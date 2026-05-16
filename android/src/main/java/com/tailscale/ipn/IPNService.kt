// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.Process
import android.system.OsConstants
import android.util.LruCache
import java.net.InetAddress
import java.net.InetSocketAddress
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.util.TSLog
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import libtailscale.Libtailscale

open class IPNService : VpnService(), libtailscale.IPNService {
  private val TAG = "IPNService"
  private val randomID: String = UUID.randomUUID().toString()
  private lateinit var app: App
  val scope = CoroutineScope(Dispatchers.IO)
  private var closed = false

  override fun id(): String {
    return randomID
  }

  override fun updateVpnStatus(status: Boolean) {
    app.getAppScopedViewModel().setVpnActive(status)
  }

  override fun onCreate() {
    super.onCreate()
    // grab app to make sure it initializes
    app = App.get()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
      when (intent?.action) {
        ACTION_STOP_VPN -> {
          app.setWantRunning(false)
          close()
          START_NOT_STICKY
        }
        ACTION_RESTART_VPN -> {
          app.setWantRunning(false) {
            close()
            app.startVPN()
          }
          START_NOT_STICKY
        }
        ACTION_START_FOREGROUND_ONLY -> {
          // Start the foreground service notification without creating a VPN tunnel.
          // This is used during interactive login so that Android does not freeze the process
          // or restrict network access while the user completes auth in the browser.
          showForegroundNotification()
          START_NOT_STICKY
        }
        ACTION_START_VPN -> {
          showForegroundNotification()
          app.setWantRunning(true)
          Libtailscale.requestVPN(this)
          START_STICKY
        }
        "android.net.VpnService" -> {
          // This means we were started by Android due to Always On VPN.
          // We show a non-foreground notification because we weren't
          // started as a foreground service.
          scope.launch {
            // Collect the first value of hideDisconnectAction asynchronously.
            val hideDisconnectAction = MDMSettings.forceEnabled.flow.first()
            val exitNodeName =
                UninitializedApp.getExitNodeName(Notifier.prefs.value, Notifier.netmap.value)
            app.notifyStatus(true, hideDisconnectAction.value, exitNodeName)
          }
          app.setWantRunning(true)
          Libtailscale.requestVPN(this)
          START_STICKY
        }
        else -> {
          // This means that we were restarted after the service was killed
          // (potentially due to OOM).
          if (UninitializedApp.get().isAbleToStartVPN()) {
            showForegroundNotification()
            App.get()
            Libtailscale.requestVPN(this)
            START_STICKY
          } else {
            START_NOT_STICKY
          }
        }
      }

  override fun close() {
    if (closed) return
    closed = true
    Notifier.setState(Ipn.State.Stopping)
    disconnectVPN()
    Libtailscale.serviceDisconnect(this)
  }

  override fun disconnectVPN() {
    stopSelf()
  }

  override fun onDestroy() {
    close()
    updateVpnStatus(false)
    super.onDestroy()
  }

  override fun onRevoke() {
    // VPN permission was granted to another app, so tell the Go backend and then set prepared to be
    // false so that when user attempts to connect again, VpnService.prepare() is called
    app.setWantRunning(false)
    setVpnPrepared(false)
    close()
    updateVpnStatus(false)
    super.onRevoke()
  }

  private fun setVpnPrepared(isPrepared: Boolean) {
    app.getAppScopedViewModel().setVpnPrepared(isPrepared)
  }

  private fun showForegroundNotification(
      hideDisconnectAction: Boolean,
      exitNodeName: String? = null
  ) {
    try {
      startForeground(
          UninitializedApp.STATUS_NOTIFICATION_ID,
          UninitializedApp.get().buildStatusNotification(true, hideDisconnectAction, exitNodeName))
    } catch (e: Exception) {
      TSLog.e(TAG, "Failed to start foreground service: $e")
    }
  }

  private fun showForegroundNotification() {
    val hideDisconnectAction = MDMSettings.forceEnabled.flow.value.value
    val exitNodeName = UninitializedApp.getExitNodeName(Notifier.prefs.value, Notifier.netmap.value)
    showForegroundNotification(hideDisconnectAction, exitNodeName)
  }

  private fun configIntent(): PendingIntent {
    return PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }

  private fun allowApp(b: Builder, name: String) {
    try {
      b.addAllowedApplication(name)
    } catch (e: PackageManager.NameNotFoundException) {
      TSLog.e(TAG, "Failed to add allowed application: $e")
    }
  }

  private fun disallowApp(b: Builder, name: String) {
    try {
      b.addDisallowedApplication(name)
    } catch (e: PackageManager.NameNotFoundException) {
      TSLog.e(TAG, "Failed to add disallowed application: $e")
    }
  }

  override fun newBuilder(): VPNServiceBuilder {
    val b: Builder =
        Builder()
            .setConfigureIntent(configIntent())
            .allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      b.setMetered(false) // Inherit the metered status from the underlying networks.
    }
    b.setUnderlyingNetworks(null) // Use all available networks.

    val mdmAllowed =
        MDMSettings.includedPackages.flow.value.value?.split(",")?.map { it.trim() } ?: emptyList()
    val mdmDisallowed =
        MDMSettings.excludedPackages.flow.value.value?.split(",")?.map { it.trim() } ?: emptyList()

    var packagesList: List<String>
    var allowPackages: Boolean
    if (mdmAllowed.isNotEmpty()) {
      // An admin defined a list of packages that are exclusively allowed to be used via
      // Tailscale, so only allow those.
      packagesList = mdmAllowed
      allowPackages = true
      TSLog.d(TAG, "Included application packages were set via MDM: $mdmAllowed")
    } else if (mdmDisallowed.isNotEmpty()) {
      // An admin defined a list of packages that are excluded from accessing Tailscale,
      // so ignore user definitions and only exclude those
      packagesList = mdmDisallowed
      allowPackages = false
      TSLog.d(TAG, "Excluded application packages were set via MDM: $mdmDisallowed")
    } else {
      // Otherwise, prevent user manually disallowed apps from getting their traffic + DNS routed
      // via Tailscale
      packagesList = UninitializedApp.get().selectedPackageNames()
      allowPackages = UninitializedApp.get().allowSelectedPackages()
      TSLog.d(TAG, "Application packages were set by user: $packagesList")
    }

    if (allowPackages) {
      for (packageName in packagesList) {
        TSLog.d(TAG, "Including app: $packageName")
        allowApp(b, packageName)
      }
    } else {
      // Make sure to also exclude hard-coded apps that are known to cause issues
      packagesList += UninitializedApp.get().builtInDisallowedPackageNames

      for (packageName in packagesList) {
        TSLog.d(TAG, "Disallowing app: $packageName")
        disallowApp(b, packageName)
      }
    }

    return VPNServiceBuilder(b)
  }

