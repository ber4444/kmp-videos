pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        gradlePluginPortal()
    }

    plugins {
        id("com.android.application") version "9.3.1"
        id("org.jetbrains.kotlin.android") version "2.4.10"
        id("org.jetbrains.kotlin.multiplatform") version "2.4.10"
        id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
        id("org.jetbrains.compose") version "1.11.1"
    }
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
include(":androidApp")
include(":composeApp")
include(":mediakit")
