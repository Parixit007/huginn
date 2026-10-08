plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core:model"))
    // Tink is compile-only here: the Android app supplies tink-android at runtime, while tests and
    // JVM tools (the Mac test peer) supply the plain JVM artifact. Both have the same classes.
    compileOnly(libs.tink)
    testImplementation(libs.tink)
}
