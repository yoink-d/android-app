package com.maynkudu.yoinkd.core.design

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.maynkudu.yoinkd.R
import com.maynkudu.yoinkd.core.model.DisplayMode
import com.maynkudu.yoinkd.core.model.MediaFilter
import com.maynkudu.yoinkd.core.model.MediaSort

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionHeader(title: String, query: String, searching: Boolean, onQuery: (String) -> Unit,
    onSearch: (Boolean) -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    val requester = remember { FocusRequester() }
    TopAppBar(title = {
        if (searching) OutlinedTextField(query, onQuery, Modifier.fillMaxWidth().focusRequester(requester),
            singleLine = true, placeholder = { Text("Search ${title.lowercase()}") })
        else Text(title, style = MaterialTheme.typography.headlineLarge)
    }, actions = {
        if (!searching) actions()
        IconButton(onClick = { onSearch(!searching) }) {
            RuneIcon(if (searching) R.drawable.rune_close else R.drawable.rune_search,
                if (searching) "Close search" else "Search ${title.lowercase()}")
        }
    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
    LaunchedEffect(searching) { if (searching) requester.requestFocus() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaFilters(selected: MediaFilter, onSelect: (MediaFilter) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        MediaFilter.entries.forEachIndexed { index, filter ->
            SegmentedButton(selected == filter, onClick = { onSelect(filter) },
                shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(filter.label) }
        }
    }
}

@Composable
fun SortMenu(sort: MediaSort, display: DisplayMode, onSort: (MediaSort) -> Unit, onDisplay: (DisplayMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { RuneIcon(R.drawable.rune_sort, "Sort and display") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            MediaSort.entries.forEach { option ->
                DropdownMenuItem(text = { Text(option.label) }, onClick = { onSort(option); expanded = false },
                    trailingIcon = { if (sort == option) RuneIcon(R.drawable.rune_check) })
            }
            HorizontalDivider()
            DisplayMode.entries.forEach { option ->
                DropdownMenuItem(text = { Text(option.label) }, onClick = { onDisplay(option); expanded = false },
                    leadingIcon = { RuneIcon(if (option == DisplayMode.GRID) R.drawable.rune_grid else R.drawable.rune_menu) },
                    trailingIcon = { if (display == option) RuneIcon(R.drawable.rune_check) })
            }
        }
    }
}
