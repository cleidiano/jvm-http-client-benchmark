// Adapters for raw HTTP client libraries (no framework layer), and NativeClients, where each
// client is configured. The libraries are `api` so that clients-abstractions can wrap the exact
// same configured instances.
dependencies {
    api(project(":bench-api"))
    api(libs.httpclient5)
    api(libs.okhttp)
    api(libs.jetty.client)
    api(libs.reactor.netty.http)
}
