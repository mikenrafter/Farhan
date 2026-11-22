Farhan privileged access, Self-ADB route: third-party notices

spake2-android 2.2.1 (Muntashir Al-Islam)
  https://github.com/MuntashirAkon/spake2-java
  LGPL-3.0 (https://www.gnu.org/licenses/lgpl-3.0.html). Linked as a separate library.

Conscrypt 2.5.3 (conscrypt-android): Apache-2.0
  https://github.com/google/conscrypt

Bouncy Castle 1.81 (bcprov, bcpkix, bcutil): Bouncy Castle Licence (MIT-style)
  https://www.bouncycastle.org/licence.html

The wire client (AdbWire.kt, ConscryptTls.kt) is adapted from Farhan's own settings rescue helper. The pairing client
(AdbPairingClient.kt, PairingCrypto.kt, AndroidPubkey.kt) is Farhan's own code, written from AOSP's public
pairing protocol; it uses spake2-android for the SPAKE2 step and no code from libadb-android.
