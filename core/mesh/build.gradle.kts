plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:crypto"))
    testImplementation(libs.tink)
}
