import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Encrypts string literals in the compiled bytecode (XOR + per-build random key), decrypted at
// runtime by the small `xor` library below - complements R8, which renames identifiers but
// leaves string constants (URLs, log messages, SSIDs, error text) sitting in the clear for
// anyone who unzips the APK and runs `strings` on classes.dex.
apply(plugin = "stringfog")

configure<com.github.megatronking.stringfog.plugin.StringFogExtension> {
    implementation = "com.github.megatronking.stringfog.xor.StringFogImpl"
    fogPackages = arrayOf("io.motohub.android")
}

val localSigningPropertiesFile = rootProject.projectDir.resolve(
    "../../tooling/private/android-auto/release-signing.properties"
)
val localSigningProperties = Properties().apply {
    if (localSigningPropertiesFile.isFile) {
        localSigningPropertiesFile.inputStream().use { stream -> load(stream) }
    }
}
val localSigningStore = localSigningProperties.getProperty("storeFile")?.let { configuredPath ->
    File(configuredPath).let { file ->
        if (file.isAbsolute) {
            file
        } else {
            rootProject.projectDir.resolve("../..").resolve(configuredPath).normalize()
        }
    }
}
val hasLocalReleaseSigning = localSigningStore?.isFile == true &&
    localSigningProperties.getProperty("storePassword") != null &&
    localSigningProperties.getProperty("keyAlias") != null &&
    localSigningProperties.getProperty("keyPassword") != null

