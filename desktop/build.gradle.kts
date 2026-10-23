import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Vegas for Windows. Compiles the shared innertube sources directly so that module stays untouched.
plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.ksp)
}

kotlin {
    jvmToolchain(21)
    sourceSets.main {
        kotlin.srcDir("../innertube/src/main/kotlin")
    }
}

dependencies {
    implementation(compose.desktop.windows_x64)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.coroutines.swing)
    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)
    implementation(libs.materialKolor)
    implementation(libs.compose.reorderable)
    implementation(libs.jna.platform)

    implementation(libs.innertubex)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.encoding)

    // FFmpeg decoding; Windows natives only.
    implementation(libs.javacv) { isTransitive = false }
    implementation(libs.ffmpeg)
    implementation(libs.javacpp)
    implementation(variantOf(libs.ffmpeg) { classifier("windows-x86_64") })
    implementation(variantOf(libs.javacpp) { classifier("windows-x86_64") })

    implementation(libs.room.runtime)
    implementation(libs.sqlite.bundled)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

compose.desktop {
    application {
        mainClass = "dev.alextorres.vegas.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Vegas"
            // Desktop's own version; the Android version is never touched from here.
            packageVersion = "1.0.0"
            vendor = "Alex"
            description = "Vegas para Windows"
            // suggestRuntimeModules, plus TLS elliptic curves and JNDI that OkHttp may need at runtime.
            modules("java.instrument", "java.management", "jdk.unsupported", "jdk.crypto.ec", "java.naming", "java.sql")
            windows {
                iconFile.set(project.file("src/main/resources/vegas.ico"))
                menuGroup = "Vegas"
                perUserInstall = true
                menu = true
                shortcut = true
                upgradeUuid = "c0c67a02-32a9-4316-8a14-77a0e93030d4"
            }
        }
    }
}
