import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Reines Kotlin/JVM, aus demselben Grund wie beim Router: Zerlegen, Erkennen,
// ZIP-Auspacken und Bewerten sind die Stellen mit echter Logik. Ohne Android-Abhängigkeit
// laufen sie in gewöhnlichen Unit-Tests — die Android-Seite beschränkt sich darauf, Ströme
// hereinzureichen.
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
    api(project(":core:router"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
