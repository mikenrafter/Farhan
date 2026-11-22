// The Self-ADB route: talks to this phone's own wireless-debugging daemon over loopback. It lives apart from the
// rest of the connector because it is the only code that needs the INTERNET permission (a loopback socket is a
// network socket to Android). Dropping this module from :app removes the permission, libadb, Conscrypt and
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

    // Pinned ADB client, used for pairing only (dual GPL-3.0-or-later / Apache-2.0; its spake2-android dependency is
    // LGPL-3.0). JitPack. The same pins as the settings rescue helper.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    // Bundled TLS 1.3 provider the client prefers over hidden platform APIs (Apache-2.0).
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    // Public X.509 builder for the client certificate; same 1.81 family as the client's bcprov.
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
}
