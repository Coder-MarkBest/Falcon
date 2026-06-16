package com.falcon.ipc.runtime

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Launches a coroutine bound to the lifecycle owner's lifecycleScope.
 * The coroutine is automatically cancelled when the lifecycle is destroyed.
 *
 * Usage in Activity/Fragment:
 * ```
 * ipcScope {
 *     val location = navService.getCurrentLocation()
 *     navService.onLocationChanged().collect { updateMap(it) }
 * }
 * ```
 */
fun LifecycleOwner.ipcScope(
    block: suspend CoroutineScope.() -> Unit
) {
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            block()
        }
    }
}

/**
 * Fire-and-forget IPC scope that runs immediately (not tied to STARTED state).
 * Automatically cancelled when lifecycle is destroyed.
 */
fun LifecycleOwner.ipcFire(
    block: suspend CoroutineScope.() -> Unit
) {
    lifecycleScope.launch {
        block()
    }
}
