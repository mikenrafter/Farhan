plugins {
    id("com.android.library")
    alias(libs.plugins.hiltAndroidGradle)
}
android {
    namespace = "ly.com.tahaben.privileged_access_data"
    buildFeatures {
        aidl = true
    }
}
apply {
    from("$rootDir/base-module.gradle")
}

dependencies {
    "implementation"(project(Modules.privilegedAccessDomain))
    "implementation"(libs.coroutines.core)

    // Shizuku API 13.1.5, the latest release on Maven Central (2023-09-21). `Shizuku.newProcess` is private from
    // 13.1.0, so commands run through a user service (see ExecUserService).
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
