plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Which ABIs cargo-ndk builds: `-Pabis=x86_64` for the emulator; the release is arm64 tablets.
val abis = (findProperty("abis") as String? ?: "arm64-v8a,x86_64").split(',').map { it.trim() }.filter { it.isNotEmpty() }
val repo = rootDir.resolve("../..").canonicalFile
val craftI18n = ((findProperty("craftI18nDir") as String?) ?: System.getenv("CRAFT_I18N_DIR"))?.let(::file) ?: repo.resolve("../craft-i18n")
// Metric-compatible substitutes for Word's fonts (Carlito for Calibri, …) keep pagination like Word.
val craftFonts = ((findProperty("craftFontsDir") as String?) ?: System.getenv("CRAFT_FONTS_DIR"))?.let(::file) ?: repo.resolve("../craft-fonts")

android {
    namespace = "ai.storyteller.wordcraft"
    compileSdk = 35
    // The installed NDK: with it AGP strips symbols from the ~93 MB-per-ABI engine library.
    ndkVersion = "30.0.16248370"
    defaultConfig {
        applicationId = "ai.storyteller.wordcraft"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += abis }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

val cargoBuild by tasks.registering(Exec::class) {
    description = "Builds libwordcraft_android.so for ${abis.joinToString()} with cargo-ndk"
    workingDir = repo
    val args = mutableListOf("cargo", "ndk", "--platform", "26", "-o", projectDir.resolve("src/main/jniLibs").path)
    abis.forEach { args += listOf("-t", it) }
    args += listOf("build", "--release", "-p", "wordcraft-android")
    commandLine(args)
    environment("ANDROID_HOME", android.sdkDirectory.path)
    environment("CARGO_TARGET_DIR", System.getenv("CARGO_TARGET_DIR") ?: repo.resolve("target-android").path)
    environment("CRAFT_PROOF_EMBED", System.getenv("CRAFT_PROOF_EMBED") ?: "en")
    if (craftFonts.isDirectory) environment("CRAFT_FONTS_DIR", craftFonts.path)
    else logger.warn("craft-fonts not found at $craftFonts: Word documents fall back to system fonts with different metrics")
    if (craftI18n.isDirectory) environment("CRAFT_I18N_DIR", craftI18n.path)
    else logger.warn("craft-i18n not found at $craftI18n: English spelling falls back to the built-in word list")
}
tasks.named("preBuild") { dependsOn(cargoBuild) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
