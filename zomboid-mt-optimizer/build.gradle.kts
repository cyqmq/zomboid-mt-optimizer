plugins {
    java
}

group = "dev.zmtopt"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    //
    // ASM 9.11 is used ONLY by the javaagent (bytecode hooks) and is bundled
    // into the agent jar so the agent has no external runtime dependency.
    // 9.11 is required: the game ships with JRE 25 and its classes use
    // class file major version 69, which older ASM versions cannot read.
    implementation("org.ow2.asm:asm:9.11")

    // Test framework
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.jar {
    archiveBaseName.set("zmtopt")
    archiveVersion.set("")
    manifest {
        attributes["Implementation-Title"] = "Zomboid MT Optimizer"
        attributes["Implementation-Version"] = project.version
        attributes["Implementation-Vendor"] = "ZMTOpt"
    }
}

// The javaagent fat jar: contains the agent entry point + ASM + all mod
// classes. Launch the game/server with:
//   -javaagent:/path/to/zmtopt-agent.jar=hook=zombie/game/GameServer.update
val agentJar = tasks.register<Jar>("agentJar") {
    archiveBaseName.set("zmtopt-agent")
    archiveVersion.set("")
    manifest {
        attributes["Premain-Class"] = "dev.zmtopt.agent.ZMTOptAgent"
        attributes["Agent-Class"] = "dev.zmtopt.agent.ZMTOptAgent"
        attributes["Can-Redefine-Classes"] = "true"
        attributes["Can-Retransform-Classes"] = "true"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) })
}

sourceSets {
    main {
        java.setSrcDirs(listOf("java/src/main/java"))
    }
    test {
        java.setSrcDirs(listOf("java/src/test/java"))
    }
}

tasks.compileJava {
    options.encoding = "UTF-8"
    options.release.set(17)
}