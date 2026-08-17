package com.wa2c.android.cifsdocumentsprovider.common.utils

import com.wa2c.android.cifsdocumentsprovider.common.values.DIXSU_PROXY_PORT_RANGE_SIZE
import com.wa2c.android.cifsdocumentsprovider.common.values.DIXSU_PROXY_PORT_RANGE_START

/**
 * Deterministic local port for a connection's standalone dixsu proxy, stable across app/proxy
 * restarts (pure function of [connectionId] - doesn't require the proxy to actually be running).
 * The real bound port can differ by a few if this one was taken by something else when the
 * proxy last started (see the fallback logic in `DixsuProxyManager.bindServerSocket`), but that's
 * rare - safe to use as the address to display before/without confirming the proxy is live.
 */
fun dixsuProxyPreferredPort(connectionId: String): Int {
    return DIXSU_PROXY_PORT_RANGE_START + (connectionId.hashCode() and Int.MAX_VALUE) % DIXSU_PROXY_PORT_RANGE_SIZE
}
