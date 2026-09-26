val devServerDirPath = (findProperty("devServerDirectory") as String?) ?: "../../DEV_SERVER_1.21.10"

plugins {
    id("com.gradleup.shadow") version "8.3.5"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    // EngineHub repo voor WorldGuard/WorldEdit
    maven("https://maven.enginehub.org/repo/")
    maven("https://jitpack.io")
}

dependencies {
    implementation(project(":knk-core"))
    implementation(project(":knk-api-client"))

    compileOnly("io.papermc.paper:paper-api:1.21.10-R0.1-SNAPSHOT")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.2.13")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.10")
    
    testImplementation("io.papermc.paper:paper-api:1.21.10-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    // Content-port seed contract test reads knk-web-api's exported seed JSON through the api-client DTOs.
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    testImplementation("org.mockito:mockito-core:5.5.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.5.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform {
        // Skip integration tests by default during build
        excludeTags("integration", "requires-bukkit")
    }
}

val devPluginsDir: File = try {
    rootProject.layout.projectDirectory
        .dir(devServerDirPath)
        .dir("plugins")
        .asFile
} catch (e: Exception) {
    file(".") // Fallback if path resolution fails
}

tasks {
    shadowJar {
        archiveClassifier.set("")
        
        // Relocate dependencies to avoid conflicts with Paper's bundled libraries
        relocate("com.fasterxml.jackson", "net.knightsandkings.knk.libs.jackson")
        relocate("okhttp3", "net.knightsandkings.knk.libs.okhttp3")
        relocate("okio", "net.knightsandkings.knk.libs.okio")
    }

    register<Copy>("deployToDevServer") {
        dependsOn(shadowJar)
        mustRunAfter(jar)

        from(shadowJar.get().archiveFile)
        into(devPluginsDir)

        doFirst {
            println("Deploying plugin jar to: ${devPluginsDir.absolutePath}")
        }
    }

    // “Dev” convenience task: build (shadowJar) + deploy
    register("dev") {
        dependsOn("shadowJar")
        dependsOn("deployToDevServer")
    }

    // Optioneel: laat een normale build ook deployen
    build {
        dependsOn("deployToDevServer")
    }
}
