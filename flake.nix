{
  description = "Farhan — Android development environment";

  inputs = {
    # Use nixos-unstable to ensure Android SDK 35 packages are available.
    # Run `nix flake update` to pull in newer SDK versions later.
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";

      pkgs = import nixpkgs {
        inherit system;
        config = {
          # Android SDK is distributed under an unfree license.
          allowUnfree = true;
          # Accept the Android SDK license (equivalent to `sdkmanager --licenses`).
          android_sdk.accept_license = true;
        };
      };

      # ── Android SDK composition ───────────────────────────────────────────
      # Versions chosen to match the project configuration:
      #   compileSdk / targetSdk = 35, minSdk = 23
      #   Android Gradle Plugin  = 8.8.2
      #   Kotlin                 = 2.1.10
      #
      # If a version string is not found in your nixpkgs rev, run
      # `nix flake update` to pull a newer nixpkgs, or change the version
      # string to whatever `ls $ANDROID_SDK_ROOT/build-tools/` shows.
      androidComposition = pkgs.androidenv.composeAndroidPackages {
        # cmdline-tools ships sdkmanager, avdmanager, apkanalyzer, etc.
        cmdLineToolsVersion = "11.0";

        # platform-tools ships adb, fastboot, etc.
        platformToolsVersion = "35.0.2";

        # AGP 8.8.2 targets build-tools 35.0.0 for compileSdk 35.
        buildToolsVersions = [ "35.0.0" ];

        # Only the platform we compile against is needed for local builds.
        platformVersions = [ "35" ];

        # Not needed for plain app builds from the command line.
        includeEmulator    = false;
        includeNDK         = false;
        includeSystemImages = false;
        useGoogleAPIs      = false;

        # Explicitly accept the base SDK license so Gradle doesn't complain.
        extraLicenses = [
          "android-sdk-license"
        ];
      };

      androidSdk = androidComposition.androidsdk;

      # AGP 8.x requires JDK 17+; JDK 21 is the current LTS.
      jdk = pkgs.jdk21;

    in {
      # ── packages ─────────────────────────────────────────────────────────
      # Expose the SDK so you can also run `nix build .#androidSdk`.
      packages.${system} = {
        inherit androidSdk;
        default = androidSdk;
      };

      # ── devShell ──────────────────────────────────────────────────────────
      devShells.${system}.default = pkgs.mkShell {
        packages = [
          androidSdk
          jdk
          # unzip is used internally by some Gradle download logic.
          pkgs.unzip
        ];

        # ── Android SDK paths ───────────────────────────────────────────────
        ANDROID_HOME     = "${androidSdk}/libexec/android-sdk";
        ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";

        # ── Java ────────────────────────────────────────────────────────────
        JAVA_HOME = "${jdk.home}";

        # ── Gradle / NixOS compatibility ────────────────────────────────────
        # On NixOS the aapt2 binary that Gradle downloads from Maven uses a
        # hard-coded glibc interpreter path (/lib64/ld-linux-x86-64.so.2)
        # that doesn't exist on a pure NixOS system.  Pointing Gradle at the
        # aapt2 that ships with the Nix-packaged SDK avoids the need for
        # patchelf, FHS containers, or nix-ld.
        GRADLE_OPTS =
          "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdk}/libexec/android-sdk/build-tools/35.0.0/aapt2";

        shellHook = ''
          echo ""
          echo "Farhan — Android devShell"
          echo "  ANDROID_SDK_ROOT = $ANDROID_SDK_ROOT"
          echo "  JAVA_HOME        = $JAVA_HOME"
          echo ""
          # Ensure the Gradle wrapper is executable after a fresh clone.
          [ -f gradlew ] && chmod +x gradlew
          echo "Build: ./gradlew assembleDebug"
          echo ""
        '';
      };
    };
}
