buildscript {
    // The Android Gradle Plugin and Dokka drag vulnerable transitive libraries (netty,
    // bouncycastle, jackson, jsoup, commons-lang3, httpclient, jose4j, jdom2, protobuf) onto the
    // plugin classpath. They surface in GitHub's dependency graph and trip Dependabot alerts.
    // Force patched versions on the buildscript classpath so only fixed versions are ever resolved.
    // (Inlined rather than shared with the allprojects block below: the buildscript block is
    // evaluated before top-level script declarations exist.)
    configurations.all {
        resolutionStrategy.eachDependency {
            when {
                requested.group == "io.netty" -> useVersion("4.1.137.Final")
                requested.group == "org.bouncycastle" -> useVersion("1.84")
                requested.group == "org.jsoup" -> useVersion("1.23.1")
                // Covers jackson-core/databind/annotations plus the dataformat, module and bom
                // artifacts, which all share one version line. Dokka 2.2.0 requests 2.15.3;
                // 2.18.10 is the newest 2.18.x and clears every open jackson advisory. (Dokka
                // <= 2.1.0 could not be forced past 2.15 — it called TypeFactory(LRUMap), removed
                // in jackson 2.16 — which is why jackson used to be excluded here.)
                requested.group.startsWith("com.fasterxml.jackson") -> useVersion("2.18.10")
                requested.group == "com.google.protobuf" ->
                    if (requested.name != "protobuf-bom") useVersion("3.25.5")
                requested.group == "org.apache.commons" ->
                    if (requested.name == "commons-lang3") useVersion("3.18.0")
                requested.group == "org.apache.httpcomponents" ->
                    if (requested.name == "httpclient") useVersion("4.5.14")
                // Dokka 2.2.0 pulls Apache HttpClient 5 for its analysis, and 5.5.1 /
                // 5.3.6 carry open advisories. These are the 5.x line under different
                // group ids from the httpclient 4.x above, so they need their own
                // entries — matching on "org.apache.httpcomponents" does not cover them.
                requested.group == "org.apache.httpcomponents.core5" -> useVersion("5.4.3")
                requested.group == "org.apache.httpcomponents.client5" -> useVersion("5.6.3")
                requested.group == "org.bitbucket.b_c" ->
                    if (requested.name == "jose4j") useVersion("0.9.6")
                requested.group == "org.jdom" ->
                    if (requested.name == "jdom2") useVersion("2.0.6.1")
            }
        }
    }
}

plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    // Declared here, not only in :server: the Kotlin plugin is already on the root
    // buildscript classpath via the multiplatform alias, and a versioned request
    // from a subproject cannot be version-checked against it ("already on the
    // classpath with an unknown version"). Pinning it once at the root resolves it.
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinx.serialization) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    // SDK discipline (plan.md Phase 1/6): Dokka API docs + Kover coverage gate.
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.kover) apply true
}

// Dummy task to satisfy GitHub CodeQL Default Setup Autobuilder.
// CodeQL executes `testClasses` by default. We map this to all Kotlin compilation tasks
// so CodeQL can trace the compiler invocations without triggering native linking (which causes OOMs).
val isMac = System.getProperty("os.name").startsWith("Mac OS X")

// Force patched versions of vulnerable transitive dependencies (Dependabot alerts). The same
// libraries the AGP plugin classpath pulls in (see the buildscript block) also appear in
// AGP-injected project configurations — e.g. the Unified Test Platform, which brings in
// protobuf-java/protobuf-kotlin. Apply the override to every project configuration. Keep this
// list in sync with the buildscript block above.
fun DependencyResolveDetails.forceSecurityPatchedVersions() {
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
        requested.group == "org.apache.httpcomponents.core5" -> useVersion("5.4.3")
        requested.group == "org.apache.httpcomponents.client5" -> useVersion("5.6.3")
        requested.group == "org.bitbucket.b_c" ->
            if (requested.name == "jose4j") useVersion("0.9.6")
        requested.group == "org.jdom" ->
            if (requested.name == "jdom2") useVersion("2.0.6.1")
    }
}

allprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency { forceSecurityPatchedVersions() }
    }
    tasks.configureEach {
        if (name.contains("Ios", ignoreCase = true) && !isMac) {
            enabled = false
        }
    }
}

// Kotlin/Wasm's browser toolchain pins a vulnerable `ws` (< 8.21.0) in
// kotlin-js-store/wasm/yarn.lock. Force the patched version via a Yarn resolution so the
// regenerated lockfile records 8.21.0 (Dependabot npm alert).
plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootExtension>().resolution("ws", "8.21.0")
}

tasks.register("testClasses") {
    // This task is an empty placeholder because CodeQL autobuilder hardcodes `testClasses`.
    // It's already defined for subprojects, so creating it in root project satisfies CodeQL if it runs it at root.
    dependsOn(subprojects.map { it.tasks.matching { t -> t.name == "testClasses" } })
    subprojects {
        val subproject = this
        this@register.dependsOn(subproject.tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") })
    }
}
