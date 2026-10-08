import dev.detekt.gradle.Detekt
import dev.detekt.gradle.DetektCreateBaselineTask
import dev.detekt.gradle.extensions.DetektExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    // Declaring the Kotlin plugins here also puts our Kotlin version on the build classpath,
    // which the Android Gradle plugin's built-in Kotlin support then uses.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

val junitBom = libs.junit.bom
val junitJupiter = libs.junit.jupiter
val junitLauncher = libs.junit.platform.launcher
val jazzerJunit = libs.jazzer.junit
val ktlintVersion = libs.versions.ktlint.get()
val detektKotlinVersion = libs.versions.detektKotlin.get()
val detektConfig = files("config/detekt/detekt.yml")

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "dev.detekt")

    extensions.configure<KtlintExtension> {
        version.set(ktlintVersion)
    }

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(detektConfig)
    }

    // Analyse for our Java 17 target, not the JDK that happens to run Gradle.
    tasks.withType<Detekt>().configureEach { jvmTarget.set("17") }
    tasks.withType<DetektCreateBaselineTask>().configureEach { jvmTarget.set("17") }

    // detekt runs its own embedded Kotlin compiler; stop our newer Kotlin version from replacing it.
    configurations.matching { it.name == "detekt" }.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion(detektKotlinVersion)
            }
        }
    }

    // Pure Kotlin/JVM modules: compile for Java 17 so Android (and the Mac test peer) can use them.
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                freeCompilerArgs.add("-Xjdk-release=17")
            }
        }

        // JUnit 6 for tests; Jazzer for fuzz tests. Fuzz tests run their saved inputs on every build;
        // set JAZZER_FUZZ=1 to actually fuzz (see PROGRESS.md).
        dependencies {
            "testImplementation"(platform(junitBom))
            "testImplementation"(junitJupiter)
            "testImplementation"(jazzerJunit)
            "testRuntimeOnly"(junitLauncher)
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}
