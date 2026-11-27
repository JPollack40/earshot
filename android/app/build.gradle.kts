import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/** Phase 12 scaffold switch, see the end of the android block. */
val carApp = providers.gradleProperty("earshot.carApp").orNull == "true"

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

        // Firebase Cloud Messaging (phase 10): new-episode notifications. Download google-services.json
        // from the Firebase console into android/app/ (gitignored). The Google Services Gradle plugin
        // isn't used (it fails the build when the file is missing); the four values are read here and
        // the app starts Firebase itself. Without the file these are blank and notifications stay off.
        val firebase = firebaseConfig(file("google-services.json"), "com.earshot.app")
        buildConfigField("String", "FIREBASE_PROJECT_ID", quoted(firebase["projectId"].orEmpty()))
        buildConfigField("String", "FIREBASE_APP_ID", quoted(firebase["appId"].orEmpty()))
        buildConfigField("String", "FIREBASE_API_KEY", quoted(firebase["apiKey"].orEmpty()))
        buildConfigField("String", "FIREBASE_SENDER_ID", quoted(firebase["senderId"].orEmpty()))
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

    // Phase 12 scaffold (custom car screens, Car App Library templated media app). OFF unless the build
    // is run with -Pearshot.carApp=true, so normal builds, Android Studio and CI are unchanged. Then:
    //   standard  = the app as today (no car app)
    //   twoIcons  = the same app (same id, so it updates the installed one) plus the templated car app:
    //               the Desktop Head Unit test of whether one app shows two icons in Android Auto
    //   lab       = "Earshot Lab", a separate app (com.earshot.app.lab) beside the normal one, with the
    //               templated car app; its library arrives through sync after signing in
    // Code and manifest for the car app: src/carapp/ (shared by twoIcons and lab).
    if (carApp) {
        flavorDimensions += "edition"
        productFlavors {
            create("standard") { dimension = "edition" }
            create("twoIcons") { dimension = "edition" }
            create("lab") {
                dimension = "edition"
                applicationIdSuffix = ".lab"
                versionNameSuffix = "-lab"
                // Its name comes from src/lab/res/values/strings.xml.
            }
        }
        sourceSets {
            for (flavor in listOf("twoIcons", "lab")) {
                getByName(flavor) {
                    java.srcDir("src/carapp/java")
                    res.srcDir("src/carapp/res")
                    manifest.srcFile("src/carapp/AndroidManifest.xml")
                }
            }
        }
    }
}

dependencies {
    // Phase 12 scaffold only (see "carApp" above): Car App Library (stable 1.7.0; templated media is
    // Beta and needs Car API level 8) and the compat media classes for the session token hand-over.
    if (carApp) {
        for (flavor in listOf("twoIcons", "lab")) {
            add("${flavor}Implementation", "androidx.car.app:app:1.7.0")
            add("${flavor}Implementation", "androidx.car.app:app-projected:1.7.0")
            add("${flavor}Implementation", "androidx.media:media:1.7.1")
        }
    }

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

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)
}

/** The four values the app needs from google-services.json, for [packageName]; empty if the file is missing. */
@Suppress("UNCHECKED_CAST")
fun firebaseConfig(file: File, packageName: String): Map<String, String> {
    if (!file.exists()) return emptyMap()
    val root = groovy.json.JsonSlurper().parse(file) as Map<String, Any?>
    val project = root["project_info"] as? Map<String, Any?> ?: return emptyMap()
    val clients = root["client"] as? List<Map<String, Any?>> ?: return emptyMap()
    val client = clients.firstOrNull {
        val info = it["client_info"] as? Map<String, Any?>
        (info?.get("android_client_info") as? Map<String, Any?>)?.get("package_name") == packageName
    } ?: return emptyMap()
    val info = client["client_info"] as Map<String, Any?>
    val keys = client["api_key"] as? List<Map<String, Any?>>
    return mapOf(
        "projectId" to project["project_id"].toString(),
        "senderId" to project["project_number"].toString(),
        "appId" to info["mobilesdk_app_id"].toString(),
        "apiKey" to (keys?.firstOrNull()?.get("current_key")?.toString() ?: ""),
    )
}
