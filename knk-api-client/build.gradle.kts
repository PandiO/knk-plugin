dependencies {
    implementation(project(":knk-core"))

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")

    testImplementation(platform("org.junit:junit-bom:${property("junitBomVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // Explicit launcher (version from the BOM): Gradle 8.14 otherwise injects its bundled 1.8.2 launcher, which
    // cannot drive a newer engine, and Gradle 9 requires the dependency anyway.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
