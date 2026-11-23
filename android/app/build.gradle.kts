import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// CI passes -PappVersion=0.2.1 -PappVersionCode=42; local builds get the defaults.
val appVersion = providers.gradleProperty("appVersion").getOrElse("0.2.0")
val appVersionCode = providers.gradleProperty("appVersionCode").getOrElse("1").toInt()

// Release signing comes from the environment only, never from a file in the repository.
val keystorePath: String? = System.getenv("IAP2LOC_KEYSTORE")?.takeIf { it.isNotBlank() && file(it).isFile }

android {
    namespace = "io.github.d1ngtalk.iap2loc"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.d1ngtalk.iap2loc"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("IAP2LOC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("IAP2LOC_KEY_ALIAS")
                keyPassword = System.getenv("IAP2LOC_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a release key the APK is signed with the debug key so it still installs.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkReleaseBuilds = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
}
