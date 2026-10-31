// AGP 9 ships built-in Kotlin support, so `org.jetbrains.kotlin.android` is never *applied*; it is
// declared with `apply false` only so the Kotlin Gradle plugin resolves to the catalog version
// (matching the Compose compiler plugin) instead of AGP's bundled default.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
