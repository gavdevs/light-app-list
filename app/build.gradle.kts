plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.light.sdk)
}

val lightSdkPath = providers.gradleProperty("appList.sdkPath").getOrElse("../light-sdk")

fun requiredReleaseEnvironment(name: String): String =
    providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
        ?: error("$name must be set when APP_LIST_RELEASE_KEYSTORE is configured")

val releaseKeystorePath = providers.environmentVariable("APP_LIST_RELEASE_KEYSTORE").orNull

android {
    compileSdk = 36

    signingConfigs {
        create("lightsdkDev") {
            storeFile = rootProject.file("$lightSdkPath/sdk/keys/lightsdk-dev.jks")
            storePassword = "android"
            keyAlias = "lightsdk-dev"
            keyPassword = "android"
            enableV3Signing = true
            enableV4Signing = true
        }

        if (!releaseKeystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = requiredReleaseEnvironment("APP_LIST_RELEASE_STORE_PASSWORD")
                keyAlias = requiredReleaseEnvironment("APP_LIST_RELEASE_KEY_ALIAS")
                keyPassword = requiredReleaseEnvironment("APP_LIST_RELEASE_KEY_PASSWORD")
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    defaultConfig {
        minSdk = 34
        targetSdk = 36

        manifestPlaceholders["sdkVersion"] = property("sdkVersion") as String
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = if (releaseKeystorePath.isNullOrBlank()) {
                signingConfigs.getByName("lightsdkDev")
            } else {
                signingConfigs.getByName("release")
            }
        }
    }

    lint {
        warningsAsErrors = false
        error += "RestrictedApi"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.sdk.client)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
