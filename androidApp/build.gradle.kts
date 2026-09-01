import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.compose.compiler)
}

// The Play upload key lives outside the repo; `~/key.properties` points at the
// keystore so release builds carry the certificate Play expects. When it's absent
// (fresh clone, CI) release falls back to the debug key — fine for local installs,
// and Play rejects the upload rather than accepting a wrongly signed build.
val uploadKeyProperties = Properties().apply {
    val f = File(System.getProperty("user.home"), "key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.livingpresence.inner.circle.squared"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.livingpresence.inner.circle.squared"
        minSdk = 23
        targetSdk = 36
        versionCode = 7015
        versionName = "8.1.3"
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        // Discord mandates the redirect scheme `discord-<APP_ID>` for mobile deep
        // links. Generated from the same secrets.properties value the runtime
        // reads, so the manifest filter and the redirect URI cannot drift apart.
        // Placeholder must be non-empty even when unconfigured or the manifest
        // merger fails, hence the "unset" sentinel (which simply never matches).
        val discordClientIdForScheme = Properties().apply {
            val f = rootProject.file("secrets.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }.getProperty("DISCORD_CLIENT_ID", "").ifBlank { "unset" }
        manifestPlaceholders["discordRedirectScheme"] = "discord-$discordClientIdForScheme"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }


    signingConfigs {
        if (uploadKeyProperties.getProperty("storeFile") != null) {
            create("upload") {
                storeFile = File(uploadKeyProperties.getProperty("storeFile"))
                storePassword = uploadKeyProperties.getProperty("storePassword")
                keyAlias = uploadKeyProperties.getProperty("keyAlias")
                keyPassword = uploadKeyProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Build configuration read from the gitignored `secrets.properties` at the repo
// root (copy secrets.properties.example) and exposed via BuildConfig.
//
// NOTE: BuildConfig strings are plain constants in `classes.dex` — `unzip` and
// `strings` are enough to read them out of a published APK, and R8 does not
// obscure them. Nothing secret may go through here. The Soniox and Deepgram API
// keys used to, which is why the app now ships only the URL of the service that
// holds the Soniox key (see :server and TranscriptionSecrets).
val transcriptionSecrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

androidComponents {
    onVariants { variant ->
        // Base URL of the temporary-key service, NOT a key: it mints the
        // short-lived Soniox credential each caption session connects with, so
        // nothing long-lived is compiled into the app. Empty → captions report
        // themselves unconfigured instead of connecting.
        val sonioxTokenUrl = transcriptionSecrets.getProperty("SONIOX_TOKEN_URL", "")
        variant.buildConfigFields?.put("SONIOX_TOKEN_URL", com.android.build.api.variant.BuildConfigField("String", "\"$sonioxTokenUrl\"", "Base URL of the Soniox temporary-key service"))

        // Discord OAuth config for the landing screen's Apollo gate. Not secrets
        // (the client id is public and the guild id is a snowflake), but kept in
        // the same gitignored file so a fork configures its own Discord app.
        val discordClientId = transcriptionSecrets.getProperty("DISCORD_CLIENT_ID", "")
        variant.buildConfigFields?.put("DISCORD_CLIENT_ID", com.android.build.api.variant.BuildConfigField("String", "\"$discordClientId\"", "Discord OAuth2 client id"))

        val apolloGuildId = transcriptionSecrets.getProperty("APOLLO_GUILD_ID", "")
        variant.buildConfigFields?.put("APOLLO_GUILD_ID", com.android.build.api.variant.BuildConfigField("String", "\"$apolloGuildId\"", "Snowflake of the Apollo Discord guild"))

        // Scheme + authority of the stream server. Deliberately absent from the
        // source tree — every playlist URL is built from it — so it rides in the
        // same gitignored file. Empty → the feed resolves nowhere.
        val streamHost = transcriptionSecrets.getProperty("STREAM_HOST", "")
        variant.buildConfigFields?.put("STREAM_HOST", com.android.build.api.variant.BuildConfigField("String", "\"$streamHost\"", "Scheme + authority of the stream server"))

        // Raw URL of the extra-videos manifest (a secret gist). Not a credential,
        // but unlisted: it rides in the same gitignored file so the private list
        // stays out of this repository. Empty → the feed is events only.
        val extraVideosUrl = transcriptionSecrets.getProperty("EXTRA_VIDEOS_URL", "")
        variant.buildConfigFields?.put("EXTRA_VIDEOS_URL", com.android.build.api.variant.BuildConfigField("String", "\"$extraVideosUrl\"", "Raw URL of the extra-videos manifest"))
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation("androidx.core:core-ktx:1.19.0")
    debugImplementation(libs.compose.ui.tooling)
}
