plugins {
    alias(libs.plugins.jmh)
}

dependencies {
    jmh(project(":clients"))
}

jmh {
    jmhVersion = libs.versions.jmh.asProvider()
    // Defaults live in the benchmark annotations; run via scripts/bench.sh with the fat jar
    // (build/libs/benchmarks-jmh.jar) so CLI flags and CPU pinning stay explicit.
}
