// StringFog has no plugins{} marker artifact (legacy publishing style) - it must be applied
// the old way, via buildscript classpath + apply(plugin = "stringfog") in app/build.gradle.kts.
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.github.megatronking.stringfog:gradle-plugin:5.2.0")
        // The plugin validates `implementation` (below) by loading the class off its OWN
        // classpath at configuration time, not the app module's - it needs this here too,
        // in addition to the app-level `implementation` dependency used at runtime.
        classpath("com.github.megatronking.stringfog:xor:5.0.0")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
}

