package com.wa2c.android.cifsdocumentsprovider.presentation.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay

/**
 * Current time in milliseconds, updated every [intervalMillis] - for "N s ago" texts that must
 * keep counting while nothing else recomposes.
 */
@Composable
fun rememberNow(intervalMillis: Long = 1000): Long {
    return produceState(System.currentTimeMillis()) {
        while (true) {
            delay(intervalMillis)
            value = System.currentTimeMillis()
        }
    }.value
}
