pluginManagement {
    repositories {
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
        gradlePluginPortal(); mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
        mavenCentral()
    }
}
rootProject.name = "vap-kmp-ohos"
include(":vap-ohos")
project(":vap-ohos").projectDir = file("../vap/ohos")
