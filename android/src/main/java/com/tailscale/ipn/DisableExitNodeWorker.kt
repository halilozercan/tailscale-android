// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.tailscale.ipn.ui.localapi.Client
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

// Mirrors the UI toggle: clears activeExitNodeID via the enable-exit-node
// endpoint so selectedExitNodeID is preserved and the user can re-enable.
class DisableExitNodeWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {
  override suspend fun doWork(): Result {
    val scope = CoroutineScope(Dispatchers.Default + Job())
    var error: String? = null
    Client(scope).setUseExitNode(false) {
      error = if (it.isFailure) it.exceptionOrNull()?.message else null
    }
    scope.coroutineContext[Job]?.join()
    return if (error != null) {
      Result.failure(Data.Builder().putString("error", error).build())
    } else {
      Result.success()
    }
  }
}
