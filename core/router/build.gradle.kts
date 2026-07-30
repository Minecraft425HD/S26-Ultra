import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Java-17-Bytecode, damit die Android-Module dieses Modul einbinden können — gebaut mit
// der JDK, die gerade da ist. Eine feste Toolchain würde je nach Maschine einen Download
// erzwingen.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
    }

    // `PortableRegexTest` durchsucht **alle** Kotlin-Quellen des Projekts nach Konstrukten,
    // an denen Android beim Start scheitert. Gradle kann das nicht wissen: Es hält als
    // Eingabe nur dieses Modul fest, erklärt die Aufgabe für aktuell und holt sie aus dem
    // Build-Cache — der Wächter läuft dann nicht mehr.
    //
    // Genau das ist passiert. Ein `(?U)` in einem Doc-Kommentar in `app` blieb fünf
    // Veröffentlichungen lang unentdeckt, weil sich in `core/router` nichts geändert hatte.
    // Ein Wächter, der nicht läuft, ist keiner — und schlimmer als keiner, weil man sich auf
    // ihn verlässt.
    inputs.files(
        fileTree(rootDir) {
            include("**/src/**/*.kt")
            exclude("**/build/**")
        }
    )
        .withPropertyName("quelltextDesGesamtprojekts")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
