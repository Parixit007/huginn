plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "app.raven.spike.ble"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.raven.spike.ble"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "spike"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
