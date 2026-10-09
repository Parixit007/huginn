import com.android.build.api.artifact.SingleArtifact
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.huginn.app"
    compileSdk = 37

    defaultConfig {
        // Placeholder until the first public release (spec D33).
        applicationId = "app.huginn.mesh"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(project(":data"))
    implementation(project(":core:mesh"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.emoji.picker)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core) // newer than Compose's own, see D91
    androidTestImplementation(libs.junit4)
    androidTestImplementation(project(":transport:fake"))
    debugImplementation(libs.compose.ui.test.manifest)
}

/**
 * Huginn never talks to the internet (spec D4). This fails the build if the INTERNET permission
 * shows up in a release manifest, including when a library adds it through manifest merging.
 */
abstract class VerifyNoForbiddenPermissions : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val forbidden = setOf("android.permission.INTERNET")
        val document =
            DocumentBuilderFactory
                .newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(mergedManifest.get().asFile)
        val androidNs = "http://schemas.android.com/apk/res/android"
        val requested =
            listOf("uses-permission", "uses-permission-sdk-23").flatMap { tag ->
                val nodes = document.getElementsByTagName(tag)
                (0 until nodes.length).map {
                    nodes
                        .item(it)
                        .attributes
                        .getNamedItemNS(androidNs, "name")
                        ?.nodeValue
                }
            }
        val found = requested.filterNotNull().filter { it in forbidden }
        check(found.isEmpty()) {
            "Forbidden permission(s) in the merged release manifest: $found. Huginn must never use the internet."
        }
        report.get().asFile.writeText("Requested permissions: ${requested.filterNotNull().sorted()}\n")
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val verify =
            tasks.register<VerifyNoForbiddenPermissions>("verify${name}NoInternet") {
                mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                report.set(layout.buildDirectory.file("reports/permissions/${variant.name}.txt"))
            }
        tasks
            .matching { it.name == "assemble$name" || it.name == "bundle$name" || it.name == "check" }
            .configureEach { dependsOn(verify) }
    }
}