  // Cache of 5-tuple → resolved package name. Active TCP/UDP flows produce
  // many packets with the same 5-tuple; resolving via
  // ConnectivityManager.getConnectionOwnerUid + PackageManager.getNameForUid
  // per packet would dominate the per-packet cost of the per-app exit-node
  // override. Size bounded to keep memory predictable on long-lived sessions.
  // Empty-string values are also cached to avoid re-querying flows that
  // can't be resolved (e.g. already closed by the time wireguard-go sees
  // the first packet, or owned by a UID without an installed package).
  private val flowOwnerCache = LruCache<String, String>(1024)

  override fun lookupPackageByFlow(
      protocol: Int,
      srcAddr: String,
      srcPort: Int,
      dstAddr: String,
      dstPort: Int
  ): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      // ConnectivityManager.getConnectionOwnerUid requires API 29.
      return ""
    }
    val key = "$protocol|$srcAddr|$srcPort|$dstAddr|$dstPort"
    flowOwnerCache.get(key)?.let { return it }

    val pkg =
        try {
          val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
          val local = InetSocketAddress(InetAddress.getByName(srcAddr), srcPort)
          val remote = InetSocketAddress(InetAddress.getByName(dstAddr), dstPort)
          val uid = cm.getConnectionOwnerUid(protocol, local, remote)
          if (uid == Process.INVALID_UID) {
            ""
          } else {
            resolvePackageNameForUid(uid)
          }
        } catch (e: Exception) {
          TSLog.d(TAG, "lookupPackageByFlow failed: $e")
          ""
        }
    flowOwnerCache.put(key, pkg)
    return pkg
  }

  // Returns a plain package name for the given UID. PackageManager.getNameForUid
  // returns a synthetic string like "org.mozilla.firefox.sharedID:10357" for
  // apps that share a Linux UID with another package (the legacy sharedUserId
  // manifest attribute, used by Firefox, some Samsung apps, etc.). That string
  // never matches the per-app exit-node map keys (which are real package
  // names), so we fall back to getPackagesForUid and pick a deterministic
  // entry. For multi-package shared UIDs the first sorted name is used, so a
  // per-app rule on any one package in the group implicitly applies to all of
  // them — acceptable since they share an Android-level identity.
  private fun resolvePackageNameForUid(uid: Int): String {
    val direct = packageManager.getNameForUid(uid) ?: ""
    if (direct.isNotEmpty() && !direct.contains(":")) return direct
    val pkgs = packageManager.getPackagesForUid(uid)
    if (pkgs.isNullOrEmpty()) return direct
    return pkgs.sorted().first()
  }

  companion object {
    const val ACTION_START_VPN = "com.tailscale.ipn.START_VPN"
    const val ACTION_STOP_VPN = "com.tailscale.ipn.STOP_VPN"
    const val ACTION_RESTART_VPN = "com.tailscale.ipn.RESTART_VPN"
    const val ACTION_START_FOREGROUND_ONLY = "com.tailscale.ipn.START_FOREGROUND_ONLY"
  }
}
