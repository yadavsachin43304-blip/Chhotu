buildscript {
    val objectboxVersion by extra("5.3.0")
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("io.objectbox:objectbox-gradle-plugin:$objectboxVersion")
    }
}

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}

tasks.register("assembleDebugClone") {
    dependsOn(":app:assembleClone")
    description = "Build the clone (co-installable) debug APK with package name suffix .clone"
}

// Chhotu Master: native ABI selection for every module (app + llama/mnn/quickjs/terminal/avatars).
// Release build: arm64-v8a (default). Emulator test build: -PchhotuAbis=x86_64, so llama.cpp / MNN /
// app native code run natively on the x86_64 emulator instead of through ARM translation.
// Prebuilt arm64-only libraries (ffmpeg-kit, ripgrep, sherpa-mnn, terminal binaries) are simply not
// packaged in the x86_64 test APK; they are only loaded lazily by the features that need them.
val chhotuAbis: List<String> =
    ((findProperty("chhotuAbis") as String?) ?: "arm64-v8a")
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

subprojects {
    afterEvaluate {
        val androidExt = extensions.findByName("android") as? com.android.build.gradle.BaseExtension
        if (androidExt != null) {
            val filters = androidExt.defaultConfig.ndk.abiFilters
            if (filters.isNotEmpty() || project.name == "app") {
                filters.clear()
                filters.addAll(chhotuAbis)
            }
            // x86_64 test build: native ELF TLS (-fno-emulated-tls) needs __tls_get_addr, which bionic
            // only exports from API 29 on x86_64. The emulator is API 34, so raise minSdk for that build only.
            if (chhotuAbis.contains("x86_64")) {
                val cur = androidExt.defaultConfig.minSdkVersion?.apiLevel ?: 0
                if (cur < 29) androidExt.defaultConfig.minSdkVersion(29)
            }
        }
    }
}
