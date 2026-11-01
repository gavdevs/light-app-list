pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            name = "JitPack"
            url = uri("https://jitpack.io")
        }
    }
}

rootProject.name = "light-app-list"

include(":app")

// Consume the Light SDK as an adjacent composite build, matching the other
// tools in this tree. Override with -PappList.sdkPath=/path/to/light-sdk.
val lightSdkPath = providers.gradleProperty("appList.sdkPath").getOrElse("../light-sdk")

includeBuild(lightSdkPath) {
    dependencySubstitution {
        substitute(module("com.thelightphone:ui")).using(project(":sdk:ui"))
        substitute(module("com.thelightphone:client")).using(project(":sdk:client"))
        substitute(module("com.thelightphone:server")).using(project(":sdk:server"))
        substitute(module("com.thelightphone:shared")).using(project(":sdk:shared"))
    }
}
