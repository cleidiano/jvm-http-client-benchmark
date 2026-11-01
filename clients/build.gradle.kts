// Adapters for raw HTTP client libraries (no framework layer).
dependencies {
    api(project(":bench-api"))
    implementation(libs.httpclient5)
    implementation(libs.okhttp)
    implementation(libs.jetty.client)
}
