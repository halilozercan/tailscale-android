// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.viewModel.PER_APP_NO_EXIT_NODE
import com.tailscale.ipn.ui.viewModel.PerAppExitNodePickerViewModel
import com.tailscale.ipn.ui.viewModel.PerAppExitNodePickerViewModelFactory

@Composable
fun PerAppExitNodePickerView(
    packageName: String,
    onBack: BackNavigation,
) {
  val model: PerAppExitNodePickerViewModel =
      viewModel(
          factory = PerAppExitNodePickerViewModelFactory(packageName, onSaved = onBack),
          key = "perAppPicker:$packageName")

  val currentSelection by model.currentSelection.collectAsState()
  val appLabel by model.appLabel.collectAsState()
  val exitNodes by model.exitNodes.collectAsState()

  Scaffold(
      topBar = { Header(title = { Text(appLabel) }, onBack = onBack) },
  ) { innerPadding ->
    LazyColumn(modifier = Modifier.padding(innerPadding)) {
      item("description") {
        ListItem(headlineContent = { Text(stringResource(R.string.per_app_exit_node_description)) })
      }

      item("useGlobal") {
        PickerRow(
            label = stringResource(R.string.per_app_use_global),
            selected = currentSelection == null,
            onClick = { model.save(null) },
        )
        Lists.ItemDivider()
      }

      item("noExit") {
        PickerRow(
            label = stringResource(R.string.per_app_no_exit_node),
            selected = currentSelection == PER_APP_NO_EXIT_NODE,
            onClick = { model.save(PER_APP_NO_EXIT_NODE) },
        )
        Lists.ItemDivider()
      }

      if (exitNodes.isNotEmpty()) {
        item("nodesHeader") {
          Lists.SectionDivider(stringResource(R.string.choose_exit_node))
        }
        items(exitNodes, key = { it.id }) { node ->
          PickerRow(
              label = node.label,
              selected = currentSelection == node.id,
              dim = !node.online && currentSelection != node.id,
              onClick = { model.save(node.id) },
          )
          Lists.ItemDivider()
        }
      }
    }
  }
}

@Composable
private fun PickerRow(
    label: String,
    selected: Boolean,
    dim: Boolean = false,
    onClick: () -> Unit,
) {
  ListItem(
      modifier = Modifier.clickable(onClick = onClick),
      headlineContent = {
        Row {
          Text(
              label,
              color =
                  if (dim) MaterialTheme.colorScheme.secondary
                  else MaterialTheme.colorScheme.onSurface,
              fontStyle = if (dim) FontStyle.Italic else FontStyle.Normal,
          )
        }
      },
      trailingContent = {
        if (selected) {
          Row {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "selected",
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(4.dp))
          }
        }
      },
  )
}
