repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper API for Bukkit/Spigot compatibility (compileOnly - API is provided at runtime)
    compileOnly("io.papermc.paper:paper-api:${property("paperApiVersion")}")
    
    // Jackson for JSON processing
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.google.code.gson:gson:2.10.1")
    
    testImplementation(platform("org.junit:junit-bom:${property("junitBomVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // Explicit launcher (version from the BOM): Gradle 8.14 otherwise injects its bundled 1.8.2 launcher, which
    // cannot drive a newer engine, and Gradle 9 requires the dependency anyway.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Paper API for tests (needed for Vector class in unit tests)
    testImplementation("io.papermc.paper:paper-api:${property("paperApiVersion")}")
}

tasks.test {
    useJUnitPlatform()
}
