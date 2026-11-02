plugins {
    alias(libs.plugins.jmh)
}

dependencies {
    jmh(project(":clients"))
    jmh(project(":clients-abstractions"))
}

jmh {
    jmhVersion = libs.versions.jmh.asProvider()
    // Defaults live in the benchmark annotations; run via scripts/bench.sh with the fat jar
    // (build/libs/benchmarks-jmh.jar) so CLI flags and CPU pinning stay explicit.
}

// Each client module registers its providers in the same META-INF/services file. jmhJar is a
// plain Jar, which copies both files under one name, and the JVM then only reads the first:
// half of the clients would silently disappear. Merge them into one file instead.
val serviceFile = "META-INF/services/io.github.cleidiano.httpbench.api.BenchClientProvider"
val mergedServicesDir = layout.buildDirectory.dir("merged-services")

val mergeServiceFiles by tasks.registering {
    val classpath = configurations.named("jmhRuntimeClasspath")
    inputs.files(classpath)
    outputs.dir(mergedServicesDir)
    doLast {
        val providers = classpath.get().files.flatMap { entry ->
            val tree = if (entry.isDirectory) fileTree(entry) else zipTree(entry)
            tree.matching { include(serviceFile) }.files.flatMap { it.readLines() }
        }.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.distinct()
        val merged = mergedServicesDir.get().file(serviceFile).asFile
        merged.parentFile.mkdirs()
        merged.writeText(providers.joinToString("\n", postfix = "\n"))
    }
}

tasks.named<Jar>("jmhJar") {
    dependsOn(mergeServiceFiles)
    from(mergedServicesDir)
    val mergedRoot = mergedServicesDir.get().asFile.toPath()
    eachFile {
        // Keep only the merged copy of the service file.
        if (path == serviceFile && !file.toPath().startsWith(mergedRoot)) {
            exclude()
        }
    }
}
