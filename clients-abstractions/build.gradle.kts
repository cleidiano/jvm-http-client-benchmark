// Abstraction layers on top of the raw clients: Spring RestClient and WebClient, and OpenFeign.
// Every adapter wraps a client built by NativeClients, so a layer is always compared with the
// raw client configured exactly the same way.
dependencies {
    implementation(project(":clients"))
    implementation(libs.spring.web)
    implementation(libs.spring.webflux)
    implementation(libs.spring.context)              // Reactor integrations implement SmartLifecycle
    implementation(libs.httpcore5.reactive)          // WebClient over the Apache 5 async client
    implementation(libs.jetty.reactive.httpclient)   // WebClient over Jetty
    implementation(libs.feign.core)
    implementation(libs.feign.hc5)
    implementation(libs.feign.okhttp)
    implementation(libs.feign.java11)
}
