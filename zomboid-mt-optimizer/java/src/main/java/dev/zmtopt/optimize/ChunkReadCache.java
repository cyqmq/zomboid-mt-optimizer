package dev.zmtopt.optimize;

import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded in-heap cache of serialized chunk file bytes.
 *
 * <p>This is the next level beyond OS page-cache warmup: background I/O
 * threads pre-read chunk files and keep the raw bytes in the JVM heap, so a
 * chunk loader that consumes this cache never touches disk on the simulation
 * thread. It is C2ME-style parallel chunk I/O made safe - the cache never
 * modifies game objects; it only holds serialized bytes.
 *
 * <p>The cache is bounded (LRU eviction) and backed by {@link ChunkIOPool}.
 * Integration with the game's chunk loader is left to a bridge (the game's
 * chunk format is version-specific); the cache provides the storage and the
 * prefetch scheduling.
 */
public final class ChunkReadCache {

    private final ChunkIOPool ioPool;
    private final int maxEntries;
    private final Map<Long, ByteBuffer> cache;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    /**
     * @param ioPool     the chunk I/O pool to run file reads on
     * @param maxEntries maximum number of chunk buffers to keep in memory
     */
    public ChunkReadCache(ChunkIOPool ioPool, int maxEntries) {
        if (ioPool == null) {
            throw new NullPointerException("ioPool");
        }
        this.ioPool = ioPool;
        this.maxEntries = Math.max(16, maxEntries);
        this.cache = new LinkedHashMap<>(maxEntries, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, ByteBuffer> eldest) {
                return size() > ChunkReadCache.this.maxEntries;
            }
        };
    }

    /** Schedules a background read of a chunk file into the cache. */
    public void prefetch(int wx, int wy, File chunkFile) {
        if (chunkFile == null || !chunkFile.isFile()) {
            return;
        }
        long key = chunkKey(wx, wy);
        synchronized (cache) {
            if (cache.containsKey(key)) {
                return;
            }
        }
        ioPool.submit("chunk-cache-read", () -> {
            try (InputStream in = Files.newInputStream(chunkFile.toPath())) {
                long len = chunkFile.length();
                if (len <= 0 || len > Integer.MAX_VALUE) {
                    return;
                }
                ByteBuffer buf = ByteBuffer.allocate((int) len);
                byte[] tmp = new byte[8192];
                int read;
                while ((read = in.read(tmp)) != -1) {
                    buf.put(tmp, 0, read);
                }
                buf.flip();
                synchronized (cache) {
                    cache.put(key, buf);
                }
            } catch (Throwable ignored) {
                // File disappeared or became unreadable - skip.
            }
        });
    }

    /**
     * Returns a duplicate of the cached bytes for a chunk, or {@code null}
     * if the chunk is not cached (caller should fall back to synchronous I/O).
     * The returned buffer is read-only in the sense that it is a duplicate;
     * advancing its position does not affect the cached copy.
     */
    public ByteBuffer get(int wx, int wy) {
        long key = chunkKey(wx, wy);
        synchronized (cache) {
            ByteBuffer buf = cache.get(key);
            if (buf != null) {
                hits.incrementAndGet();
                return buf.duplicate();
            }
        }
        misses.incrementAndGet();
        return null;
    }

    /** Number of chunk buffers currently cached. */
    public int size() {
        synchronized (cache) {
            return cache.size();
        }
    }

    public long getHits() {
        return hits.get();
    }

    public long getMisses() {
        return misses.get();
    }

    public void clear() {
        synchronized (cache) {
            cache.clear();
        }
    }

    private static long chunkKey(int wx, int wy) {
        return (((long) wx) << 32) ^ (wy & 0xffffffffL);
    }
}