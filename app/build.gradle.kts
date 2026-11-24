plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
// Values can be overridden per build with -P<NAME>=... or in ~/.gradle/gradle.properties.
fun prop(name: String, default: String): String = providers.gradleProperty(name).orElse(default).get()

// Only these ABIs are built and packaged. arm64-v8a covers practically every current phone;
// add x86_64 for an emulator with -PwhisperAbis=arm64-v8a,x86_64
val whisperAbis = prop("whisperAbis", "arm64-v8a").split(",").map { it.trim() }.filter { it.isNotEmpty() }

android {
    namespace = "com.saurabhtiwari.voicetranscriber"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.saurabhtiwari.voicetranscriber"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += whisperAbis }
        buildConfigField("String", "SUPABASE_URL", "\"${prop("SUPABASE_URL", "https://nxkooahxizfbqgrsnvjd.supabase.co")}\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${prop("SUPABASE_PUBLISHABLE_KEY", "sb_publishable_koyJPGKQ2uWe_wO_KHBQ0A_od49hFdP")}\"")
        buildConfigField("String", "API_BASE_URL", "\"${prop("API_BASE_URL", "https://whisper-platform-api-m2-preview.vercel.app")}\"")
    }

    // Release signing is configured from the environment (CI secrets); nothing secret is committed.
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Point release builds at the production API with -PRELEASE_API_BASE_URL=https://...
            buildConfigField("String", "API_BASE_URL", "\"${prop("RELEASE_API_BASE_URL", prop("API_BASE_URL", "https://whisper-platform-api-m2-preview.vercel.app"))}\"")
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":whisperlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // org.json is only stubbed in android.jar; unit tests need the real implementation.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
