plugins {
    id("com.android.library")
}
android {
    namespace = "ly.com.tahaben.privileged_access_domain"
}
apply {
    from("$rootDir/base-module.gradle")
}

// Pure Kotlin logic and ports: no Android classes, so every test runs on the JVM.
dependencies {
    "implementation"(libs.coroutines.core)
}
