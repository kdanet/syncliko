// Debug-only helper app for emulator tests: behaves like a third-party app the user copies in.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.syncliko.testcopier"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.syncliko.testcopier"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
