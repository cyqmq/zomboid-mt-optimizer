package dev.zmtopt.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.security.ProtectionDomain;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Class-file transformer that injects optimization hooks into the game.
 *
 * <p>Hook targets are configurable through the agent argument:
 * <pre>
 *   -javaagent:zmtopt-agent.jar=hook=zombie/network/ServerMap.preupdate,hook=zombie/iso/IsoWorld.update
 * </pre>
 *
 * <p>Defaults (verified against the shipped dedicated-server jar, Build
 * 42.21.0):
 * <ul>
 *   <li><b>Dedicated server:</b> {@code zombie/network/ServerMap.preupdate} -
 *       called once per tick at the start of the simulation work.</li>
 *   <li><b>Client / single-player:</b> {@code zombie/iso/IsoWorld.update} -
 *       called every frame from {@code IngameState}.</li>
 * </ul>
 *
 * <p>When {@code -Dzmtopt.asyncSave.enable=true}, additionally redirects
 * {@code zombie.iso.IsoChunk.SafeWrite} to a background I/O thread pool
 * (C2ME-style parallel chunk save), guarded by a ThreadLocal to avoid
 * recursion when the original method runs on the worker.
 */
public final class ZMTOptTransformer implements java.lang.instrument.ClassFileTransformer {

    private static final String AGENT_INTERNAL = "dev/zmtopt/agent/ZMTOptAgent";

    private final Map<String, Set<String>> hooks = new LinkedHashMap<>();
    private final Set<String> watchClasses = new LinkedHashSet<>();
    private final boolean asyncSaveEnabled;
    private final boolean zombieAIHookEnabled;
    private final boolean zombieAISkipEnabled;
    private final boolean serverZombieHookEnabled;
    private volatile boolean gameClassesNotified = false;

    /**
     * Single-port optimization: when enabled via
     * {@code -Dzmtopt.singlePort.enable=true}, the server's two UDP ports
     * (default 16261 + 16262) are merged into one by overwriting the
     * {@code udpPort} constructor argument with {@code port} inside
     * {@code zombie.core.raknet.UdpEngine.<init>}.
     */
    private final boolean singlePortEnabled;

    public ZMTOptTransformer(String args, boolean asyncSaveEnabled, boolean zombieAIHookEnabled, boolean zombieAISkipEnabled, boolean serverZombieHookEnabled) {
        this.asyncSaveEnabled = asyncSaveEnabled;
        this.zombieAIHookEnabled = zombieAIHookEnabled;
        this.zombieAISkipEnabled = zombieAISkipEnabled;
        this.serverZombieHookEnabled = serverZombieHookEnabled;
        this.singlePortEnabled = Boolean.getBoolean("zmtopt.singlePort.enable");
        parseArgs(args);
        if (hooks.isEmpty()) {
            hooks.put("zombie/network/ServerMap", Set.of("preupdate"));
            hooks.put("zombie/iso/IsoWorld", Set.of("update"));
        }
        watchClasses.add("zombie/Lua/LuaManager");
    }

    private void parseArgs(String args) {
        if (args == null || args.isEmpty()) {
            return;
        }
        for (String part : args.split(",")) {
            String item = part.trim();
            if (item.startsWith("hook=")) {
                String[] classMethod = item.substring(5).split("\\.");
                if (classMethod.length == 2) {
                    hooks.computeIfAbsent(classMethod[0], k -> new LinkedHashSet<>()).add(classMethod[1]);
                }
            } else if (item.startsWith("watch=")) {
                watchClasses.add(item.substring(6));
            }
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null) {
            return null;
        }
        if (watchClasses.contains(className) && !gameClassesNotified) {
            gameClassesNotified = true;
            ZMTOptAgent.onGameClassesLoaded();
        }
        if (asyncSaveEnabled && "zombie/iso/IsoChunk".equals(className)) {
            try {
                return instrumentAsyncSave(classfileBuffer, className);
            } catch (Throwable t) {
                System.err.println("[ZMTOpt] async-save instrumentation failed for " + className + ": " + t);
                return null;
            }
        }
        if ((zombieAIHookEnabled || zombieAISkipEnabled) && "zombie/characters/IsoZombie".equals(className)) {
            try {
                return zombieAISkipEnabled
                        ? instrumentZombieAISkip(classfileBuffer, className)
                        : instrumentZombieAIDiagnostic(classfileBuffer, className);
            } catch (Throwable t) {
                System.err.println("[ZMTOpt] zombie-AI instrumentation failed for " + className + ": " + t);
                return null;
            }
        }
        if (serverZombieHookEnabled && isServerZombieClass(className)) {
            try {
                return instrumentServerZombieDiagnostic(classfileBuffer, className);
            } catch (Throwable t) {
                System.err.println("[ZMTOpt] server-zombie instrumentation failed for " + className + ": " + t);
                return null;
            }
        }
        if (singlePortEnabled && isUdpEngine(className)) {
            try {
                return instrumentSinglePort(classfileBuffer, className);
            } catch (Throwable t) {
                System.err.println("[ZMTOpt] single-port instrumentation failed for " + className + ": " + t);
                return null;
            }
        }
        Set<String> methods = hooks.get(className);
        if (methods == null || methods.isEmpty()) {
            return null;
        }
        try {
            return instrument(classfileBuffer, className, methods);
        } catch (Throwable t) {
            System.err.println("[ZMTOpt] instrumentation failed for " + className + ": " + t);
            return null;
        }
    }

