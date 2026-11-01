subprojects {
    apply(plugin = "java-library")

    group = "io.github.cleidiano.httpbench"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(21)
    }
}
