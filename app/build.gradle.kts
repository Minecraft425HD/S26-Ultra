plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "de.neon.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.neon.app"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-m1"

        ndk {
            // Nur 64-Bit ARM. Ohne diese Einschränkung packt ONNX Runtime seine nativen
            // Bibliotheken für vier Architekturen ein und die APK wird rund viermal so
            // groß — für ein Gerät, das ohnehin nur eine davon ausführen kann.
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":service"))
    implementation(project(":core:router"))
    implementation(project(":core:audio"))
    implementation(project(":core:speech"))
    implementation(project(":core:inference"))
    implementation(project(":core:memory"))
    implementation(project(":core:tools"))
    implementation(project(":core:platform"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
