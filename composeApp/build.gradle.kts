import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.kotlin.multiplatform.library")
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.dokka)
}

kotlin {
    jvmToolchain(17)

    android {
        namespace = "com.livingpresence.inner.circle.squared.shared"
        compileSdk = 37
        minSdk = 23
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        withHostTestBuilder {
            // isIncludeAndroidResources = true  (moved to testOptions if needed or omitted for now)
        }
    }

    // testOptions are typically not available directly in the simplified KMP DSL,
    // so we'll start with just withHostTestBuilder.

    val iosTargets = if (System.getProperty("os.name").startsWith("Mac OS X")) {
        listOf(
            iosArm64(),
            iosSimulatorArm64(),
        )
    } else emptyList()
    
    iosTargets.forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
        // AVPlayer Obj-C bridge. The Xcode 26.5 SDK + Kotlin/Native cinterop
        // combo fails to merge AVPlayer's Obj-C category methods
        // (play/pause/rate/seek/...) onto the generated AVPlayer class, so
        // those calls are unresolvable from Kotlin. This small cinterop wraps
        // them in a plain NSObject whose methods cinterop merges correctly.
        // See native/avplayer/cinterop/AVPlayerBridge.h.
        //
        // CAVEAT: Kotlin 2.3.x cinterop parses the def's `sources =` but does
        // NOT compile the Obj-C implementation into the klib/framework — so
        // _OBJC_CLASS_$_AVPlayerBridge stays undefined at link time. We work
        // around this by compiling AVPlayerBridge.m into a static archive with
        // the Konan clang + Xcode SDK ourselves (see compileAvplayerBridge*
        // below), and pointing the def's staticLibraries/libraryPaths at it.
        val cinteropDir = project.file("native/avplayer/cinterop")
        // Compile AVPlayerBridge.m to LLVM bitcode (.bc) so it can be injected
        // into the cinterop klib's natives/ directory, where K/N picks it up
        // during the framework link and embeds it alongside cstubs.bc. This is
        // the only mechanism that works for *static* frameworks: -force_load
        // and -ObjC linker opts are silently dropped by K/N's lld invocation
        // when producing a static framework archive.
        val cinteropTaskName = "cinteropAvplayer${iosTarget.targetName.replaceFirstChar { it.uppercase() }}"
        val bitcodeFile = layout.buildDirectory.file("tmp/avplayer/${iosTarget.targetName}/AVPlayerBridge.bc")
        val compileTask = tasks.register("compileAvplayerBridge${iosTarget.targetName.replaceFirstChar { it.uppercase() }}") {
            val source = cinteropDir.resolve("AVPlayerBridge.m")
            val sdk = when {
                iosTarget.targetName.contains("Simulator") || iosTarget.targetName.contains("X64") ->
                    providers.exec { commandLine("xcrun", "--sdk", "iphonesimulator", "--show-sdk-path") }.standardOutput.asText.get().trim()
                else ->
                    providers.exec { commandLine("xcrun", "--sdk", "iphoneos", "--show-sdk-path") }.standardOutput.asText.get().trim()
            }
            val target = when {
                iosTarget.targetName.contains("Simulator") -> "arm64-apple-ios-simulator"
                iosTarget.targetName.contains("X64") -> "x86_64-apple-ios-simulator"
                else -> "arm64-apple-ios"
            }
            inputs.file(source)
            inputs.property("sdk", sdk)
            inputs.property("target", target)
            outputs.file(bitcodeFile)
            doFirst {
                val konanDeps = file("${System.getProperty("user.home")}/.konan/dependencies")
                val clang = konanDeps.listFiles()?.asSequence()
                    ?.filter { it.isDirectory && it.name.startsWith("llvm-") }
                    ?.sortedByDescending { it.name }
                    ?.map { it.resolve("bin/clang") }
                    ?.firstOrNull { it.exists() }
                    ?: error("Konan clang not found in $konanDeps — install Kotlin/Native dependencies first")
                val outFile = bitcodeFile.get().asFile
                outFile.parentFile.mkdirs()
                ProcessBuilder(
                    clang.absolutePath,
                    "-c", "-emit-llvm",
                    source.absolutePath,
                    "-o", outFile.absolutePath,
                    "-isysroot", sdk,
                    "-target", target,
                    "-fobjc-arc",
                    "-I${cinteropDir.absolutePath}"
                ).redirectErrorStream(true).start().apply {
                    val output = inputStream.bufferedReader().readText()
                    if (waitFor() != 0) error("Clang failed: $output")
                }
            }
        }

        iosTarget.compilations.getByName("main").cinterops {
            create("avplayer") {
                defFile(project.file("native/avplayer/cinterop/avplayer.def"))
                // The def's `headers =` resolves relative to this dir, but cinterop's
                // clang invocation doesn't add it to the search path by default — pass
                // it explicitly so AVPlayerBridge.h is found regardless of CWD.
                compilerOpts("-I${cinteropDir.absolutePath}")
            }
        }
        // After cinterop generates the klib (which contains cstubs.bc but NOT
        // our Obj-C implementation), inject AVPlayerBridge.bc into the klib's
        // natives directory. K/N links every .bc there into the framework, so
        // the AVPlayerBridge class ends up defined (not undefined) in the
        // final binary.
        val injectTask = tasks.register("injectAvplayerBridge${iosTarget.targetName.replaceFirstChar { it.uppercase() }}") {
            dependsOn(cinteropTaskName, compileTask)
            val bc = bitcodeFile
            // The klib native target dir name follows K/N's convention:
            // iosSimulatorArm64 -> ios_simulator_arm64, iosArm64 -> ios_arm64.
            val nativeTargetName = iosTarget.targetName.split("(?=\\p{Upper})".toRegex())
                .filter { it.isNotEmpty() }
                .joinToString("_") { it.lowercase() }
            val nativesDir = layout.projectDirectory.dir(
                "build/classes/kotlin/${iosTarget.targetName}/main/cinterop/composeApp-cinterop-avplayer/default/targets/$nativeTargetName/native"
            )
            inputs.file(bc)
            outputs.dir(nativesDir)
            doLast {
                val target = nativesDir.dir("AVPlayerBridge.bc").asFile
                target.parentFile.mkdirs()
                bc.get().asFile.copyTo(target, overwrite = true)
            }
        }
        // The compile + link tasks must run after injection.
        tasks.named("compileKotlin${iosTarget.targetName.replaceFirstChar { it.uppercase() }}").configure {
            dependsOn(injectTask)
        }
        tasks.matching { it.name == "commonizeCInterop" || it.name.endsWith("Cinterop-avplayerKlib") }.configureEach {
            dependsOn(injectTask)
        }
        // Compose resources are assembled into build/generated/compose/
        // resourceGenerator/assembledResources/<target>Main/, but nothing in the
        // link graph produces them: CMP's own syncComposeResourcesForIos only
        // runs when Xcode drives Gradle, and iosApp/ links a *prebuilt*
        // framework instead. Left unwired, `linkDebugFrameworkIosSimulatorArm64`
        // succeeds while the resource dir is stale or absent, and every
        // Res.drawable.* lookup throws MissingResourceException at runtime.
        // Tying assembly to the link keeps the one documented iOS command
        // sufficient; iosApp/project.yml's "Copy Compose resources" phase then
        // copies the result into the app bundle.
        val targetCap = iosTarget.targetName.replaceFirstChar { it.uppercase() }
        tasks.matching { it.name.startsWith("link") && it.name.endsWith("Framework$targetCap") }
            .configureEach {
                dependsOn("assemble${targetCap}MainResources")
            }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("composeApp")
        browser()
        binaries.executable()
    }

    // Intermediate source set shared by every non-Android target (iOS + web). Both
    // lack ExoPlayer / a native download center and share several actuals (event-click,
    // login background) plus UI seams, so they hang off a common parent rather than
    // copy-pasting per platform. This is added by *extending* the default hierarchy
    // template (not manual dependsOn edges) so the template still wires iosMain to its
    // iosArm64/iosSimulatorArm64 leaf compilations.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("nonAndroid") {
                withWasmJs()
                withIosArm64()
                withIosSimulatorArm64()
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.lifecycle.viewmodel)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.navigation.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.kotlinx.serialization.json)
            // SHA-256 for the Discord OAuth2 PKCE code challenge. Discord accepts
            // only S256, and no target-shared hash ships with Kotlin/ktor.
            implementation(libs.kotlincrypto.sha2)
            implementation(project(":mediakit"))
        }
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.exoplayer.hls)
            implementation(libs.media3.exoplayer.workmanager)
            implementation(libs.media3.datasource)
            implementation(libs.media3.session)
            implementation(libs.media3.ui.compose)
            implementation(libs.media3.ui.compose.material3)
            implementation(libs.androidx.work.runtime.ktx)
            implementation(libs.splash.screen.support)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
        // Guarded because `iosTargets` above is empty off macOS. Touching the
        // `iosMain` accessor there materialises a source set that no compilation
        // owns, which KGP reports twice per build ("iOS Source Set Used Without
        // an iOS Target" + "Unused Kotlin Source Sets").
        if (iosTargets.isNotEmpty()) {
            iosMain.dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
        // Robolectric unit tests for Android player/resize logic.
        getByName("androidHostTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.media3.test.utils.robolectric)
                implementation(libs.kotlinx.coroutines.test)
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
                implementation(compose.uiTooling)
            }
        }
    }
}

