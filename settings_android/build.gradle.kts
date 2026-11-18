plugins {
    id("com.android.library")
}
android {
    namespace = "ly.com.tahaben.settings_android"
}
apply { from("$rootDir/base-module.gradle") }
dependencies {
    implementation(project(":settings_portability"))
    implementation(project(":core"))
    implementation(project(":notification_filter:notification_filter_domain"))
    implementation(project(":notification_filter:notification_filter_data"))
}
