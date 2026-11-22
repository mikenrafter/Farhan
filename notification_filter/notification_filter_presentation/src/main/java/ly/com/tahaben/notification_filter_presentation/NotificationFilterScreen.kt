package ly.com.tahaben.notification_filter_presentation

import ly.com.tahaben.notification_filter_presentation.settings.copySensitive
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import ly.com.tahaben.notification_filter_domain.inbox.InboxClearScope
import ly.com.tahaben.notification_filter_domain.inbox.OtpCodeExtractor
import ly.com.tahaben.notification_filter_presentation.inbox.DeliveryState
import ly.com.tahaben.notification_filter_presentation.inbox.InboxSection
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.launch
import ly.com.tahaben.core.R
import ly.com.tahaben.core_ui.components.CommonAppBar
import ly.com.tahaben.core_ui.LocalSpacing
import ly.com.tahaben.core_ui.OnLifecycleEvent
import ly.com.tahaben.core_ui.components.HowDialog
import ly.com.tahaben.core_ui.components.PermissionDialog
import ly.com.tahaben.core_ui.components.PermissionNotGrantedContent
import ly.com.tahaben.core_ui.components.PostNotificationPermissionTextProvider
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.showcase_layout_compose.model.Arrow
import ly.com.tahaben.showcase_layout_compose.model.Gravity
import ly.com.tahaben.showcase_layout_compose.model.ShowcaseMsg
import ly.com.tahaben.showcase_layout_compose.ui.ShowcaseLayout
import ly.com.tahaben.notification_filter_presentation.apppriority.rememberAppIconLoader
import ly.com.tahaben.notification_filter_presentation.components.InboxActions
import ly.com.tahaben.notification_filter_presentation.components.InboxSectionHeader
import ly.com.tahaben.notification_filter_presentation.components.NotificationRowCard
import ly.com.tahaben.notification_filter_presentation.components.NotificationThreadCard
import ly.com.tahaben.notification_filter_presentation.inbox.InboxSingle
import ly.com.tahaben.notification_filter_presentation.inbox.InboxThread
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NotificationFilterScreen(
    viewModel: NotificationFilterViewModel = hiltViewModel(),
    navigateToNotificationSettings: () -> Unit,
    onNavigateUp: (() -> Unit)?,
    shouldShowRational: (String) -> Boolean,
) {
    val spacing = LocalSpacing.current
    val state = viewModel.state
    val openDialog = remember { mutableStateOf(false) }
    val context = LocalContext.current
    // One stable set of callbacks for every row, so an unchanged row is skipped when the list updates.
    val iconLoader = rememberAppIconLoader()
    val inboxActions = remember(viewModel, context, iconLoader) {
        InboxActions(
            iconLoader = iconLoader,
            onOpen = { item ->
                Timber.d("notification click composable")
                viewModel.onEvent(NotificationFilterEvent.OnOpenNotification(notificationItem = item))
            },
            onAction = { id, index ->
                viewModel.onEvent(NotificationFilterEvent.OnFireNotificationAction(id, index))
            },
            onAlwaysAllow = { item ->
                viewModel.onEvent(NotificationFilterEvent.OnExcludeAppClick(item.packageName))
                Toast.makeText(context, "${item.appName} will always pass through", Toast.LENGTH_SHORT).show()
            },
            onAppInfo = { packageName ->
                viewModel.onEvent(NotificationFilterEvent.OnLaunchAppInfoClick(packageName))
            },
            onToggleExpand = { key -> viewModel.onEvent(NotificationFilterEvent.OnToggleThread(key)) },
            onToggleSelect = { ids -> viewModel.onEvent(NotificationFilterEvent.OnToggleSelected(ids)) },
            onCopyCode = { code ->
                // The digits only, as the notification's Copy does, so "G-482913" pastes into a six-digit field.
                copySensitive(context, OtpCodeExtractor.copyForm(code), "Verification code")
                // Android 13 and later show their own "copied" confirmation.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
    var showPostNotificationDialog by remember { mutableStateOf(false) }
    var isPostNotificationPermanentlyDeclined by remember { mutableStateOf(false) }

    val postNotificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isGranted) {
                isPostNotificationPermanentlyDeclined = !shouldShowRational(Manifest.permission.POST_NOTIFICATIONS)
                showPostNotificationDialog = true
            }
        }
    )

    fun requestPostNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!state.isPermissionGranted) return
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            postNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    OnLifecycleEvent { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> {
                viewModel.startObserving()
                viewModel.checkServiceStats()
                requestPostNotificationPermissionIfNeeded()
            }

            Lifecycle.Event.ON_STOP -> viewModel.stopObserving()
            else -> Unit
        }
    }

    // Leaving the screen (for example switching to another bottom tab) stops the database observation too.
    androidx.compose.runtime.DisposableEffect(viewModel) { onDispose { viewModel.stopObserving() } }

    var isShowcasing by remember { mutableStateOf(false) }
    Timber.d("isShowcasing value = $isShowcasing")
    ShowcaseLayout(
        isShowcasing = isShowcasing,
        initIndex = 0,
        greeting = ShowcaseMsg(
            stringResource(R.string.notification_filter_showcase_greeting),
            msgBackground = MaterialTheme.colorScheme.surface,
            roundedCorner = 15.dp
        ),
        onFinish = {
            viewModel.setShowcased()
            isShowcasing = false
        },
        isDarkLayout = false
    ) {
        Column {
            // As the Inbox tab there is no title bar: Settings is its own tab, and the header carries the tabs and Clear.
            if (onNavigateUp != null) {
            CommonAppBar(
                title = stringResource(R.string.notifications),
                onNavigateUp = onNavigateUp,
                actions = {
                    IconButton(onClick = navigateToNotificationSettings) {
                        Showcase(
                            index = 1,
                            message = ShowcaseMsg(
                                text = stringResource(R.string.notification_settings_shocase_tip),
                                textStyle = TextStyle(MaterialTheme.colorScheme.onSurface),
                                gravity = Gravity.Auto,
                                arrow = Arrow(curved = true),
                                msgBackground = MaterialTheme.colorScheme.surface,
                                roundedCorner = 15.dp
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.notifications_filter_settings)
                            )
                        }
                    }
                }
            )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {

                if (!state.isPermissionGranted) {
                    PermissionNotGrantedContent(
                        modifier = Modifier.fillMaxSize(),
                        message = stringResource(R.string.notification_permission_not_granted),
                        subMessage = stringResource(R.string.notification_filter_needed_message),
                        onGrantClick = viewModel::startNotificationService,
                        onHowClick = { openDialog.value = true }
                    )
                } else {
                    if (state.isFirstTimeOpened) {
                        Timber.d("shouldshowcase = true")
                        // The showcase points at the title bar's gear, which the Inbox tab does not have.
                        if (onNavigateUp != null) isShowcasing = true
                    }
                    InboxContent(viewModel = viewModel, actions = inboxActions)
                }
            }
        }
    }
    if (openDialog.value) {
        HowDialog(
            gifId = R.drawable.notification_permission_howto,
            gifDescription = stringResource(R.string.how_to_enable_permission),
            onDismiss = { openDialog.value = false }
        )
    }
    if (showPostNotificationDialog) {
        PermissionDialog(
            permissionTextProvider = PostNotificationPermissionTextProvider(),
            isPermanentlyDeclined = isPostNotificationPermanentlyDeclined,
            onDismiss = { showPostNotificationDialog = false },
            onOkClick = {
                showPostNotificationDialog = false
                if (!isPostNotificationPermanentlyDeclined) {
                    postNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onGoToAppSettingsClick = {
                showPostNotificationDialog = false
                viewModel.openAppSettings()
            },
        )
    }
}

private val SectionSubtitles = mapOf(
    DeliveryState.WAITING to "Farhan sends these at the next delivery window.",
    DeliveryState.NOT_SENT to "Farhan could not send these. Open the source app.",
    DeliveryState.SENT to "Already posted to your notification shade.",
)

/** Inbox and Cleared as two pages that follow the finger one to one. Swiping never leaves this screen. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InboxContent(viewModel: NotificationFilterViewModel, actions: InboxActions) {
    val state = viewModel.state
    val spacing = LocalSpacing.current
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(initialPage = if (state.showCleared) 1 else 0) { 2 }
    val scope = rememberCoroutineScope()
    // Null until that tab has loaded once, so a first swipe shows nothing instead of a false empty state.
    var inboxSnapshot by remember { mutableStateOf<List<InboxSection>?>(null) }
    var clearedSnapshot by remember { mutableStateOf<List<InboxSection>?>(null) }
    LaunchedEffect(state.sections, state.isLoading, state.showCleared) {
        if (!state.isLoading) {
            if (state.showCleared) clearedSnapshot = state.sections else inboxSnapshot = state.sections
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if ((page == 1) != viewModel.state.showCleared) viewModel.onEvent(NotificationFilterEvent.OnToggleClearedView)
        }
    }
    var confirmClear by remember { mutableStateOf(false) }
    val selectedCount = if (state.showCleared) 0 else state.selected.size
    val clearLabel = when {
        selectedCount > 0 -> "Clear $selectedCount"
        state.showCleared -> "Empty"
        else -> stringResource(state.clearLabel)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        InboxTabs(
            pagerState = pagerState,
            onSelect = { page -> scope.launch { pagerState.animateScrollToPage(page) } },
            actionLabel = clearLabel,
            onAction = {
                if (selectedCount > 0) viewModel.onEvent(NotificationFilterEvent.OnClearSelected) else confirmClear = true
            },
        )
        if (!state.showCleared) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = spacing.spaceMedium, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(spacing.spaceSmall)
            ) {
                listOf(
                    NotificationPriority.CRITICAL to "Critical",
                    NotificationPriority.HIGH to "High",
                    NotificationPriority.MEDIUM to "Medium",
                    NotificationPriority.LOW to "Low",
                    null to "All"
                ).forEach { (priority, label) ->
                    FilterChip(
                        selected = state.priorityFilter == priority,
                        onClick = { viewModel.onEvent(NotificationFilterEvent.OnSetPriorityFilter(priority)) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }
        // The paging row only appears when there is more than one page.
        if (state.page > 0 || state.hasNextPage) Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.spaceMedium),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            androidx.compose.material3.TextButton(onClick = viewModel::previousPage, enabled = state.page > 0) { Text("Newer") }
            Text("Page ${state.page + 1}", style = MaterialTheme.typography.labelSmall)
            androidx.compose.material3.TextButton(onClick = viewModel::nextPage, enabled = state.hasNextPage) { Text("Older") }
        }
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val shownPage = if (state.showCleared) 1 else 0
            val sections = when {
                page == shownPage && !state.isLoading -> state.sections
                page == 1 -> clearedSnapshot
                else -> inboxSnapshot
            }
            InboxPage(
                sections = sections ?: emptyList(),
                state = state,
                actions = actions,
                selectable = page == 0,
                loading = sections == null || (page == shownPage && state.isLoading),
            )
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(state.clearLabel) + "?") },
            text = {
                Text(
                    when (val scope = state.clearScope) {
                        InboxClearScope.Cleared -> "This removes the Cleared history for good."
                        InboxClearScope.All -> "This removes everything in the Inbox. Farhan will not send them. The Cleared history stays."
                        is InboxClearScope.Priority -> "This removes the ${scope.priority.name.lowercase()} priority notifications from the Inbox. Farhan will not send them."
                    }
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmClear = false
                    state.dispatchClear(viewModel::onEvent)
                }) { Text("Clear") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InboxPage(
    sections: List<InboxSection>,
    state: NotificationFilterState,
    actions: InboxActions,
    selectable: Boolean,
    loading: Boolean,
) {
    val spacing = LocalSpacing.current
    if (sections.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (!loading) Text(
                text = stringResource(R.string.no_notifications),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(spacing.spaceSmall),
        contentPadding = PaddingValues(bottom = spacing.spaceHuge),
    ) {
        sections.forEach { section ->
            stickyHeader(key = section.key, contentType = "header") {
                InboxSectionHeader(
                    title = if (section.isCodes) "Codes" else section.delivery.headerLabel,
                    count = section.messageCount,
                    onPage = state.page > 0 || state.hasNextPage,
                    subtitle = if (section.isCodes) "Nothing is copied until you tap Copy."
                    else SectionSubtitles.getValue(section.delivery),
                )
            }
            items(
                items = section.entries,
                key = { it.key },
                contentType = { it.contentType },
            ) { entry ->
                val itemModifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = tween(200))
                when (entry) {
                    is InboxSingle -> NotificationRowCard(
                        message = entry.message,
                        expanded = entry.key in state.expandedThreads,
                        selected = entry.message.item.id in state.selected,
                        selectable = selectable,
                        actions = actions,
                        modifier = itemModifier,
                    )

                    is InboxThread -> NotificationThreadCard(
                        thread = entry,
                        expanded = entry.key in state.expandedThreads,
                        selected = entry.messages.all { it.item.id in state.selected },
                        selectable = selectable,
                        actions = actions,
                        modifier = itemModifier,
                    )
                }
            }
        }
    }
}

/** Inbox and Cleared tabs sharing one row with the Clear button; the underline follows the pager's drag. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InboxTabs(
    pagerState: androidx.compose.foundation.pager.PagerState,
    onSelect: (Int) -> Unit,
    actionLabel: String,
    onAction: () -> Unit,
) {
    var width by remember { mutableStateOf(0) }
    Column {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f).onSizeChanged { width = it.width }) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf("Inbox", "Cleared").forEachIndexed { index, title ->
                        val selected = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onSelect(index) }
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset { IntOffset(((pagerState.currentPage + pagerState.currentPageOffsetFraction) * width / 2f).roundToInt(), 0) }
                        .fillMaxWidth(0.5f)
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.onSurface),
                )
            }
            androidx.compose.material3.TextButton(onClick = onAction) { Text(actionLabel) }
        }
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
