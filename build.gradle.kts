plugins {
    id("com.android.library") version "9.1.1" apply false
    kotlin("multiplatform") version "2.2.21" apply false
    id("com.android.kotlin.multiplatform.library") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("org.jetbrains.compose") version "1.10.3" apply false
}

allprojects {
    group = "io.github.vapkmp"
    version = "0.1.0-SNAPSHOT"
}
