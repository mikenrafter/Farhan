// The Self-ADB route: talks to this phone's own wireless-debugging daemon over loopback. It lives apart from the
// rest of the connector because it is the only code that needs the INTERNET permission (a loopback socket is a
// network socket to Android). Dropping this module from :app removes the permission, SPAKE2, Conscrypt and
// Bouncy Castle; the connector then offers Shizuku only.
plugins {
    id("com.android.library")
    alias(libs.plugins.hiltAndroidGradle)
}
android {
    namespace = "ly.com.tahaben.privileged_access_selfadb"
}
apply {
    from("$rootDir/base-module.gradle")
}

dependencies {
    "implementation"(project(Modules.privilegedAccessDomain))
    "implementation"(libs.coroutines.core)

    // SPAKE2 for the pairing code exchange: native BoringSSL code under a JNI wrapper (LGPL-3.0, linked as a separate
    // library). Farhan's own Kotlin speaks the pairing protocol around it. JitPack. The same pin as the settings rescue helper's.
    implementation("com.github.MuntashirAkon.spake2-java:spake2-android:2.2.1")
    // Bundled TLS 1.3 provider the client prefers over hidden platform APIs (Apache-2.0).
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    // Public X.509 builder for the client certificate; same 1.81 family as the client's bcprov.
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
}
