pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        gradlePluginPortal()
    }

    plugins {
        id("com.android.application") version "9.3.2"
        id("org.jetbrains.kotlin.android") version "2.4.10"
        id("org.jetbrains.kotlin.multiplatform") version "2.4.10"
        id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
        id("org.jetbrains.compose") version "1.11.1"
    }
}

// `jvmToolchain(17)` needs a JDK 17 that no machine here ships (the daemon itself
// runs on 25), so Gradle auto-provisions one. Doing that without a declared
// toolchain repository is deprecated and becomes an error in Gradle 10 — this
// resolver is the repository.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

buildscript {
    configurations.all {
        resolutionStrategy.eachDependency {
            when {
                requested.group == "io.netty" -> useVersion("4.1.137.Final")
                requested.group == "org.bouncycastle" -> useVersion("1.84")
                requested.group == "org.jsoup" -> useVersion("1.23.1")
                requested.group.startsWith("com.fasterxml.jackson") -> useVersion("2.18.10")
                requested.group == "com.google.protobuf" ->
                    if (requested.name != "protobuf-bom") useVersion("3.25.5")
                requested.group == "org.apache.commons" ->
                    if (requested.name == "commons-lang3") useVersion("3.18.0")
                requested.group == "org.apache.httpcomponents" ->
                    if (requested.name == "httpclient") useVersion("4.5.14")
                requested.group == "org.bitbucket.b_c" ->
                    if (requested.name == "jose4j") useVersion("0.9.6")
                requested.group == "org.jdom" ->
                    if (requested.name == "jdom2") useVersion("2.0.6.1")
            }
        }
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "InnerCircleSquared"

// The token service is a plain JVM module and is built in a Docker image that has
// no Android SDK. Including the Android/KMP modules there fails at plugin
// resolution, long before anything is compiled, so `-PserverOnly=true` prunes them.
// See server/Dockerfile.
include(":server")
if (providers.gradleProperty("serverOnly").orNull != "true") {
    include(":androidApp")
    include(":composeApp")
    include(":mediakit")
}
