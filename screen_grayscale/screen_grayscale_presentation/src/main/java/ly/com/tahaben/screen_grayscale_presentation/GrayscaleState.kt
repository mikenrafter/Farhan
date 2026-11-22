package ly.com.tahaben.screen_grayscale_presentation

import ly.com.tahaben.privileged_access_domain.AccessResult

data class GrayscaleState(
    val isServiceEnabled: Boolean = false,
    val isDeviceRooted: Boolean = false,
    val isSecureSettingsPermissionGranted: Boolean = false,
    val isAccessibilityPermissionGranted: Boolean = false,
    val isLoading: Boolean = false,
    /** The last grant through the privileged-access connector (Self-ADB or Shizuku), until dismissed. */
    val secureSettingsGrant: AccessResult? = null,
)