android {
    namespace = "io.motohub.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.motohub.android"
        minSdk = 34
        targetSdk = 36
        versionCode = 92
        versionName = "1.1.0-build.92-r1"
    }

    // One codebase, two editions ("identica bit by bit ... con tutte le funzionalità
    // abilitate"): CORE = the open, reduced app (Home/Connessioni/Mirroring/Android Auto/
    // Settings); PRO = the same code with every feature enabled (adds Ride Dashboard,
    // Navigation, Trips). The only difference at runtime is BuildConfig.IS_PRO, which gates
    // the extra tabs/modes — see HubBottomNavigation and ModeSelectionContent.
    flavorDimensions += "edition"
    productFlavors {
        create("core") {
            dimension = "edition"
            applicationId = "io.motohub.android"
            buildConfigField("boolean", "IS_PRO", "false")
            manifestPlaceholders["appLabel"] = "MOTO-HUB"
        }
        create("pro") {
            dimension = "edition"
            applicationId = "io.motohub.android.pro"
            buildConfigField("boolean", "IS_PRO", "true")
            // Internal flavor/package name stays "pro" — only the user-visible label is
            // "Advanced" (rebranding is display-only, see proFeatureUnavailable()).
            manifestPlaceholders["appLabel"] = "MOTO-HUB Advanced"
            proguardFile("proguard-rules-pro-release.pro")
        }
    }

    signingConfigs {
        if (hasLocalReleaseSigning) {
            create("localRelease") {
                storeFile = localSigningStore
                storePassword = localSigningProperties.getProperty("storePassword")
                keyAlias = localSigningProperties.getProperty("keyAlias")
                keyPassword = localSigningProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // The exported "public" APK is this variant, so it ships obfuscated:
            // R8 shrinking + optimization + repackaging (see proguard-rules.pro).
            // AGP silently disables obfuscation on debuggable variants, so this
            // build is marked non-debuggable — which also blocks debugger attach
            // and run-as on shipped APKs. De-obfuscate stack traces with
            // retrace + outputs/mapping/debug/mapping.txt.
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            // proRelease is the distributed closed-source artifact — full R8 shrink +
            // obfuscation. The "pro" flavor layers proguard-rules-pro-release.pro on
            // top (see productFlavors) for extra anti-decompilation hardening not
            // wanted on coreRelease (which nobody currently ships — Core's public
            // export is the "debug" variant above — but keeping it minified too is
            // harmless since Core's source is public anyway).
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("localRelease")
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

val androidAutoIdentityDir = rootProject.projectDir.resolve("../../tooling/private/android-auto")
val androidAutoIdentityOutputDir = layout.buildDirectory.dir("generated/res/android-auto-identity/main")
val translationResourceOutputDir = layout.buildDirectory.dir("generated/res/translations/main")
val includeAndroidAutoIdentity = providers.gradleProperty("includeAndroidAutoIdentity")
    .map(String::toBoolean)
    .orElse(false)

val cleanAndroidAutoIdentity by tasks.registering(Delete::class) {
    delete(androidAutoIdentityOutputDir)
}

val prepareAndroidAutoIdentity by tasks.registering(Copy::class) {
    dependsOn(cleanAndroidAutoIdentity)
    from(androidAutoIdentityDir) {
        include("aa_cert", "aa_identity_data")
    }
    into(androidAutoIdentityOutputDir.map { it.dir("raw") })
    onlyIf {
        includeAndroidAutoIdentity.get() &&
            androidAutoIdentityDir.resolve("aa_cert").isFile &&
            androidAutoIdentityDir.resolve("aa_identity_data").isFile
    }
    doLast {
        val rawDir = androidAutoIdentityOutputDir.get().dir("raw").asFile
        rawDir.mkdirs()
        rawDir.resolve("motohub_android_auto_identity_keep.xml").writeText(
            """
            <resources xmlns:tools="http://schemas.android.com/tools"
                tools:keep="@raw/aa_cert,@raw/aa_identity_data" />
            """.trimIndent()
        )
    }
}

/**
 * Translator-friendly catalogues live in /translations and use explicit tags
 * such as strings-it-IT.xml. Android resource folders use a different syntax,
 * so map the filenames here before resource processing starts.
 */
val syncTranslationResources by tasks.registering {
    val sourceDir = rootProject.projectDir.resolve("../../translations")
    val outputDir = translationResourceOutputDir.get().asFile
    inputs.dir(sourceDir)
    outputs.dir(outputDir)
    doLast {
        outputDir.deleteRecursively()
        val localeDirectories = mapOf(
            "en-US" to "values",
            "it-IT" to "values-it",
            "pt-PT" to "values-pt-rPT",
            "ko-KR" to "values-ko-rKR"
        )
        sourceDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("strings-") && it.name.endsWith(".xml") }
            ?.forEach { catalog ->
                val locale = catalog.name.removePrefix("strings-").removeSuffix(".xml")
                val resourceDirectory = localeDirectories[locale]
                    ?: error("Unknown translation locale in ${catalog.name}")
                outputDir.resolve(resourceDirectory).mkdirs()
                catalog.copyTo(
                    outputDir.resolve(resourceDirectory).resolve("strings.xml"),
                    overwrite = true
                )
            }
    }
}

android.sourceSets.getByName("main").res.srcDir(androidAutoIdentityOutputDir)
android.sourceSets.getByName("main").res.srcDir(translationResourceOutputDir)
tasks.named("preBuild").configure {
    dependsOn(prepareAndroidAutoIdentity)
    dependsOn(syncTranslationResources)
}

val exportPublicApk by tasks.registering(Copy::class) {
    // Core's public export is the "debug" buildType (see the comment on it above) - since
    // product flavors were introduced, AGP no longer writes a flavor-less
    // outputs/apk/debug/app-debug.apk (that path is a stale leftover from before the flavor
    // split and silently went un-regenerated, which meant this task was copying a day-old APK
    // into artifacts/ without any error - always target the real per-flavor output path).
    dependsOn("assembleCoreDebug")
    from(layout.buildDirectory.file("outputs/apk/core/debug/app-core-debug.apk"))
    into(rootProject.projectDir.resolve("../../artifacts"))
    rename { "MOTO-HUB-${android.defaultConfig.versionName}-${android.defaultConfig.versionCode}-public.apk" }
}

val exportPrivateAndroidAutoApk by tasks.registering(Copy::class) {
    // "release" is Pro's distributed closed-source artifact (see the comment on it above) -
    // same stale-path issue as exportPublicApk above: outputs/apk/release/app-release.apk
    // hasn't existed since flavors were introduced, so this previously silently did nothing
    // (NO-SOURCE) instead of producing an APK.
    dependsOn("assembleProRelease")
    from(layout.buildDirectory.file("outputs/apk/pro/release/app-pro-release.apk"))
    into(rootProject.projectDir.resolve("../../artifacts"))
    rename { "MOTO-HUB-${android.defaultConfig.versionName}-${android.defaultConfig.versionCode}-android-auto-private.apk" }
    doFirst {
        check(hasLocalReleaseSigning) {
            "The persistent MOTO-HUB release keystore and release-signing.properties are required."
        }
        check(includeAndroidAutoIdentity.get()) {
            "Private Android Auto APK export requires -PincludeAndroidAutoIdentity=true."
        }
        check(
            androidAutoIdentityDir.resolve("aa_cert").isFile &&
                androidAutoIdentityDir.resolve("aa_identity_data").isFile
        ) {
            "The private Android Auto identity files are required."
        }
    }
}

dependencies {
    implementation("com.github.megatronking.stringfog:xor:5.0.0")
    implementation(project(":ipc-contract"))
    // hudlib.aar is GPL-3.0 (the EasyConn/T-Box transport). CORE-only: PRO must not link it, so
    // PRO can be distributed closed-source. PRO reaches the transport through Core via AIDL.
    "coreImplementation"(files("libs/hudlib.aar"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.barcode.scanning)
    implementation(libs.protobuf.java)
    implementation(libs.conscrypt.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.maplibre.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
