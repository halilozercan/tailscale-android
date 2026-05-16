// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.viewModel.PER_APP_NO_EXIT_NODE
import com.tailscale.ipn.ui.viewModel.PerAppExitNodeListViewModel

@Composable
fun PerAppExitNodeView(
    backToSettings: BackNavigation,
    onPickForApp: (String) -> Unit,
    model: PerAppExitNodeListViewModel = viewModel(),
) {
  val installedApps by model.installedApps.collectAsState()
  val perAppMap by model.perAppMap.collectAsState()
  val exitNodeLabels by model.exitNodeLabels.collectAsState()
  var query by remember { mutableStateOf("") }

  Scaffold(
      topBar = { Header(titleRes = R.string.per_app_exit_node, onBack = backToSettings) },
  ) { innerPadding ->
    if (installedApps.isEmpty()) {
      Box(
          modifier = Modifier.fillMaxSize().padding(innerPadding),
          contentAlignment = Alignment.Center,
      ) {
        CircularProgressIndicator(
            modifier = Modifier.width(64.dp),
            color = MaterialTheme.colorScheme.secondary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
      }
      return@Scaffold
    }

    // Filter (case-insensitive substring on name OR package), then partition.
    // Apps with overrides always appear in their section first regardless of
    // an empty query; a non-empty query narrows both sections.
    val needle = query.trim().lowercase()
    val filtered =
        if (needle.isEmpty()) installedApps
        else
            installedApps.filter {
              it.name.lowercase().contains(needle) ||
                  it.packageName.lowercase().contains(needle)
            }
    val overridden = filtered.filter { perAppMap.containsKey(it.packageName) }
    val others = filtered.filterNot { perAppMap.containsKey(it.packageName) }

    Column(modifier = Modifier.padding(innerPadding)) {
      OutlinedTextField(
          value = query,
          onValueChange = { query = it },
          singleLine = true,
          placeholder = { Text(stringResource(R.string.per_app_search_hint)) },
          leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
          trailingIcon = {
            if (query.isNotEmpty()) {
              IconButton(onClick = { query = "" }) {
                Icon(Icons.Default.Clear, contentDescription = "clear")
              }
            }
          },
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      )

      LazyColumn(modifier = Modifier.fillMaxWidth()) {
      item("header") {
        ListItem(
            headlineContent = { Text(stringResource(R.string.per_app_exit_node_description)) })
      }

      if (overridden.isNotEmpty()) {
        item("overridesDivider") {
          Lists.SectionDivider(
              stringResource(R.string.per_app_count_overrides, overridden.size.toString()))
        }
        items(overridden, key = { "ovr:${it.packageName}" }) { app ->
          PerAppRow(
              app = app,
              assignmentLabel =
                  assignmentLabel(perAppMap[app.packageName], exitNodeLabels),
              packageManager = model.installedAppsManager.packageManager,
              onClick = { onPickForApp(app.packageName) },
          )
          Lists.ItemDivider()
        }
      }

      item("othersDivider") {
        Lists.SectionDivider(stringResource(R.string.per_app_section_others))
      }
      items(others, key = { "oth:${it.packageName}" }) { app ->
        PerAppRow(
            app = app,
            assignmentLabel = null,
            packageManager = model.installedAppsManager.packageManager,
            onClick = { onPickForApp(app.packageName) },
        )
        Lists.ItemDivider()
      }
      }
    }
  }
}

@Composable
private fun PerAppRow(
    app: com.tailscale.ipn.ui.util.InstalledApp,
    assignmentLabel: String?,
    packageManager: android.content.pm.PackageManager,
    onClick: () -> Unit,
) {
  ListItem(
      modifier = Modifier.clickable(onClick = onClick),
      headlineContent = { Text(app.name, fontWeight = FontWeight.SemiBold) },
      leadingContent = {
        Image(
            bitmap =
                packageManager.getApplicationIcon(app.packageName).toBitmap().asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.width(40.dp).height(40.dp),
        )
      },
      supportingContent = {
        Text(
            assignmentLabel ?: stringResource(R.string.per_app_assigned_global),
            color =
                if (assignmentLabel == null) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.primary,
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
            letterSpacing = MaterialTheme.typography.bodySmall.letterSpacing,
        )
      },
  )
}

@Composable
private fun assignmentLabel(
    selection: com.tailscale.ipn.ui.model.StableNodeID?,
    labels: Map<com.tailscale.ipn.ui.model.StableNodeID, String>,
): String? {
  return when {
    selection == null -> null
    selection == PER_APP_NO_EXIT_NODE -> stringResource(R.string.per_app_assigned_none)
    else ->
        stringResource(R.string.per_app_assigned_exit_node, labels[selection] ?: selection)
  }
}
