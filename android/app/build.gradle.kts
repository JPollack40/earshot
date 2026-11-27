import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.earshot.app"
    // Compose 1.13 / Coil 3.6 / core 1.19 / lifecycle 2.11 need compileSdk 37.1+.
    // compileSdk only controls which APIs we can call; targetSdk stays 36 (no new runtime behavior).
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.earshot.app"
        minSdk = 26
        targetSdk = 36
        // Release builds from GitHub Actions pass the version from the git tag (v0.8.0 -> "0.8.0", code 800).
        // Builds on the VM stay at 1 / "0.1.0-dev".
        versionCode = System.getenv("EARSHOT_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("EARSHOT_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.1.0-dev"

        // Supabase project for Podcast Index search, from android/local.properties (never committed):
        //   earshot.supabaseUrl=https://<project-ref>.supabase.co
        //   earshot.supabaseKey=sb_publishable_...   (the publishable key, which is meant to ship in apps)
        // Without them the app searches Apple's directory instead.
        val local = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        fun quoted(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        // GitHub Actions has no local.properties, so it passes the same two values as environment variables.
        fun config(prop: String, env: String) =
            local.getProperty(prop)?.takeIf { it.isNotBlank() } ?: System.getenv(env).orEmpty()
        buildConfigField("String", "SUPABASE_URL", quoted(config("earshot.supabaseUrl", "EARSHOT_SUPABASE_URL")))
        buildConfigField("String", "SUPABASE_KEY", quoted(config("earshot.supabaseKey", "EARSHOT_SUPABASE_KEY")))
    }

    // Release signing (phase 8). The key never goes in git (*.jks and keystore.properties are gitignored).
    // GitHub Actions: environment variables from the repo's secrets (see RELEASES.md).
    // On the VM (optional): android/keystore.properties with storeFile, storePassword, keyAlias, keyPassword.
    // Without either, release builds come out unsigned and debug builds are unaffected.
    val keystore = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    fun signing(env: String, prop: String): String? =
        System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystore.getProperty(prop)?.takeIf { it.isNotBlank() }
    val releaseStore = signing("EARSHOT_KEYSTORE_FILE", "storeFile")
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = rootProject.file(releaseStore)
                storePassword = signing("EARSHOT_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signing("EARSHOT_KEY_ALIAS", "keyAlias")
                keyPassword = signing("EARSHOT_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // Shrinking stays off: R8 rules for Media3/Room/Coil would need their own testing pass.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // Plain JVM unit tests (audio processing): Android framework calls return defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.reorderable)

    testImplementation(libs.junit)
}
