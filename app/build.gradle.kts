plugins {
    alias(libs.plugins.android.application)
}

fun loadEnvFile(file: File): Map<String, String> {
    if (!file.exists()) {
        return emptyMap()
    }
    return file.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
        .associate {
            val name = it.substringBefore("=").trim()
            val value = it.substringAfter("=").trim().trim('"', '\'')
            name to value
        }
}

fun buildConfigString(value: String): String {
    return "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}

val serverEnv = loadEnvFile(file("server.env"))
val defaultServerUrl = serverEnv["LINKVIEW_SERVER_URL"] ?: "http://qylad-server.duckdns.org/linkview/"

android {
    namespace = "com.aes.linkview"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.aes.linkview"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "LINKVIEW_DEFAULT_SERVER_URL",
            buildConfigString(defaultServerUrl)
        )
        buildConfigField(
            "String",
            "LINKVIEW_DEFAULT_API_TOKEN",
            buildConfigString(serverEnv["LINKVIEW_API_TOKEN"].orEmpty())
        )
        buildConfigField(
            "String",
            "LINKVIEW_DEFAULT_DEVICE_ID",
            buildConfigString(serverEnv["LINKVIEW_DEVICE_ID"] ?: "linkview-device")
        )
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
