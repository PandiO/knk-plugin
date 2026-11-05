dependencies {
    implementation(project(":knk-core"))

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")
    // Realtime channel (PandiO/knk-workspace#178): the official SignalR Java client. It brings RxJava 3 and uses okhttp
    // 4.12.0 (the version above); Gson is pinned to a release that handles current JDKs. knk-paper's shadow jar relocates
    // RxJava and Gson and leaves slf4j to the server.
    implementation("com.microsoft.signalr:signalr:10.0.13")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(platform("org.junit:junit-bom:${property("junitBomVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // Explicit launcher (version from the BOM): Gradle 8.14 otherwise injects its bundled 1.8.2 launcher, which
    // cannot drive a newer engine, and Gradle 9 requires the dependency anyway.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
