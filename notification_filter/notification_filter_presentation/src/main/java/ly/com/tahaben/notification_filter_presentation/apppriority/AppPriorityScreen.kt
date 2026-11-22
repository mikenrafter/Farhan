package ly.com.tahaben.notification_filter_presentation.apppriority

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import android.os.Build
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import ly.com.tahaben.core_ui.components.InfoButton
import ly.com.tahaben.core_ui.components.SettingToggleRow
import ly.com.tahaben.core_ui.components.SettingsBox
import ly.com.tahaben.core_ui.components.SettingsRowDivider
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.core.os.ConfigurationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ly.com.tahaben.notification_filter_presentation.R
import java.util.Locale
import ly.com.tahaben.core_ui.LocalSpacing
import ly.com.tahaben.notification_filter_domain.delivery.AppQueuePreference
import ly.com.tahaben.notification_filter_domain.delivery.QueueOrder
import ly.com.tahaben.notification_filter_domain.model.AppPriorityInfo
import ly.com.tahaben.notification_filter_domain.model.ChannelPriorityInfo
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority

private val PRIORITY_COLORS = mapOf(
    NotificationPriority.CRITICAL to Color(0xFFF44336),
    NotificationPriority.HIGH to Color(0xFFFF9800),
    NotificationPriority.MEDIUM to Color(0xFF2196F3),
    NotificationPriority.LOW to Color(0xFF9E9E9E),
)

/** The searchable app list. Tapping an app calls [onOpenApp]; its settings page is [AppPriorityDetail]. */
@Composable
fun AppPriorityScreen(onOpenApp: (packageName: String) -> Unit, viewModel: AppPriorityViewModel = hiltViewModel()) {
    AppPriorityList(viewModel.state, onOpenApp)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AppPriorityList(state: AppPriorityState, onOpenApp: (String) -> Unit) {
    val listState = remember { LazyListState() }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<AppFilter>(AppFilter.All) }
    val apps = (state.apps as? SnapshotStateList<AppPriorityInfo>)?.toList() ?: state.apps
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()
    val waitingForApps = state.isLoading && apps.isEmpty()
    val catalog by produceState<AppListCatalog?>(null, apps, locale, waitingForApps) {
        if (!waitingForApps) {
            value = withContext(Dispatchers.Default) { AppListCatalog(apps, locale) }
        }
    }
    var displayed by remember { mutableStateOf<AppList?>(null) }
    var displayedFilter by remember { mutableStateOf<Pair<AppFilter, String>>(AppFilter.All to "") }
    LaunchedEffect(catalog, filter, query) {
        val current = catalog ?: return@LaunchedEffect
        val list = withContext(Dispatchers.Default) { current.filter(filter, query) }
        val selection = filter to query
        if (displayedFilter != selection && list.sections.isNotEmpty()) listState.requestScrollToItem(0)
        displayedFilter = selection
        displayed = list
    }
    val icons = rememberAppIconLoader()

    Column(modifier = Modifier.fillMaxSize()) {
        AppListControls(query, { query = it }, filter, { filter = it }, catalog)

        val list = displayed
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (list != null && list.sections.isEmpty()) {
                val message = if (catalog?.count(AppFilter.All) == 0) R.string.app_list_empty
                else R.string.app_list_no_matches
                Text(
                    text = stringResource(message),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            } else {
                val loadingDescription = stringResource(R.string.app_list_loading)
                val loading = if (list == null) Modifier.clearAndSetSemantics {
                    contentDescription = loadingDescription
                    liveRegion = LiveRegionMode.Polite
                } else Modifier
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(end = appRailWidth).then(loading),
                    contentPadding = PaddingValues(start = 12.dp, end = 4.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (list == null) {
                        item(key = "loading-header", contentType = "letter") { AppSectionHeader(null) }
                        items(count = 9, key = { "loading-$it" }, contentType = { "loading" }) { index ->
                            AppRowSkeleton(Modifier.testTag("app-placeholder-$index"))
                        }
                    } else {
                        list.sections.forEach { section ->
                            stickyHeader(key = "letter:${section.letter}", contentType = "letter") {
                                AppSectionHeader(section.letter)
                            }
                            items(
                                items = section.apps,
                                key = { it.packageName },
                                contentType = { "app" },
                            ) { app ->
                                AppPriorityRow(
                                    appInfo = app,
                                    letter = section.letter,
                                    icons = icons,
                                    onClick = { onOpenApp(app.packageName) },
                                    modifier = Modifier.testTag("app-row:${app.packageName}"),
                                )
                            }
                        }
                    }
                }
                if (list != null) {
                    AppAlphabetRail(
                        letters = list.firstRowByLetter.keys.toList(),
                        onJump = { letter ->
                            // Bring the first row into view with its sticky header still above it.
                            listState.requestScrollToItem(list.firstRowByLetter.getValue(letter) - 1)
                        },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
        }
    }
}

@Composable
private fun AppSectionHeader(letter: String?) {
    Box(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
            .padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = letter ?: "A",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (letter == null) Color.Transparent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = if (letter == null) Modifier.clearAndSetSemantics {} else Modifier.semantics { heading() },
        )
        if (letter == null) SkeletonMark(12.dp, 6.dp)
    }
}

@Composable
private fun AppRowSkeleton(modifier: Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        AppRowHeader(
            title = null,
            category = null,
            icon = { SkeletonMark(appIconSize, appIconSize) },
            actions = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        PriorityBadge(NotificationPriority.MEDIUM, Color.Transparent)
                        SkeletonMark(38.dp, 12.dp)
                    }
                    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { SkeletonMark(14.dp, 16.dp) }
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { SkeletonMark(12.dp, 8.dp) }
                }
            },
        )
    }
}

