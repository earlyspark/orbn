import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Oura OAuth credentials are NEVER hardcoded. They live in local.properties (gitignored)
// and are surfaced as BuildConfig fields at build time. Missing values resolve to empty
// strings; the app guards against that at runtime (see OuraConfig) rather than shipping
// a default credential.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secretProp(key: String): String = localProps.getProperty(key) ?: ""

android {
    namespace = "com.earlyspark.orbn"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.earlyspark.orbn"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Injected from local.properties — see note above. Quoted because buildConfigField
        // emits raw Kotlin source.
        buildConfigField("String", "OURA_CLIENT_ID", "\"${secretProp("OURA_CLIENT_ID")}\"")
        buildConfigField("String", "OURA_CLIENT_SECRET", "\"${secretProp("OURA_CLIENT_SECRET")}\"")
        buildConfigField("String", "OURA_REDIRECT_URI", "\"${secretProp("OURA_REDIRECT_URI")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Only build for the MindOne's ABI in debug; add others if portability is needed later.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)

    // Room (local analysis store)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager (background tagging job)
    implementation(libs.androidx.work.runtime.ktx)

    // Media3 / ExoPlayer (playback + media session)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

    // Oura integration (M4): HTTP + JSON + encrypted token storage + OAuth browser tab
    implementation(libs.okhttp)
    debugImplementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.browser)

    // Unit tests (JVM — matching engine is pure Kotlin)
    testImplementation(libs.junit)
}
