// Standalone rescue helper. Plain Activity and native views: no Hilt, no Compose, no main-app modules.
// CLAIM(helper-isolated): depends only on the shared portable-settings library and the shared Android validator,
// so it can be installed beside the old and the new Farhan [research/settings-rescue-green-report.md].
plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "ly.com.tahaben.farhan_rescue"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "ly.com.tahaben.farhan_rescue"
        minSdk = 30
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // :settings_android is compiled with core-library desugaring; the main app enables it too.
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes.add("META-INF/AL2.0")
        resources.excludes.add("META-INF/LGPL2.1")
        resources.excludes.add("META-INF/licenses/ASM")
        resources.excludes.add("META-INF/LICENSE.md")
        resources.excludes.add("META-INF/LICENSE-notice.md")
        resources.excludes.add("**/attach_hotspot_windows.dll")
    }
    dependenciesInfo {
        includeInApk = false
    }
}

dependencies {
    implementation(project(":settings_portability"))
    implementation(project(":settings_android"))
    // settings_android declares :core as `implementation`; WidgetProfile's ThemeColors needs it on this compile classpath.
    implementation(project(":core"))

    implementation(libs.androidx.datastore)
    implementation(libs.coroutines.core)
    implementation("androidx.annotation:annotation:1.9.1")

    // Pinned ADB client (dual GPL-3.0-or-later / Apache-2.0; its spake2-android dependency is LGPL-3.0). JitPack.
    // The pom lists its own dependencies as runtime scope, so nothing is re-exported to this compile classpath.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    // Bundled TLS 1.3 provider the client prefers over hidden platform APIs (Apache-2.0).
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    // Public X.509 builder for the throwaway client certificate; same 1.81 family as the client's bcprov.
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")

    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)

    testImplementation("junit:junit:${libs.versions.junit.asProvider().get()}")
    testImplementation(libs.truth)

    androidTestImplementation("junit:junit:${libs.versions.junit.asProvider().get()}")
    androidTestImplementation("androidx.test.ext:junit:${libs.versions.junit.android.ext.get()}")
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.truth)
}
