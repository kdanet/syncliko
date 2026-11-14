plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.syncliko.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.syncliko.android"
        minSdk = 31
        targetSdk = 37
        versionCode = 9
        versionName = "0.3.2"
    }

    // Release key, kept outside the repository. scripts/release.sh sets these from the macOS Keychain:
    //   SYNCLIKO_KEYSTORE=~/.config/syncliko/syncliko-release.jks, SYNCLIKO_KEYSTORE_PASSWORD, SYNCLIKO_KEY_ALIAS.
    // Without them a release build falls back to the debug key, so anyone can still build and try the app;
    // such an APK can't update one installed from GitHub Releases.
    val releaseKeystore = System.getenv("SYNCLIKO_KEYSTORE")?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("SYNCLIKO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SYNCLIKO_KEY_ALIAS") ?: "syncliko"
                keyPassword = System.getenv("SYNCLIKO_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release") else {
                logger.warn("SYNCLIKO_KEYSTORE not set: signing the release build with the debug key")
                signingConfigs.getByName("debug")
            }
        }
    }

    // No Google-encrypted dependency metadata in the APK: it's only readable by Google Play, and stores like
    // IzzyOnDroid and tools like Obtainium prefer APKs without it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

dependencies {
    implementation(project(":core:transport"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.play.services.code.scanner)
    implementation(libs.androidx.work.runtime)
    testImplementation(libs.junit)
}
