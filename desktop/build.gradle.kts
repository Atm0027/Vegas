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
    implementation(project(":synccore"))
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.network.tls.certificates)
    implementation(libs.jmdns)
    implementation(libs.jaudiotagger)

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
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// The MSI only replaces an installed Vegas when its version is higher, so a fixed
// packageVersion made every rebuild a silent no-op for msiexec. The build field
// is the commit count: it only grows, stays put between builds of the same commit
// and fits MSI's limit (MAJOR.MINOR <= 255, BUILD <= 65535). Without git it falls
// back to the last hand-written version.
val desktopVersion: String = runCatching {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        workingDir = rootDir
    }.standardOutput.asText.get().trim().toInt()
}.getOrNull()?.let { "1.2.${it.coerceIn(3, 65535)}" } ?: "1.2.2"

compose.desktop {
    application {
        mainClass = "dev.alextorres.vegas.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Vegas"
            // Desktop's own version; the Android version is never touched from here.
            packageVersion = desktopVersion
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
