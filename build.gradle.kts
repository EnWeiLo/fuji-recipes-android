// Top-level build file.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // Not used by this project. It is declared (apply false) only so that the Kotlin Gradle plugin
    // 2.4.x is on the build classpath, which AGP 9's built-in Kotlin then uses. The backdrop library's
    // own sample app does the same.
    alias(libs.plugins.kotlin.multiplatform) apply false
}
