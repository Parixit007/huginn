plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:crypto"))
    api(project(":core:transport"))
    testImplementation(project(":transport:fake"))
    testImplementation(libs.tink)
}
