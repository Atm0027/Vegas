// Vegas: sync logic shared by the Android app and the Windows app. Pure Kotlin/JVM, no platform code.
plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.ktor.serialization.json)
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
}
