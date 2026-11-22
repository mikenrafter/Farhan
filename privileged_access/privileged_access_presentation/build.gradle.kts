plugins {
    id("com.android.library")
}
android {
    namespace = "ly.com.tahaben.privileged_access_presentation"
}
apply {
    from("$rootDir/compose-module.gradle")
}

// A ViewModel the screens call; the screens themselves are a later task.
dependencies {
    "implementation"(project(Modules.privilegedAccessDomain))
    "implementation"(libs.coroutines.core)
}
