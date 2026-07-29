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
        // Für die Versionsnummer im Protokoll. Ohne sie ist bei einem gemeldeten Fehler
        // nicht erkennbar, welcher Stand überhaupt lief.
        buildConfig = true
    }

    /**
     * Ein fester Signierschlüssel — der Punkt, an dem Updates hängen.
     *
     * Ohne ihn nimmt AGP den Debug-Schlüssel, und den erzeugt ein frischer Rechner (etwa
     * ein CI-Läufer) bei jedem Lauf neu. Jede APK hätte dann eine andere Signatur, und
     * Android verweigert die Installation über eine bestehende App mit „App nicht
     * installiert". Man müsste jedes Mal deinstallieren — und dabei das importierte
     * Sprachmodell, die gelernten Beispiele und das Gedächtnis verlieren.
     *
     * Der Schlüssel liegt bewusst offen im Repository und ist **keine Sicherheitsgrenze**:
     * Neon landet in keinem Store, und wer das Repository lesen kann, könnte ohnehin eigene
     * Builds erzeugen. Er sorgt allein dafür, dass sich Fassungen gegenseitig ablösen können.
     * Sollte Neon je verteilt werden, gehört er in ein Secret außerhalb des Repositories.
     */
    signingConfigs {
        create("neon") {
            storeFile = rootProject.file("keystore/neon.jks")
            storePassword = "neonneon"
            keyAlias = "neon"
            keyPassword = "neonneon"
        }
    }

    buildTypes {
        // Beide Varianten mit demselben Schlüssel: So lässt sich ein lokal gebauter
        // Debug-Build gegen eine Fassung aus der CI austauschen, ohne zu deinstallieren.
        debug {
            signingConfig = signingConfigs.getByName("neon")
        }
        release {
            signingConfig = signingConfigs.getByName("neon")
            // R8 bleibt aus. Room und kotlinx.serialization greifen über Reflection zu;
            // das ohne eigene Prüfung einzuschalten wäre genau die Art stiller
            // Kaputtmachung, die erst auf dem Gerät auffällt.
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

    testOptions {
        unitTests {
            // Robolectric braucht die Ressourcen und das Manifest der App, um den Start
            // nachzustellen. Ohne das kann der Starttest die Anwendung nicht erzeugen.
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"

        jniLibs {
            // llama-server ist ein Programm, keine Bibliothek. Es muss beim Installieren
            // ausgepackt werden, damit es aus einem ausführbaren Verzeichnis gestartet
            // werden kann — komprimiert in der APK ließe es sich nicht ausführen.
            useLegacyPackaging = true
        }
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
    implementation(libs.androidx.documentfile)
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
    // Führt Android-Code auf der JVM aus. Damit lässt sich der Anwendungsstart prüfen,
    // ohne ein Gerät — genau die Lücke, durch die ein Startabsturz ausgeliefert wurde.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
