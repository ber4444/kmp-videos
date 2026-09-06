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
        versionCode = 7016
        versionName = "8.1.4"
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

        // Discord OAuth client id for the landing screen's Apollo gate. Public by
        // design, but kept in the same gitignored file so a fork configures its
        // own Discord app. The guild snowflake lives in :server, which is what
        // actually checks membership.
        val discordClientId = transcriptionSecrets.getProperty("DISCORD_CLIENT_ID", "")
        variant.buildConfigFields?.put("DISCORD_CLIENT_ID", com.android.build.api.variant.BuildConfigField("String", "\"$discordClientId\"", "Discord OAuth2 client id"))


        // No STREAM_HOST or EXTRA_VIDEOS_URL field. Keeping them out of
        // secrets.properties kept them out of this repository; it never kept them
        // out of the APK, where a BuildConfig string is a readable constant in the
        // dex. :server now issues both per account, so a build carries neither and
        // a non-member is never told where the streams are. See FeedConfig.
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
