import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningFile = providers.gradleProperty("pointsHomeSigningProperties").orNull
    ?.let { rootProject.file(it) }
    ?: rootProject.file("../deploy/private/android-signing.properties")
val releaseSigning = Properties().apply {
    if (releaseSigningFile.isFile) releaseSigningFile.inputStream().use { load(it) }
}
val localConfiguration = Properties().apply {
    val file = rootProject.file("local-config.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val releaseApiUrl = providers.gradleProperty("pointsHomeReleaseApiUrl").orNull
    ?: providers.environmentVariable("POINTS_HOME_RELEASE_API_URL").orNull
    ?: localConfiguration.getProperty("releaseApiUrl", "https://api.example.com")
val releaseApiUri = URI(releaseApiUrl)
require(releaseApiUri.scheme == "https" && !releaseApiUri.host.isNullOrBlank()
    && releaseApiUri.userInfo == null && releaseApiUri.rawQuery == null
    && releaseApiUri.rawFragment == null && '\\' !in releaseApiUrl && '"' !in releaseApiUrl) {
    "Release API URL must be an HTTPS address without credentials, query or fragment."
}

android {
    namespace = "com.pointshome.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pointshome.app"
        minSdk = 23
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningFile.isFile) {
            create("internalRelease") {
                storeFile = rootProject.file(requireNotNull(releaseSigning.getProperty("storeFile")))
                storePassword = requireNotNull(releaseSigning.getProperty("storePassword"))
                keyAlias = requireNotNull(releaseSigning.getProperty("keyAlias"))
                keyPassword = requireNotNull(releaseSigning.getProperty("keyPassword"))
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "DEFAULT_API_URL", "\"http://10.0.2.2:8080\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "DEFAULT_API_URL", "\"$releaseApiUrl\"")
            if (releaseSigningFile.isFile) signingConfig = signingConfigs.getByName("internalRelease")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core:1.18.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
