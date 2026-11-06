import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Release signing key, kept out of git. See README "Publishing a release".
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.daviddokakis.karoointerval"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.daviddokakis.karoointerval.stride"
        minSdk = 26
        targetSdk = 34
        // Each CI build gets a higher version, so the Companion app installs it as an update
        // (GitHub's run number, above the upstream app's codes; local builds stay at 6).
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = build?.let { 100 + it } ?: 6
        versionName = "0.5.1-stride" + (build?.let { ".$it" } ?: "")
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Your own release key when keystore.properties exists; otherwise the debug key, so
            // the project still builds anywhere (but such APKs can't update a published one).
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":karoo-ext"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)
}
