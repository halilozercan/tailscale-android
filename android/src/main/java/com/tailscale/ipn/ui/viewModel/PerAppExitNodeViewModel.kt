// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.StableNodeID
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.InstalledApp
import com.tailscale.ipn.ui.util.InstalledAppsManager
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// Sentinel value representing "no exit node — drop this app's exit traffic"
// in the PerAppExitNode map. Maps to the zero StableNodeID on the Go side,
// which LocalBackend.lookupPerAppExitNodePeer interprets as "return ok=true
// with zero key, causing wireguard-go to drop the packet."
const val PER_APP_NO_EXIT_NODE: StableNodeID = ""

/** Display info for a candidate exit node in the per-app picker. */
data class PerAppExitNodeChoice(
    val id: StableNodeID,
    val label: String,
    val online: Boolean,
    val mullvad: Boolean,
)

/** ViewModel for the per-app exit-node apps list. */
class PerAppExitNodeListViewModel : ViewModel() {
  val installedAppsManager = InstalledAppsManager(packageManager = App.get().packageManager)

  val installedApps: StateFlow<List<InstalledApp>> =
      flow { emit(installedAppsManager.fetchInstalledApps()) }
          .flowOn(Dispatchers.IO)
          .stateIn(
              scope = viewModelScope,
              started = SharingStarted.WhileSubscribed(5000),
              initialValue = emptyList(),
          )

  /** The current PerAppExitNode map. Empty if no overrides are configured. */
  val perAppMap: StateFlow<Map<String, StableNodeID>> = MutableStateFlow(emptyMap())

  /** Map of StableNodeID → display label for any node referenced in perAppMap. */
  val exitNodeLabels: StateFlow<Map<StableNodeID, String>> = MutableStateFlow(emptyMap())

  init {
    viewModelScope.launch {
      Notifier.prefs.combine(Notifier.netmap) { prefs, netmap -> prefs to netmap }.collect {
          (prefs, netmap) ->
        perAppMap.set(prefs?.PerAppExitNode ?: emptyMap())
        val labels = mutableMapOf<StableNodeID, String>()
        netmap?.Peers?.forEach { peer ->
          if (peer.isExitNode) {
            labels[peer.StableID] = peer.displayName
          }
        }
        exitNodeLabels.set(labels)
      }
    }
  }
}

class PerAppExitNodePickerViewModelFactory(
    private val packageName: String,
    private val onSaved: () -> Unit,
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    return PerAppExitNodePickerViewModel(packageName, onSaved) as T
  }
}

/** ViewModel for the single-app exit-node picker. */
class PerAppExitNodePickerViewModel(
    val packageName: String,
    private val onSaved: () -> Unit,
) : IpnViewModel() {

  /**
   * Current selection state for this app:
   * - null → no override (uses global exit node)
   * - "" → drop exit-bound traffic ([PER_APP_NO_EXIT_NODE])
   * - any other StableNodeID → use that exit node
   */
  val currentSelection: StateFlow<StableNodeID?> = MutableStateFlow(null)

  /** Display name for the app being edited (loaded async from the package manager). */
  val appLabel: StateFlow<String> = MutableStateFlow(packageName)

  /** All exit nodes available to choose from, sorted (tailnet first, then mullvad). */
  val exitNodes: StateFlow<List<PerAppExitNodeChoice>> = MutableStateFlow(emptyList())

  init {
    viewModelScope.launch {
      val pm = App.get().packageManager
      val label =
          try {
            pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
          } catch (e: Exception) {
            packageName
          }
      appLabel.set(label)
    }

    viewModelScope.launch {
      Notifier.prefs.combine(Notifier.netmap) { prefs, netmap -> prefs to netmap }.collect {
          (prefs, netmap) ->
        currentSelection.set(prefs?.PerAppExitNode?.get(packageName))

        val candidates = mutableListOf<PerAppExitNodeChoice>()
        netmap?.Peers?.filter { it.isExitNode }?.forEach { peer ->
          val isMullvad = peer.Name.endsWith(".mullvad.ts.net.")
          candidates.add(
              PerAppExitNodeChoice(
                  id = peer.StableID,
                  label = peer.displayName,
                  online = peer.Online ?: false,
                  mullvad = isMullvad,
              ))
        }
        // Hide offline non-mullvad nodes? Keep them visible but show status, like ExitNodePicker.
        candidates.sortWith(
            compareBy<PerAppExitNodeChoice> { it.mullvad }.thenBy { it.label.lowercase() })
        exitNodes.set(candidates)
      }
    }
  }

  /**
   * Save the selection.
   * @param newSelection null = remove override (use global); empty string = drop exit traffic; otherwise = exit node ID.
   */
  fun save(newSelection: StableNodeID?) {
    val prefs = Notifier.prefs.value
    val current = prefs?.PerAppExitNode?.toMutableMap() ?: mutableMapOf()
    if (newSelection == null) {
      current.remove(packageName)
    } else {
      current[packageName] = newSelection
    }

    LoadingIndicator.start()
    val out = Ipn.MaskedPrefs()
    out.PerAppExitNode = current
    Client(viewModelScope).editPrefs(out) {
      LoadingIndicator.stop()
      onSaved()
    }
  }
}
