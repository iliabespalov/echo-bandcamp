import java.util.Properties

val properties = Properties()
runCatching { properties.load(rootProject.file("gradle.properties").inputStream()) }

fun property(name: String) = properties.getProperty(name) ?: ""

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.brahmkshatriya.echo.extension.bandcamp"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.brahmkshatriya.echo.extension.bandcamp"
        minSdk = 24
        targetSdk = 34
        versionCode = property("extVersionCode").toIntOrNull() ?: 1
        versionName = property("extVersion")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":ext"))
}
