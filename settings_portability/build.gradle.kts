import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Plain JVM module (no Android SDK) so the model, codec and import journal run in SDK-free unit tests.
// Both the rescue APK and the normal app depend on it. Android adapters (SharedPreferences, DataStore,
// Room, SAF) live in those apps and implement the ports declared here.
plugins {
    alias(libs.plugins.jetbrainsKotlinJvm)
}

tasks.test {
    maxHeapSize = "128m"
    maxParallelForks = 1
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Streaming JSON with STRICT_DUPLICATE_DETECTION and StreamReadConstraints; same behavior on JVM and Android.
    implementation(libs.jackson.core)

    testImplementation("junit:junit:${libs.versions.junit.asProvider().get()}")
    testImplementation(libs.truth)
    // Real SQLite engine for the configuration-rows test (JVM only; never shipped).
    testImplementation(libs.sqlite.jdbc)
}
