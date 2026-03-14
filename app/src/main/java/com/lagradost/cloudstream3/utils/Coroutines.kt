@file:Suppress("unused")

package com.lagradost.cloudstream3.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coroutine helpers expected by many CloudStream extensions.
 */

fun CoroutineScope.ioSafe(block: suspend CoroutineScope.() -> Unit): Job {
    return launch(Dispatchers.IO) {
        try {
            block()
        } catch (_: Exception) {
        }
    }
}

fun ioSafe(block: suspend CoroutineScope.() -> Unit): Job {
    return CoroutineScope(Dispatchers.IO).launch {
        try {
            block()
        } catch (_: Exception) {
        }
    }
}

suspend fun <T> ioWork(block: suspend CoroutineScope.() -> T): T {
    return withContext(Dispatchers.IO) {
        block()
    }
}

fun main(block: suspend CoroutineScope.() -> Unit): Job {
    return CoroutineScope(Dispatchers.Main).launch {
        block()
    }
}
