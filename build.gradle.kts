allprojects {
    group = "com.knk"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

subprojects {
    // Zorgt dat elk subproject de Java plugin heeft
    plugins.apply("java")

    // Zet JDK 25 toolchain (Minecraft 26.x vereist Java 25) voor alle subprojects
    extensions.configure<org.gradle.api.plugins.JavaPluginExtension> {
        toolchain {
            languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(25))
        }
    }

    // Encoding consistent (optioneel, maar netjes)
    tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
}
