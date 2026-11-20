package ly.com.tahaben.notification_filter_presentation.apppriority

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ly.com.tahaben.notification_filter_domain.model.NotificationCategory
import ly.com.tahaben.notification_filter_presentation.R

private val appFilters = listOf(AppFilter.All) +
    NotificationCategory.entries.map { AppFilter.Category(it) } + AppFilter.HasNotifications

@Composable
internal fun AppListControls(
    query: String,
    onQuery: (String) -> Unit,
    selection: AppFilter,
    onSelect: (AppFilter) -> Unit,
    catalog: AppListCatalog?,
) {
    TextField(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        value = query,
        onValueChange = onQuery,
        label = { Text(stringResource(R.string.app_list_search)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        appFilters.forEach { filter ->
            FilterChip(
                selected = selection == filter,
                onClick = { onSelect(filter) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            when (filter) {
                                AppFilter.All -> stringResource(R.string.app_list_all)
                                AppFilter.HasNotifications -> stringResource(R.string.app_list_has_notifications)
                                is AppFilter.Category -> filter.category.displayName()
                            },
                        )
                        Text(
                            text = catalog?.count(filter)?.toString().orEmpty(),
                            modifier = Modifier.widthIn(min = 20.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
            )
        }
    }
}

@Composable
internal fun NotificationCategory.displayName(): String = stringResource(
    when (this) {
        NotificationCategory.COMMUNICATION -> R.string.app_list_communication
        NotificationCategory.SOCIAL -> R.string.app_list_social
        NotificationCategory.PRODUCTIVITY -> R.string.app_list_productivity
        NotificationCategory.FINANCE -> R.string.app_list_finance
        NotificationCategory.HEALTH -> R.string.app_list_health
        NotificationCategory.ENTERTAINMENT -> R.string.app_list_entertainment
        NotificationCategory.SYSTEM -> R.string.app_list_system
        NotificationCategory.OTHER -> R.string.app_list_other
    },
)