    private static boolean isUdpEngine(String className) {
        return "zombie/core/raknet/UdpEngine".equals(className)
                || "zombie/spnetwork/UdpEngine".equals(className);
    }

    private static boolean isServerZombieClass(String className) {
        return "zombie/VirtualZombieManager".equals(className)
                || "zombie/popman/ZombiePopulationManager".equals(className);
    }

    /**
     * Diagnostic hook for the server-side zombie simulation (phase 2 starter).
     * When {@code -Dzmtopt.serverZombie.hook=true}, injects
     * {@code ZMTOptAgent.onServerZombieTick()} into
     * {@code VirtualZombieManager.update()} and
     * {@code ZombiePopulationManager.updateRealZombies()}. This proves whether
     * the server's zombie simulation is reachable via the Java classloader.
     */
    private byte[] instrumentServerZombieDiagnostic(byte[] classfileBuffer, String className) {
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                boolean target = "zombie/VirtualZombieManager".equals(className)
                        ? "update".equals(name)
                        : "updateRealZombies".equals(name);
                if (!target || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE)) != 0) {
                    return mv;
                }
                System.out.println("[ZMTOpt] server-zombie diagnostic hooking " + className + "#" + name + descriptor);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        visitMethodInsn(Opcodes.INVOKESTATIC, AGENT_INTERNAL,
                                "onServerZombieTick", "()V", false);
                    }
                };
            }
        };
        reader.accept(visitor, ClassReader.SKIP_FRAMES);
        return writer.toByteArray();
    }

    private byte[] instrument(byte[] classfileBuffer, String className, Set<String> methodsToHook) {
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE)) != 0) {
                    return mv;
                }
                if (!methodsToHook.contains(name)) {
                    return mv;
                }
                System.out.println("[ZMTOpt] hooking " + className + "#" + name + descriptor);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        visitMethodInsn(Opcodes.INVOKESTATIC, AGENT_INTERNAL,
                                "onGameTick", "()V", false);
                    }
                };
            }
        };
        reader.accept(visitor, ClassReader.SKIP_FRAMES);
        return writer.toByteArray();
    }

    /**
     * Redirects {@code IsoChunk.SafeWrite(int,int,ByteBuffer)} to a background
     * thread pool. Injected code:
     * <pre>
     *   if (!ZMTOptAgent.shouldOffloadSave()) goto original
     *   ZMTOptAgent.onSafeWrite(wx, wy, bb)
     *   return
     * original:
     * </pre>
     * The ThreadLocal guard makes {@code shouldOffloadSave()} return false when
     * the original method runs on the worker, so the original body executes
     * without recursion.
     */
    private byte[] instrumentAsyncSave(byte[] classfileBuffer, String className) {
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"SafeWrite".equals(name) || !"(IILjava/nio/ByteBuffer;)V".equals(descriptor)) {
                    return mv;
                }
                System.out.println("[ZMTOpt] async-save: hooking " + className + "#SafeWrite");
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        Label fallthrough = new Label();
                        visitMethodInsn(Opcodes.INVOKESTATIC, AGENT_INTERNAL,
                                "shouldOffloadSave", "()Z", false);
                        visitJumpInsn(Opcodes.IFEQ, fallthrough);
                        visitVarInsn(Opcodes.ILOAD, 0); // wx
                        visitVarInsn(Opcodes.ILOAD, 1); // wy
                        visitVarInsn(Opcodes.ALOAD, 2); // ByteBuffer
                        visitMethodInsn(Opcodes.INVOKESTATIC, AGENT_INTERNAL,
                                "onSafeWrite", "(IILjava/nio/ByteBuffer;)V", false);
                        visitInsn(Opcodes.RETURN);
                        visitLabel(fallthrough);
                    }
                };
            }
        };
        reader.accept(visitor, ClassReader.SKIP_FRAMES);
        return writer.toByteArray();
    }

    /**
     * Diagnostic scaffold for wiring {@code ZombieAISharder} into the real
     * game. When {@code -Dzmtopt.zombieAI.hook=true}, injects a call to
     * {@code ZMTOptAgent.onZombieUpdateTick()} at the entry of every
     * {@code IsoZombie.update} method. This only counts updates (no behavior
     * change); the sharder-based skip logic is the next phase and should be
     * validated on a running server first.
     */
    private byte[] instrumentZombieAIDiagnostic(byte[] classfileBuffer, String className) {
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"update".equals(name)
                        || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE)) != 0) {
                    return mv;
                }
                System.out.println("[ZMTOpt] zombie-AI diagnostic hooking " + className + "#update" + descriptor);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        visitMethodInsn(Opcodes.INVOKESTATIC, AGENT_INTERNAL,
                                "onZombieUpdateTick", "()V", false);
                    }
                };
            }
        };
        reader.accept(visitor, ClassReader.SKIP_FRAMES);
        return writer.toByteArray();
    }

    /**
     * Injects actual frame-time-distribution skip logic into
     * {@code IsoZombie.update} (when {@code -Dzmtopt.zombieAI.skip=true}):
     * <pre>
     *   int id = this.getID();
     *   float x = this.getX();
     *   float y = this.getY();
     *   if (ZMTOptAgent.shouldSkipZombieUpdate(id, x, y)) return;
     * </pre>
     * The methods are declared on {@code zombie/iso/IsoMovingObject}, which
     * {@code IsoZombie} inherits from.
     */
    private byte[] instrumentZombieAISkip(byte[] classfileBuffer, String className) {
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"update".equals(name)
                        || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE)) != 0) {
                    return mv;
                }
                System.out.println("[ZMTOpt] zombie-AI skip hooking " + className + "#update" + descriptor);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        Label continueUpdate = new Label();
                        // int id = this.getID()
                        visitVarInsn(Opcodes.ALOAD, 0);
                        visitMethodInsn(Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoMovingObject", "getID", "()I", false);
                        // float x = this.getX()
                        visitVarInsn(Opcodes.ALOAD, 0);
                        visitMethodInsn(Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoMovingObject", "getX", "()F", false);
                        // float y = this.getY()
                        visitVarInsn(Opcodes.ALOAD, 0);
                        visitMethodInsn(Opcodes.INVOKEVIRTUAL, "zombie/iso/IsoMovingObject", "getY", "()F", false);
                        // if (!shouldSkipZombieUpdate(id, x, y)) continue
                        visitMethodInsn(Opcodes.INVOKESTATIC, AGENT_INTERNAL,
                                "shouldSkipZombieUpdate", "(IFF)Z", false);
                        visitJumpInsn(Opcodes.IFEQ, continueUpdate);
                        visitInsn(Opcodes.RETURN);
                        visitLabel(continueUpdate);
                    }
                };
            }
        };
        reader.accept(visitor, ClassReader.SKIP_FRAMES);
        return writer.toByteArray();
    }

    /**
     * Forces {@code udpPort = port} at the start of the UdpEngine constructor,
     * so the server listens on a single UDP port instead of two.
     * Local indices for {@code UdpEngine(int port, int udpPort, int maxConnections,
     * String serverPassword, boolean bListen)}: this=0, port=1, udpPort=2.
     */
    private byte[] instrumentSinglePort(byte[] classfileBuffer, String className) {
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"<init>".equals(name) || !"(IIILjava/lang/String;Z)V".equals(descriptor)) {
                    return mv;
                }
                System.out.println("[ZMTOpt] single-port: forcing udpPort=port in " + className);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        visitVarInsn(Opcodes.ILOAD, 1);  // port
                        visitVarInsn(Opcodes.ISTORE, 2); // udpPort = port
                    }
                };
            }
        };
        reader.accept(visitor, ClassReader.SKIP_FRAMES);
        return writer.toByteArray();
    }
}