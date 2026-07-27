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

val localSentryPropertiesFile = rootProject.projectDir.resolve("../../tooling/private/sentry.properties")
val localSentryProperties = Properties().apply {
    if (localSentryPropertiesFile.isFile) {
        localSentryPropertiesFile.inputStream().use { stream -> load(stream) }
    }
}

fun sentryDsn(propertyName: String, environmentName: String): String =
    providers.gradleProperty(propertyName).orNull
        ?: providers.environmentVariable(environmentName).orNull
        ?: localSentryProperties.getProperty(propertyName)
        ?: ""

fun asBuildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val coreSentryDsn = sentryDsn("sentryCoreDsn", "SENTRY_CORE_DSN")

android {
    namespace = "io.motohub.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.motohub.android"
        minSdk = 34
        targetSdk = 36
        versionCode = 96
        versionName = "1.1.2"
        buildConfigField("boolean", "IS_PRO", "false")
        buildConfigField("String", "SENTRY_DSN", asBuildConfigString(coreSentryDsn))
        manifestPlaceholders["appLabel"] = "MOTO-HUB"
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
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(rootProject.projectDir.resolve("../../artifacts"))
    rename { "MOTO-HUB-${android.defaultConfig.versionName}-${android.defaultConfig.versionCode}-public.apk" }
}

val exportPrivateAndroidAutoApk by tasks.registering(Copy::class) {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
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
    // hudlib.aar is GPL-3.0 (the EasyConn/T-Box transport) and is required by CORE.
    implementation(files("libs/hudlib.aar"))
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
    implementation(libs.zxing.core)
    implementation(libs.protobuf.java)
    implementation(libs.conscrypt.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.maplibre.android)
    implementation(libs.okhttp)
    implementation(libs.sentry.android)

    testImplementation(libs.junit)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
