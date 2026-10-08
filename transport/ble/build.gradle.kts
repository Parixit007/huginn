plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.huginn.transport.ble"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
