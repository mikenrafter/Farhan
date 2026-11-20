package ly.com.tahaben.notification_filter_presentation.settings

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ly.com.tahaben.core.R
import ly.com.tahaben.core_ui.LocalSpacing
import ly.com.tahaben.core_ui.mirror
import ly.com.tahaben.notification_filter_domain.model.AppPriorityInfo
import ly.com.tahaben.notification_filter_domain.model.NotificationCategory
import ly.com.tahaben.notification_filter_domain.sound.SoundControlState
import ly.com.tahaben.notification_filter_domain.sound.SuspensionReason

private enum class Page { SETTINGS, ENABLE, ELECTION }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SoundControlSettingsScreen(viewModel: NotificationSettingsViewModel, onBack: () -> Unit) {
    val sound = viewModel.soundControl
    val state by sound.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf(Page.SETTINGS) }
    var grants by remember { mutableStateOf(sound.grants()) }
    var choices by remember { mutableStateOf(sound.choices()) }
    var apps by remember { mutableStateOf<List<AppPriorityInfo>>(emptyList()) }
    var search by rememberSaveable { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var effectiveFilter by remember { mutableStateOf(sound.effectiveFilter()) }

    fun refresh() {
        scope.launch {
            sound.refresh()
            grants = sound.grants()
            choices = sound.choices()
            effectiveFilter = sound.effectiveFilter()
        }
    }

    fun back() {
        if (page == Page.SETTINGS) onBack() else page = Page.SETTINGS
    }

    fun finishAction(action: suspend () -> Unit) {
        if (working) return
        working = true
        scope.launch {
            try {
                action()
                effectiveFilter = sound.effectiveFilter()
            } finally {
                working = false
            }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refresh()
        scope.launch { apps = withContext(Dispatchers.IO) { viewModel.soundApps() } }
    }
    BackHandler { back() }
    val requestPost = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh()
        if (!granted) openSettings(context, Settings.ACTION_APP_NOTIFICATION_SETTINGS, context.packageName)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(when (page) {
                Page.SETTINGS -> "Sound control"
                Page.ENABLE -> "Turn on sound control"
                Page.ELECTION -> "Which notifications make sound"
            }) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            navigationIcon = {
                IconButton(onClick = ::back) {
                    Icon(Icons.Filled.ArrowBack, stringResource(R.string.back), Modifier.mirror())
                }
            },
        )
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = spacing.spaceMedium),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (page) {
                Page.SETTINGS -> {
                    item {
                        SoundCard {
                            ExperimentRow(
                                "Mute other apps",
                                "Farhan holds its own Do Not Disturb rule while sound control is on.",
                                state is SoundControlState.Held || state == SoundControlState.Creating,
                            ) { enabled ->
                                if (enabled) page = Page.ENABLE else finishAction { sound.off() }
                            }
                        }
                    }
                    item {
                        SoundCard {
                            TextButton(onClick = { page = Page.ELECTION }) { Text("Which notifications make sound") }
                            Text(
                                "Selected apps: ${choices.apps.size} · Categories: ${choices.categories.size}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    item {
                        SoundCard {
                            Text("Sound", style = MaterialTheme.typography.titleMedium)
                            Text("Selected notifications use the Farhan alert channel.")
                            TextButton(onClick = { openSettings(context, Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, context.packageName) }) {
                                Text("Open alert channel settings")
                            }
                            ExperimentRow("Allow system sounds", "Applies next time you turn sound control on.", choices.systemSoundsAllowed) {
                                scope.launch { sound.setSystemSoundsAllowed(it); choices = sound.choices() }
                            }
                        }
                    }
                    item {
                        SoundCard {
                            Text("Calls", style = MaterialTheme.typography.titleMedium)
                            Text("Farhan allows calls from anyone and repeat callers. Your own modes can still restrict calls.")
                        }
                    }
                    item {
                        SoundCard {
                            Text("Status", style = MaterialTheme.typography.titleMedium)
                            Text(describe(state))
                            Text("Effective Android mode: ${describeFilter(effectiveFilter)}", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick = { openSettings(context, "android.settings.ZEN_MODE_SETTINGS") }) {
                                    Text("Open Android Modes")
                                }
                                if (state != SoundControlState.Off) {
                                    TextButton(enabled = !working, onClick = { finishAction { sound.off() } }) { Text("Turn off") }
                                }
                            }
                        }
                    }
                }
                Page.ENABLE -> {
                    item { Text("Farhan needs three Android permissions. You can change them in Settings.") }
                    item {
                        GrantRow("Notification access", "Lets Farhan see incoming notifications.", grants.listener, "Open settings") {
                            openSettings(context, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        }
                    }
                    item {
                        GrantRow("Do Not Disturb access", "Lets Farhan add and remove its own Android mode.", grants.policy, "Open settings") {
                            openSettings(context, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                        }
                    }
                    item {
                        GrantRow("Post notifications", "Lets Farhan show alerts and the Off action.", grants.post, "Allow") {
                            requestPost.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    item {
                        val exceptions = sound.exceptionPackages()
                        SoundCard {
                            Text("Before you turn it on", style = MaterialTheme.typography.titleMedium)
                            Text("Selected alerts use Farhan’s name and sound. They can play during your own Do Not Disturb and Bedtime modes. To stop them, turn off Override Do Not Disturb for Farhan’s alert channel.")
                            Text("If you turn off that override, Farhan releases its mute. Reinstall Farhan to reset the channel.")
                            Text("Only the first alert in a conversation sounds until you open or dismiss Farhan’s alert.")
                            Text("If Farhan stops, your phone can stay muted. Tap Off in the status notification, open Farhan and turn it off, or stop Farhan’s rule in Android Modes.")
                            Text("Work profile notifications, exception apps and notifications marked Critical can be silenced. Android Modes lets you add an app or channel exception.")
                            if (exceptions.isNotEmpty()) {
                                val names = exceptions.map { name -> apps.firstOrNull { it.packageName == name }?.appName ?: name }
                                Text("Your exception apps: ${names.sorted().joinToString()}")
                            }
                            Text("Farhan allows calls from anyone and repeat callers. It leaves the call screener’s notification alone. Check calls and your screener on your phone after enabling.")
                        }
                    }
                    item {
                        Button(
                            enabled = !working && grants.listener && grants.policy && grants.post,
                            onClick = { finishAction { sound.enable(); page = Page.SETTINGS } },
                        ) { Text("I understand, turn it on") }
                    }
                }
                Page.ELECTION -> {
                    item { Text("Alerts can sound when their app or category is selected and the delivery schedule allows them.") }
                    item { Text("Categories", style = MaterialTheme.typography.titleMedium) }
                    items(NotificationCategory.entries, key = { "category-${it.name}" }) { category ->
                        SoundCard {
                            ExperimentRow(category.name.lowercase().replaceFirstChar { it.uppercase() }, "", category in choices.categories) {
                                scope.launch { sound.setCategoryElected(category, it); choices = sound.choices() }
                            }
                        }
                    }
                    item {
                        Text("Apps", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = search, onValueChange = { search = it },
                            label = { Text("Find an app") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    val visibleApps = apps.distinctBy { it.packageName }.filter {
                        it.appName.contains(search, ignoreCase = true) || it.packageName.contains(search, ignoreCase = true)
                    }
                    items(visibleApps, key = { "app-${it.packageName}" }) { app ->
                        SoundCard {
                            ExperimentRow(app.appName, app.packageName, app.packageName in choices.apps) {
                                scope.launch { sound.setAppElected(app.packageName, it); choices = sound.choices() }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SoundCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun GrantRow(label: String, description: String, granted: Boolean, action: String, open: () -> Unit) {
    SoundCard {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(if (granted) "Allowed" else description, style = MaterialTheme.typography.bodySmall)
        if (!granted) OutlinedButton(onClick = open) { Text(action) }
    }
}

private fun openSettings(context: Context, action: String, packageName: String? = null) {
    val intent = Intent(action)
    packageName?.let { intent.putExtra(Settings.EXTRA_APP_PACKAGE, it) }
    if (action == Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS) {
        intent.putExtra(Settings.EXTRA_CHANNEL_ID, "farhan_breakthrough_notifications")
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

private fun describe(state: SoundControlState): String = when (state) {
    SoundControlState.Off -> "Farhan’s rule is off"
    SoundControlState.Creating -> "Turning on Farhan’s rule"
    is SoundControlState.Held -> "Farhan’s rule is on"
    is SoundControlState.Suspended -> when (state.reason) {
        SuspensionReason.FILTER_OFF -> "Turn on the notification filter before resuming."
        SuspensionReason.APP_OFF -> "Turn on Farhan’s main switch before resuming."
        SuspensionReason.LISTENER_ACCESS -> "Notification access is off."
        SuspensionReason.NEEDS_ACCESS -> "Do Not Disturb access is off. Open Android Modes if Farhan’s rule is still on."
        SuspensionReason.POST_PERMISSION -> "Permission to post notifications is off."
        SuspensionReason.CHANNEL_UNUSABLE -> "Farhan’s alert channel cannot override Do Not Disturb. Check its settings."
        SuspensionReason.STOPPED_IN_SETTINGS -> "Stopped in Settings. Turn on sound control to resume."
        SuspensionReason.REMOVAL_FAILED -> "Could not remove the rule. Turn it off in Android Modes."
        SuspensionReason.STORAGE_FAILED -> "Could not save the change. Check Android Modes before trying again."
        SuspensionReason.PLATFORM_FAILED -> "Android could not update the rule. Check Android Modes before trying again."
    }
}

private fun describeFilter(filter: Int) = when (filter) {
    NotificationManager.INTERRUPTION_FILTER_ALL -> "Off"
    NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "Priority only"
    NotificationManager.INTERRUPTION_FILTER_ALARMS -> "Alarms only"
    NotificationManager.INTERRUPTION_FILTER_NONE -> "No interruptions"
    else -> "Unknown"
}
