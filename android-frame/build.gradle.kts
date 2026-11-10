plugins { id("com.android.library"); `maven-publish` }

android {
    namespace = "io.github.vapkmp.frame"
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    defaultConfig { minSdk = 23; consumerProguardFiles("consumer-rules.pro") }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    publishing { singleVariant("release") }
}

afterEvaluate {
    publishing { publications { create<MavenPublication>("release") { from(components["release"]) } } }
}
