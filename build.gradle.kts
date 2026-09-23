import java.util.Properties

plugins {
    // AGP 9 has built-in Kotlin support, plugin org.jetbrains.kotlin.android must not be applied.
    id("com.android.application") version "9.4.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
}

// Every build artifact goes under output, the directory can be deleted at any time.
layout.buildDirectory.set(layout.projectDirectory.dir("output/build"))

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/** Архитектура устройства отладки для debug-сборки; пустое значение - все архитектуры (так собирает CI).
    Release всегда содержит все архитектуры.
*/
val debugAbi: String = (localProps.getProperty("DEBUG_ABI") ?: "arm64-v8a").trim()

// Version is edited manually in version.properties.
val versionProps = Properties().apply {
    providers.fileContents(layout.projectDirectory.file("version.properties"))
        .asText.orNull?.let { load(it.reader()) }
}
val appVersionCode: Int = (versionProps.getProperty("versionCode") ?: "1").toInt()
val appVersionName: String = versionProps.getProperty("versionName") ?: "0.1"
val yandexMapKitVersion = "4.42.0-full"

// Signing key, see DEVELOP.md.
val keystoreFile = rootProject.file("appKey/keystore.properties")
if (!keystoreFile.exists()) {
    throw GradleException("appKey/keystore.properties not found, see DEVELOP.md")
}
val keystoreProps = Properties().apply {
    keystoreFile.inputStream().use { load(it) }
}
val keystorePath = keystoreProps.getProperty("storeFile")
    ?: throw GradleException("storeFile is missing in appKey/keystore.properties, see DEVELOP.md")
if (!rootProject.file(keystorePath).exists()) {
    throw GradleException("key store $keystorePath not found, see DEVELOP.md")
}

android {
    namespace = "com.jm.xtravel"
    compileSdk = 37

    sourceSets["main"].apply {
        manifest.srcFile("res/AndroidManifest.xml")
        kotlin.directories.apply { clear(); add("src") }
        java.directories.apply { clear(); add("src") }
        res.directories.apply { clear(); addAll(listOf("res/app", "res/ext")) }
        assets.directories.clear()
        jniLibs.directories.clear()
    }

    defaultConfig {
        applicationId = "com.jm.xtravel"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "YANDEX_MAPKIT_VERSION", "\"$yandexMapKitVersion\"")
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(keystorePath)
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        // Debug package carries native libraries for the debug device only, see debugAbi.
        // The same signing key as release, so debug and release packages replace each other.
        debug {
            if (debugAbi.isNotEmpty()) {
                ndk {
                    abiFilters.clear()
                    abiFilters.add(debugAbi)
                }
            }
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.compose.runtime:runtime:1.12.1")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.appcompat:appcompat:1.7.1")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("com.yandex.android:maps.mobile:$yandexMapKitVersion")
}
