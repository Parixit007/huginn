plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.huginn.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:mesh"))
    api(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher.android)
    api(libs.kotlinx.coroutines.android)
    // Supplies the Tink classes :core:crypto compiles against, in their Android form.
    implementation(libs.tink.android)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(project(":transport:fake"))
}
