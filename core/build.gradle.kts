import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "com.simon.harmonichackernews.core"
        compileSdk {
            version = release(37) { minorApiLevel = 1 }
        }
        minSdk = 26

        withHostTest {}

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    iosArm64()
    iosSimulatorArm64()
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(libs.androidx.datastore.preferences.core)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            api(libs.ktor.client.core)
            api(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.serialization.protobuf)
            implementation(libs.ksoup)
            implementation(libs.kuri)
            implementation(libs.kotlinx.collections.immutable)
            api(libs.androidx.sqlite)
            // The bundled driver ships SQLite itself, so every target (including iOS and desktop)
            // executes the same engine and the same FTS availability instead of the OS copy.
            implementation(libs.androidx.sqlite.bundled)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        getByName("desktopMain").dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.litertlm.jvm)
            implementation(libs.jna.platform)
        }
    }
}
