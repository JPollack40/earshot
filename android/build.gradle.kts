// Top-level build file. AGP 9 has Kotlin built in, so there is no kotlin-android plugin here.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
