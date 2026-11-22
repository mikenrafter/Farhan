package ly.com.tahaben.privileged_access_domain

import kotlinx.coroutines.CompletableDeferred

class FakePhone : PhoneFacts {
    override var sdk = 35
    var op = AppOpMode.NOT_ALLOWED
    var held = false
    var writeWorks: Boolean? = true
    override fun sensitiveNotificationsOp() = op
    override fun secureSettingsPermissionHeld() = held
    override fun secureSettingsWriteWorks() = writeWorks

    /** What a clean run of [command] does to the phone. */
    fun apply(command: PrivilegedCommand) {
        when (command) {
            PrivilegedCommand.GRANT_SENSITIVE_NOTIFICATIONS -> op = AppOpMode.ALLOWED
            PrivilegedCommand.REVOKE_SENSITIVE_NOTIFICATIONS -> op = AppOpMode.NOT_ALLOWED
            PrivilegedCommand.GRANT_SECURE_SETTINGS -> held = true
            PrivilegedCommand.REVOKE_SECURE_SETTINGS -> held = false
        }
    }
}

class FakePreferences(override var route: RoutePreference = RoutePreference.DEFAULT, override var linkDropPending: Boolean = false) : PrivilegedPreferences

/** Records order across fakes so a test can say "the grayscale reset ran before the revoke". */
class Journal { val events = mutableListOf<String>() }

abstract class FakeShell(override val route: Route, protected val phone: FakePhone, protected val journal: Journal) : PrivilegedShell {
    val ran = mutableListOf<ShellCommand>()
    var result: ShellResult? = null          // forced outcome; null means "run cleanly and change the phone"
    var gate: CompletableDeferred<Unit>? = null
    val entered = CompletableDeferred<Unit>()
    var applyEffect = true

    protected fun commandOf(shell: ShellCommand): PrivilegedCommand =
        PrivilegedCommand.entries.first { it.shellCommand(APP) == shell }

    override suspend fun run(command: ShellCommand): ShellResult {
        ran += command
        journal.events += "run ${command.args[0]} ${command.args[1]}"
        entered.complete(Unit)
        gate?.await()
        result?.let { return it }
        if (applyEffect) phone.apply(commandOf(command))
        return ShellResult.Ran(0, "")
    }

    companion object { val APP = AppId.parse("ly.com.tahaben.farhan_alpha")!! }
}

class FakeSelfAdb(phone: FakePhone, journal: Journal) : FakeShell(Route.SELF_ADB, phone, journal), SelfAdbRoute {
    var hasKey = false
    var wirelessOff = false
    var pairResult: PairingResult = PairingResult.Paired
    var pairCalls = 0
    var forgetCalls = 0
    var lastCode: PairingCode? = null
    var lastPairingPort: Int? = null

    override suspend fun readiness(): RouteReadiness = when {
        !hasKey -> RouteReadiness.Blocked(RouteBlocker.NOT_PAIRED)
        wirelessOff -> RouteReadiness.Blocked(RouteBlocker.WIRELESS_DEBUGGING_OFF)
        else -> RouteReadiness.Ready
    }

    override suspend fun pair(code: PairingCode, pairingPort: Int?): PairingResult {
        pairCalls++; lastCode = code; lastPairingPort = pairingPort
        if (pairResult == PairingResult.Paired) hasKey = true
        return pairResult
    }

    override fun forgetPairing() { forgetCalls++; hasKey = false }

    var checkLines = listOf("check line")
    var checkPorts = mutableListOf<Int?>()
    var checkGate: CompletableDeferred<Unit>? = null
    val checkEntered = CompletableDeferred<Unit>()
    var logText = "log text"
    override suspend fun checkConnection(typedPort: Int?): List<String> {
        checkPorts += typedPort
        checkEntered.complete(Unit)
        checkGate?.await()
        return checkLines
    }
    override fun connectionLogText() = logText
}

class FakeShizuku(phone: FakePhone, journal: Journal) : FakeShell(Route.SHIZUKU, phone, journal), ShizukuRoute {
    var readiness: RouteReadiness = RouteReadiness.Blocked(RouteBlocker.SHIZUKU_NOT_INSTALLED)
    var permissionResult: ShizukuPermissionResult = ShizukuPermissionResult.Granted
    override suspend fun readiness() = readiness
    override suspend fun requestPermission(): ShizukuPermissionResult {
        if (permissionResult == ShizukuPermissionResult.Granted) readiness = RouteReadiness.Ready
        return permissionResult
    }
}
