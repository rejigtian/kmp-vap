plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    `maven-publish`
}
group = "io.github.vapkmp"
version = "0.1.0-SNAPSHOT"
kotlin {
    explicitApi()
    ohosArm64()
    sourceSets {
        commonMain {
            kotlin.srcDir("../src/commonMain/kotlin")
            dependencies {
                api(compose.runtime)
                api(compose.ui)
                implementation(compose.foundation)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2-1.0.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.1-1.0.0")
            }
        }
        getByName("ohosArm64Main") {
            kotlin.srcDir("../src/ohosMain/kotlin")
            dependencies { implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.9.4-1.1.0") }
        }
    }
}
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.skiko" && requested.name == "skiko-ohosarm64")
            useTarget("org.jetbrains.skiko:skiko-ohosarm64-api:${requested.version}")
    }
}
