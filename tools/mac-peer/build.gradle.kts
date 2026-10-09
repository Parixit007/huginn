// The Mac test peer (build plan 5.6, D95, D100): Raven's real engine on the Mac, with the Swift helper in
// radio/ driving the Bluetooth radio. Dev-only, never shipped. Start it with tools/mac-peer/run.sh.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":core:mesh"))
    implementation(libs.tink)
    implementation(libs.zxing.core)
    testImplementation(project(":transport:fake"))
}

application {
    mainClass.set("app.raven.tools.macpeer.MainKt")
    applicationName = "mac-peer"
}
