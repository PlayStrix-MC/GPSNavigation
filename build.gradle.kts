plugins {
    java
}

group = "com.gpsnavigation"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// Обычная сборка использует настоящий Paper API (Purpur 1.21.11 полностью с ним совместим).
// Для офлайн-проверки компиляции можно передать -PlocalApiJar=путь/к/api.jar
val localApiJar = providers.gradleProperty("localApiJar").orNull

dependencies {
    // DisplayLib (необязательная интеграция): положите его jar в папку libs/ для компиляции.
    compileOnly(fileTree("libs") { include("*.jar") })
    if (localApiJar != null) {
        compileOnly(files(localApiJar))
    } else {
        compileOnly("io.papermc.paper:paper-api:${providers.gradleProperty("paperApiVersion").get()}")
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.jar {
    archiveFileName.set("GPSNavigation-${project.version}.jar")
    // Плагин обращается к классам сервера по mojang-именам — Paper не должен их переименовывать.
    manifest {
        attributes("paperweight-mappings-namespace" to "mojang")
    }
}
