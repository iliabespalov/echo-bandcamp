import java.util.Properties

val properties = Properties()
runCatching { properties.load(rootProject.file("gradle.properties").inputStream()) }

fun property(name: String) = properties.getProperty(name) ?: ""

plugins {
    id("com.android.application") version "8.3.0" apply false
    id("com.android.library") version "8.3.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.23" apply false
}