// Web (wasmJs) transcription key provisioning — mirror of the Android BuildConfig
// approach (androidApp/build.gradle.kts): read the gitignored secrets.properties and
// generate a Kotlin constants file into the wasmJs source set.
// NOTE: these keys are embedded in the web bundle and are extractable by anyone who
// loads the page — a dev/portfolio convenience, not production key handling. For
// production, proxy the websocket through a backend that holds the key.
val transcriptionSecrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val generateWebTranscriptionKeys = tasks.register("generateWebTranscriptionKeys") {
    val outputDir = layout.buildDirectory.dir("generated/transcriptionKeys/wasmJsMain")
    outputs.dir(outputDir)
    val deepgram = transcriptionSecrets.getProperty("DEEPGRAM_API_KEY", "")
    val soniox = transcriptionSecrets.getProperty("SONIOX_API_KEY", "")
    // Discord OAuth config for the landing screen's Apollo gate — public values,
    // carried in the same file so a fork configures its own Discord application.
    val discordClientId = transcriptionSecrets.getProperty("DISCORD_CLIENT_ID", "")
    val apolloGuildId = transcriptionSecrets.getProperty("APOLLO_GUILD_ID", "")
    // Raw URL of the extra-videos manifest (a secret gist) — see FeedConfig.
    val extraVideosUrl = transcriptionSecrets.getProperty("EXTRA_VIDEOS_URL", "")
    // Scheme + authority of the stream server — see MediaKitConfig.defaultHost.
    val streamHost = transcriptionSecrets.getProperty("STREAM_HOST", "")
    // Track key values so the task re-runs when they change.
    inputs.property("deepgram", deepgram)
    inputs.property("soniox", soniox)
    inputs.property("discordClientId", discordClientId)
    inputs.property("apolloGuildId", apolloGuildId)
    inputs.property("extraVideosUrl", extraVideosUrl)
    inputs.property("streamHost", streamHost)
    doLast {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\${'$'}")
        val dir = outputDir.get().asFile
        dir.mkdirs()
        dir.resolve("TranscriptionKeys.kt").writeText(
            """
            package com.livingpresence.inner.circle.squared

            // Generated from secrets.properties at build time — do not edit or commit.
            internal object TranscriptionKeys {
                const val DEEPGRAM_API_KEY = "${esc(deepgram)}"
                const val SONIOX_API_KEY = "${esc(soniox)}"
                const val DISCORD_CLIENT_ID = "${esc(discordClientId)}"
                const val APOLLO_GUILD_ID = "${esc(apolloGuildId)}"
                const val EXTRA_VIDEOS_URL = "${esc(extraVideosUrl)}"
                const val STREAM_HOST = "${esc(streamHost)}"
            }
            """.trimIndent() + "\n"
        )
    }
}