@Composable
private fun AppRowHeader(
    title: String?,
    category: String?,
    icon: @Composable () -> Unit,
    actions: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon()
        Column(Modifier.weight(1f)) {
            Box(contentAlignment = Alignment.CenterStart) {
                Text(
                    text = title ?: "App",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (title == null) Color.Transparent else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (title == null) SkeletonMark(88.dp, 10.dp)
            }
            Box(contentAlignment = Alignment.CenterStart) {
                Text(
                    text = category ?: "Category",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (category == null) Color.Transparent else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (category == null) SkeletonMark(48.dp, 7.dp)
            }
        }
        actions()
    }
}

@Composable
private fun SkeletonMark(width: Dp, height: Dp) {
    Box(
        Modifier.size(width, height)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
    )
}

@Composable
private fun PriorityBadge(priority: NotificationPriority, color: Color) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (color == Color.Transparent) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(Modifier.size(6.dp).background(color, CircleShape))
            Text(
                text = priority.name.lowercase().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelSmall,
                color = if (color == Color.Transparent) Color.Transparent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun AppPriorityRow(
    appInfo: AppPriorityInfo,
    letter: String,
    icons: AppIconLoader<ImageBitmap>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = appInfo.notificationsEnabled
    val disabledDescription = stringResource(R.string.app_list_notifications_disabled)
    Card(
        modifier = modifier.fillMaxWidth().semantics {
            if (!enabled) stateDescription = disabledDescription
        },
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
        onClick = onClick,
    ) {
        AppRowHeader(
            title = appInfo.appName,
            category = appInfo.resolvedCategory.displayName(),
            icon = { PackageAppIcon(appInfo.packageName, letter, icons) },
            actions = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PriorityBadge(appInfo.currentPriority, PRIORITY_COLORS[appInfo.currentPriority] ?: Color(0xFF9E9E9E))
                    if (appInfo.isLocked) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = "Locked",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(2.dp).size(16.dp),
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }
}

/**
 * One app's settings page. The notification rows live here; [extraSections] lets the host add the sections that
 * belong to other features (the focus tools' per-app exceptions) without this module knowing them.
 */
@Composable
fun AppPriorityDetail(
    packageName: String,
    modifier: Modifier = Modifier,
    viewModel: AppPriorityViewModel = hiltViewModel(),
    extraSections: @Composable ColumnScope.() -> Unit = {},
) {
    val appInfo = viewModel.appInfo(packageName)
    val icons = rememberAppIconLoader()
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.spaceMedium, vertical = spacing.spaceSmall),
        verticalArrangement = Arrangement.spacedBy(spacing.spaceSmall),
    ) {
        if (appInfo == null) {
            Text(
                text = stringResource(R.string.app_list_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        val enabled = appInfo.notificationsEnabled
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
        ) {
            AppRowHeader(
                title = appInfo.appName,
                category = appInfo.resolvedCategory.displayName(),
                icon = { PackageAppIcon(appInfo.packageName, appInfo.appName.take(1).uppercase(), icons) },
                actions = { PriorityBadge(appInfo.currentPriority, PRIORITY_COLORS[appInfo.currentPriority] ?: Color(0xFF9E9E9E)) },
            )
        }
        if (enabled) {
            AppCategorySummary(appInfo)
        } else {
            Text(
                text = stringResource(R.string.app_list_notifications_disabled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        var allowed by remember(packageName, appInfo) { mutableStateOf(viewModel.isAlwaysAllowed(packageName)) }
        var soundElected by remember(packageName) { mutableStateOf(viewModel.isSoundElected(packageName)) }
        SettingsBox("Notifications") {
            Text(
                text = "Priority" + if (appInfo.userOverride == null) " · default is ${appInfo.defaultPriority.label()}" else "",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            PriorityChipRow(
                selected = appInfo.userOverride,
                onSelect = { viewModel.onEvent(AppPriorityEvent.OnSetAppPriority(packageName, it)) },
                showDefault = true,
            )
            InfoButton("Priority", "${appInfo.priorityReason}\n\nTap the chosen priority again, or Default, to go back to the app's default. Channels below can sit above or below the app's priority.")
            SettingsRowDivider()
            SettingToggleRow(
                title = "Lock priority",
                summary = "Keep the flood and chatter rules from changing it",
                checked = appInfo.isLocked,
                onCheckedChange = { viewModel.onEvent(AppPriorityEvent.OnSetAppLocked(packageName, it)) },
            )
            SettingsRowDivider()
            SettingToggleRow(
                title = "Always allow through",
                summary = "Skip the filter and arrive at once",
                info = "Marks this app Critical, the same as Always allow in the Inbox. Turning it off returns the app to its normal priority.",
                checked = allowed,
                onCheckedChange = { allowed = it; viewModel.setAlwaysAllowed(packageName, it) },
            )
            SettingsRowDivider()
            SettingToggleRow(
                title = "Sound",
                summary = if (Build.VERSION.SDK_INT >= 35) "Let this app make sound when sound control is on" else "Needs Android 15 or later",
                info = "Sound control mutes other apps. Turn this on to let this app's notifications keep their sound. Set up sound control in Settings, Notifications.",
                checked = soundElected,
                onCheckedChange = if (Build.VERSION.SDK_INT >= 35) {
                    { soundElected = it; viewModel.setSoundElected(packageName, it) }
                } else null,
            )
            SettingsRowDivider()
            val otpAll = remember(packageName) { viewModel.otpAllApps() }
            var otpCovered by remember(packageName) { mutableStateOf(viewModel.isOtpCovered(packageName)) }
            SettingToggleRow(
                title = "Verification codes",
                summary = if (otpAll) "Every app is covered. Change this in Settings, Notifications." else "Raise codes from this app",
                checked = otpCovered,
                onCheckedChange = if (otpAll) null else { { otpCovered = it; viewModel.setOtpCovered(packageName, it) } },
            )
        }

        SettingsBox("Order") {
            QueuePreferenceControls(
                appInfo.queuePreference,
                onChange = { viewModel.onEvent(AppPriorityEvent.OnSetQueuePreference(packageName, it)) },
            )
        }

        if (appInfo.channels.isNotEmpty()) {
            SettingsBox("Channels") {
                appInfo.channels.forEach { channelInfo ->
                    ChannelRow(
                        channelInfo = channelInfo,
                        appPriority = appInfo.currentPriority,
                        onSetPriority = { priority ->
                            viewModel.onEvent(AppPriorityEvent.OnSetChannelPriority(packageName, channelInfo.channelId, priority))
                        },
                    )
                }
            }
        }
        extraSections()
    }
}

private fun NotificationPriority.label() = name.lowercase().replaceFirstChar { it.uppercase() }

@Composable
private fun AppCategorySummary(appInfo: AppPriorityInfo) {
    val resolvedLabel = appInfo.resolvedCategory.displayName()

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        appInfo.appCategoryLabel?.let { label ->
            Text(
                text = "App category: $label",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (appInfo.androidNotificationCategoryLabels.isNotEmpty()) {
            Text(
                text = "Notification categories: ${appInfo.androidNotificationCategoryLabels.joinToString(", ")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (appInfo.appCategoryLabel != null || appInfo.androidNotificationCategoryLabels.isNotEmpty()) {
            Text(
                text = "Default group: $resolvedLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChannelRow(
    channelInfo: ChannelPriorityInfo,
    appPriority: NotificationPriority,
    onSetPriority: (NotificationPriority?) -> Unit,
) {
    val effectivePriority = if (channelInfo.offset == 0) null
    else channelInfo.effectivePriority(appPriority)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = channelInfo.channelId,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
        PriorityChipRow(
            selected = effectivePriority,
            onSelect = onSetPriority,
            showDefault = true,
            compact = true,
        )
    }
}

@Composable
private fun PriorityChipRow(
    selected: NotificationPriority?,
    onSelect: (NotificationPriority?) -> Unit,
    showDefault: Boolean = true,
    compact: Boolean = false,
) {
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        selectedLabelColor = MaterialTheme.colorScheme.onSurface,
        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NotificationPriority.entries.forEach { priority ->
            val color = PRIORITY_COLORS[priority] ?: Color(0xFF9E9E9E)
            val isSelected = selected == priority
            FilterChip(
                selected = isSelected,
                onClick = {
                    onSelect(if (isSelected) null else priority)
                },
                leadingIcon = { Box(Modifier.size(6.dp).background(color, CircleShape)) },
                label = {
                    Text(
                        text = priority.name.lowercase().replaceFirstChar { it.uppercase() },
                        fontSize = if (compact) 10.sp else 12.sp,
                    )
                },
                colors = chipColors,
            )
        }
        if (showDefault) {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = {
                    Text(
                        text = "Default",
                        fontSize = if (compact) 10.sp else 12.sp,
                    )
                },
                colors = chipColors,
            )
        }
    }
}

/** Newest/oldest first and a rank, for apps that share a priority. The explanation says priority still wins. */
@Composable
private fun QueuePreferenceControls(preference: AppQueuePreference, onChange: (AppQueuePreference) -> Unit) {
    Text(
        text = "Queue order",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 8.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(
            selected = preference.order == QueueOrder.NEWEST_FIRST,
            onClick = { onChange(preference.copy(order = QueueOrder.NEWEST_FIRST)) },
            label = { Text("Newest first", fontSize = 12.sp) },
            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.surfaceVariant),
        )
        FilterChip(
            selected = preference.order == QueueOrder.OLDEST_FIRST,
            onClick = { onChange(preference.copy(order = QueueOrder.OLDEST_FIRST)) },
            label = { Text("Oldest first", fontSize = 12.sp) },
            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.surfaceVariant),
        )
    }
    var rankText by remember(preference.rank) { mutableStateOf(preference.rank.toString()) }
    fun commitRank() { rankText.toIntOrNull()?.takeIf { it != preference.rank }?.let { onChange(preference.copy(rank = it)) } }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { onChange(preference.copy(rank = preference.rank - 1)) }, enabled = preference.rank > -MAX_RANK) { Text("−") }
        OutlinedTextField(
            value = rankText,
            onValueChange = { if (it.length <= 5 && it.all { c -> c.isDigit() || c == '-' }) rankText = it },
            label = { Text("Rank") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commitRank() }),
            modifier = Modifier.width(110.dp).onFocusChanged { if (!it.isFocused) commitRank() },
        )
        OutlinedButton(onClick = { onChange(preference.copy(rank = preference.rank + 1)) }, enabled = preference.rank < MAX_RANK) { Text("+") }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Priority still comes first",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        InfoButton(
            "Queue order",
            "Notification priority always comes first. Rank and order only decide between apps and notifications of the same priority: a higher rank goes first, and the order chooses newest or oldest within an app.",
        )
    }
}

private const val MAX_RANK = 9999
