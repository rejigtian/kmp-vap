plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    `maven-publish`
}

kotlin {
    explicitApi()
    android {
        namespace = "io.github.vapkmp"
        compileSdk = 37
        minSdk = 23
        withHostTestBuilder {}.configure { isIncludeAndroidResources = false }
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.framework { baseName = "VapKmp" }
    }
    sourceSets {
        commonMain.dependencies {
            api(compose.runtime)
            api(compose.ui)
            implementation(compose.foundation)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
        }
        androidMain.dependencies {
            implementation(project(":android-frame"))
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        getByName("androidHostTest").dependencies { implementation(kotlin("test-junit")) }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("VAP KMP")
            description.set("Self-contained VAP and VAPX playback for Compose Multiplatform")
            licenses { license { name.set("MIT"); url.set("https://opensource.org/license/mit") } }
        }
    }
}
