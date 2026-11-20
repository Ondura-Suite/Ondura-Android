import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing: a keystore from the environment (CI secrets / Jenkins
// credentials). Without one, release builds are signed with the debug key so
// they still install – fine for testing, but updates need the same key.
val keystoreFile = System.getenv("ONDURA_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "de.mirranet.ondura.mail"
    compileSdk = 35
    // Same NDK as :core, so the native libraries get stripped when packaged.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "de.mirranet.ondura.mail"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: System.getenv("BUILD_NUMBER") ?: "1").toInt()
        versionName = providers.gradleProperty("ondura.versionName").get()
        // Only the languages Ondura is translated into.
        resourceConfigurations += listOf("en", "de", "fr")
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("ONDURA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ONDURA_KEY_ALIAS") ?: "ondura"
                keyPassword = System.getenv("ONDURA_KEY_PASSWORD") ?: System.getenv("ONDURA_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ui"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.browser)
}