// iOS reads the same secrets.properties through an xcconfig the Xcode target
// includes. Generated from Gradle rather than an Xcode build phase because Xcode
// parses xcconfig files *before* any build phase runs: a script phase writing
// this file is always one build behind, so an edited key silently ships stale
// until you build twice. Gradle runs first in the documented iOS flow (link the
// framework, then xcodebuild), which puts the values on disk in time.
val iosSecretKeys = listOf(
    "DEEPGRAM_API_KEY",
    "SONIOX_API_KEY",
    "DISCORD_CLIENT_ID",
    "APOLLO_GUILD_ID",
    "STREAM_HOST",
    "EXTRA_VIDEOS_URL",
)
val generateIosSecretsXcconfig = tasks.register("generateIosSecretsXcconfig") {
    description = "Writes iosApp/Secrets.xcconfig from the gitignored secrets.properties."
    val outputFile = rootProject.file("iosApp/Secrets.xcconfig")
    outputs.file(outputFile)
    val values = iosSecretKeys.associateWith { transcriptionSecrets.getProperty(it, "") }
    // Track the values so the task re-runs when secrets.properties changes.
    values.forEach { (key, value) -> inputs.property(key, value) }
    doLast {
        // `//` opens a comment in an xcconfig, which would truncate every URL
        // value to its scheme ("https:"). Xcode evaluates build settings
        // recursively, so routing each slash through SLASH round-trips intact.
        fun esc(s: String) = s.replace("/", "\$(SLASH)")
        outputFile.writeText(
            buildString {
                appendLine("// Generated from secrets.properties at build time — do not edit or commit.")
                appendLine("SLASH = /")
                iosSecretKeys.forEach { appendLine("$it = ${esc(values.getValue(it))}") }
                // Mirrors androidApp/build.gradle.kts: an unconfigured build still
                // has to register some scheme, and "discord-unset" never matches.
                val clientId = values.getValue("DISCORD_CLIENT_ID").ifBlank { "unset" }
                appendLine("DISCORD_REDIRECT_SCHEME = discord-$clientId")
            }
        )
    }
}
tasks.matching { it.name.startsWith("link") && it.name.contains("FrameworkIos") }.configureEach {
    dependsOn(generateIosSecretsXcconfig)
}
kotlin.sourceSets.named("wasmJsMain") {
    kotlin.srcDir(generateWebTranscriptionKeys)
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.livingpresence.inner.circle.squared.generated.resources"
}

// Cache-bust the fixed-name composeApp.js in the generated index.html. Browsers cache
// it aggressively (it has no content hash in its name), so after a rebuild a plain
// reload can run a stale bundle. After each web distribution we append a content-hash
// query (?v=<hash>) to the <script src>, so the browser refetches only when the bundle
// actually changed.
tasks.matching {
    it.name.matches(Regex("wasmJsBrowser(Development|Production)ExecutableDistribution"))
}.configureEach {
    val variantDir = if (name.contains("Development")) "developmentExecutable" else "productionExecutable"
    val distDir = layout.buildDirectory.dir("dist/wasmJs/$variantDir")
    doLast {
        val dir = distDir.get().asFile
        val indexHtml = dir.resolve("index.html")
        val jsFile = dir.resolve("composeApp.js")
        if (!indexHtml.exists() || !jsFile.exists()) return@doLast
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(jsFile.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            .take(10)
        val marker = "src=\"./composeApp.js\""
        val text = indexHtml.readText()
        if (marker in text) {
            indexHtml.writeText(text.replace(marker, "src=\"./composeApp.js?v=$hash\""))
        }
    }
}

dependencies {
    // Debug dependencies moved to androidApp or runtime classpath
}
