import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// RevenueCat's public Google Play key (goog_…): `revenuecat.apiKey` in local.properties, or the
// REVENUECAT_API_KEY environment variable. Without it, purchases are off; debug builds can still
// get past the paywall so the app stays testable.
val revenueCatApiKey: String = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}.getProperty("revenuecat.apiKey") ?: System.getenv("REVENUECAT_API_KEY") ?: ""

android {
    namespace = "app.langboard"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "app.langboard"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "REVENUECAT_API_KEY", "\"$revenueCatApiKey\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    packaging {
        // llama.cpp picks its CPU backend at runtime by loading libggml-cpu-*.so from
        // nativeLibraryDir, so the libraries must be extracted on install.
        jniLibs {
            useLegacyPackaging = true
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":cedict"))
    implementation(project(":llama"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.revenuecat.purchases)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    testImplementation(libs.junit)
    // Android's org.json is a stub in JVM tests; PromptBook parses prompts.json with it.
    testImplementation(libs.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
tasks.withType<Test>().configureEach {
    // Optional full-file check of the model repack: HYMT_125_GGUF=/path/Hy-MT2-1.8B-1.25Bit.gguf
    System.getenv("HYMT_125_GGUF")?.let { environment("HYMT_125_GGUF", it) }
}
