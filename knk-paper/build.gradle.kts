val devServerDirPath = (findProperty("devServerDirectory") as String?) ?: "../../DEV_SERVER_1.21.10"

plugins {
    id("com.gradleup.shadow") version "8.3.11"
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

    compileOnly("io.papermc.paper:paper-api:${property("paperApiVersion")}")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.4.6")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.20")
    // Log4j core (provided by the server) for the private-message command-log filter (KNG-18 Phase 3)
    compileOnly("org.apache.logging.log4j:log4j-core:2.24.1")
    
    testImplementation("io.papermc.paper:paper-api:${property("paperApiVersion")}")
    // One JUnit version for every artifact (api, engine, platform). Without the BOM, a transitive
    // junit-jupiter-api (mockito-junit-jupiter brings 5.13.4) upgrades api/commons while the engine stays at
    // the explicit version, and the engine then fails test discovery with NoSuchMethodError.
    testImplementation(platform("org.junit:junit-bom:${property("junitBomVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // Content-port seed contract test reads knk-web-api's exported seed JSON through the api-client DTOs.
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    testImplementation("org.mockito:mockito-core:5.24.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.24.0")
    testImplementation("org.apache.logging.log4j:log4j-core:2.24.1")
    // KNG-103: region rename/replace tests build real WorldGuard regions around a mocked RegionManager (core
    // artifacts only: the bukkit ones bring an older spigot-api).
    testImplementation("com.sk89q.worldedit:worldedit-core:7.4.6")
    testImplementation("com.sk89q.worldguard:worldguard-core:7.0.20")
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
        // SignalR client (PandiO/knk-workspace#178): its RxJava and Gson must not meet the server's copies.
        relocate("io.reactivex.rxjava3", "net.knightsandkings.knk.libs.rxjava3")
        relocate("org.reactivestreams", "net.knightsandkings.knk.libs.reactivestreams")
        relocate("com.google.gson", "net.knightsandkings.knk.libs.gson")
        // slf4j comes with Paper; SignalR's log lines go to the server log through it.
        dependencies {
            exclude(dependency("org.slf4j:.*"))
        }
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
