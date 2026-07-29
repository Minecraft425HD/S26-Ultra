plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "de.neon.memory"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

/**
 * Room schreibt das Schema als JSON heraus.
 *
 * Nicht Zierrat: Eine handgeschriebene Migration muss auf die Spalte genau dem entsprechen,
 * was Room erwartet — sonst verweigert Room beim nächsten Start das Öffnen mit
 * "Migration didn't properly handle". Mit der exportierten Datei lässt sich das vor der
 * Auslieferung vergleichen statt auf dem Telefon zu erleben.
 */
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":core:router"))
    api(project(":core:attachments"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // api, weil NeonDatabase von RoomDatabase erbt und im App-Modul benutzt wird.
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
