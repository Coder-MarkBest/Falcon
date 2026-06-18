package com.falcon.ipc.core

import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Lazily collects an event Flow while >=1 subscriber is present (ref-counted). */
class EventCollector(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val counts = ConcurrentHashMap<String, Int>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val lock = Any()

    fun onSubscribe(eventKey: String, flowProvider: () -> Flow<Bundle>?, emit: (Bundle) -> Unit) {
        synchronized(lock) {
            val n = (counts[eventKey] ?: 0) + 1
            counts[eventKey] = n
            if (n == 1) {
                val flow = flowProvider() ?: run { counts.remove(eventKey); return }
                jobs[eventKey] = scope.launch { flow.collect { emit(it) } }
            }
        }
    }

    fun onUnsubscribe(eventKey: String) {
        synchronized(lock) {
            val n = (counts[eventKey] ?: 0) - 1
            if (n <= 0) {
                counts.remove(eventKey)
                jobs.remove(eventKey)?.cancel()
            } else counts[eventKey] = n
        }
    }

    fun isCollecting(eventKey: String): Boolean = jobs.containsKey(eventKey)
    fun shutdown() { scope.cancel() }
}
