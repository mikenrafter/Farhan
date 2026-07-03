package ly.com.tahaben.notification_filter_presentation.apppriority

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import ly.com.tahaben.core_ui.LocalSpacing
import ly.com.tahaben.core_ui.mirror
import ly.com.tahaben.notification_filter_domain.model.AppPriorityInfo
import ly.com.tahaben.notification_filter_domain.model.ChannelPriorityInfo
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority

private val PRIORITY_COLORS = mapOf(
    NotificationPriority.CRITICAL to Color(0xFFF44336),
    NotificationPriority.HIGH to Color(0xFFFF9800),
    NotificationPriority.MEDIUM to Color(0xFF2196F3),
    NotificationPriority.LOW to Color(0xFF9E9E9E),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPriorityScreen(
    viewModel: AppPriorityViewModel = hiltViewModel(),
    onNavigateUp: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val state = viewModel.state

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("App Priorities") },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            ),
            navigationIcon = {
                IconButton(onClick = onNavigateUp) {
                    Icon(
                        modifier = Modifier.mirror(),
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            }
        )

        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxSize()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        } else if (state.apps.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "No notifications recorded yet.",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(spacing.spaceMedium),
                verticalArrangement = Arrangement.spacedBy(spacing.spaceSmall)
            ) {
                items(state.apps, key = { it.packageName }) { appInfo ->
                    val isExpanded = appInfo.packageName in state.expandedPackages
                    AppPriorityCard(
                        appInfo = appInfo,
                        isExpanded = isExpanded,
                        onToggleExpanded = {
                            viewModel.onEvent(AppPriorityEvent.OnToggleExpanded(appInfo.packageName))
                        },
                        onSetPriority = { priority ->
                            viewModel.onEvent(AppPriorityEvent.OnSetAppPriority(appInfo.packageName, priority))
                        },
                        onSetLocked = { locked ->
                            viewModel.onEvent(AppPriorityEvent.OnSetAppLocked(appInfo.packageName, locked))
                        },
                        onSetChannelPriority = { channelId, priority ->
                            viewModel.onEvent(
                                AppPriorityEvent.OnSetChannelPriority(appInfo.packageName, channelId, priority)
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppPriorityCard(
    appInfo: AppPriorityInfo,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSetPriority: (NotificationPriority?) -> Unit,
    onSetLocked: (Boolean) -> Unit,
    onSetChannelPriority: (channelId: String, priority: NotificationPriority?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val priorityColor = PRIORITY_COLORS[appInfo.currentPriority] ?: Color(0xFF9E9E9E)

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        onClick = onToggleExpanded,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.spaceMedium),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = appInfo.appName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = appInfo.priorityReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Priority badge
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = priorityColor.copy(alpha = 0.15f),
                    ) {
                        Text(
                            text = appInfo.currentPriority.name.lowercase()
                                .replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            color = priorityColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    // Lock icon
                    Icon(
                        imageVector = if (appInfo.isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        contentDescription = if (appInfo.isLocked) "Locked" else "Unlocked",
                        tint = if (appInfo.isLocked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(2.dp)
                    )
                    // Expand/collapse icon
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(4.dp))

                // Override priority row
                Text(
                    text = "Override priority:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PriorityChipRow(
                    selected = appInfo.userOverride,
                    onSelect = onSetPriority,
                    showDefault = true,
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Lock toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Lock this priority",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Switch(
                        checked = appInfo.isLocked,
                        onCheckedChange = onSetLocked
                    )
                }

                // Channels section
                if (appInfo.channels.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Channels",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    appInfo.channels.forEach { channelInfo ->
                        ChannelRow(
                            channelInfo = channelInfo,
                            onSetPriority = { priority ->
                                onSetChannelPriority(channelInfo.channelId, priority)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channelInfo: ChannelPriorityInfo,
    onSetPriority: (NotificationPriority?) -> Unit,
) {
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
            selected = channelInfo.override,
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
    Row(
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NotificationPriority.entries.forEach { priority ->
            val color = PRIORITY_COLORS[priority] ?: Color(0xFF9E9E9E)
            val isSelected = selected == priority
            FilterChip(
                selected = isSelected,
                onClick = {
                    // Toggle off → null (Default), otherwise select
                    onSelect(if (isSelected) null else priority)
                },
                label = {
                    Text(
                        text = priority.name.lowercase().replaceFirstChar { it.uppercase() },
                        fontSize = if (compact) 10.sp else 12.sp,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = color.copy(alpha = 0.2f),
                    selectedLabelColor = color,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
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
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    selectedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
