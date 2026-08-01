plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "de.neon.inference"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
        ndk {
            // Nur 64-Bit ARM: Das Zielgerät hat nichts anderes, und jede weitere
            // Architektur verdoppelt die Größe der nativen Bibliotheken.
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // api, weil ModelSpec in den öffentlichen Signaturen dieses Moduls vorkommt.
    api(project(":core:router"))
    implementation(project(":core:platform"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // api, weil LlamaServerClient in den öffentlichen Signaturen vorkommt.
    api(libs.okhttp)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Nur zum Testen: Ob die erzeugte Grammatik gültig ist, kann kein Kotlin-Test
    // beantworten — das entscheidet der Parser in llama.cpp. Der Wächter dafür braucht
    // beides, den Erzeuger aus core/tools und den laufenden Server hier. Keine
    // Ringabhängigkeit: core/tools kennt core/inference nicht.
    testImplementation(project(":core:tools"))
}
