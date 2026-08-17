package com.wa2c.android.cifsdocumentsprovider.data.storage.apache

import com.wa2c.android.cifsdocumentsprovider.common.utils.dixsuProxyPreferredPort
import com.wa2c.android.cifsdocumentsprovider.common.utils.logD
import com.wa2c.android.cifsdocumentsprovider.common.utils.logE
import com.wa2c.android.cifsdocumentsprovider.common.values.CONNECTION_TIMEOUT
import com.wa2c.android.cifsdocumentsprovider.common.values.DIXSU_HOST_SUFFIX
import com.wa2c.android.cifsdocumentsprovider.common.values.DIXSU_PORT
import com.wa2c.android.cifsdocumentsprovider.common.values.DIXSU_PREAMBLE_PREFIX
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Keeps one local loopback forwarder per dixsu connection.
 *
 * A dixsu-tunnel connection is a plain TCP socket to `<slug>.dix.su:2222` that requires
 * a "DIXSU:<slug>\n" line to be sent before the SSH handshake starts. Neither the JSch
 * `Session` nor the Commons VFS SFTP provider exposes a way to inject that preamble on the
 * raw socket used for the SSH connection, so instead we terminate the SSH connection on a
 * local loopback port and relay bytes to/from the actual dix.su endpoint after writing the
 * preamble - the JSch/VFS layers just see a normal SFTP server at 127.0.0.1:<local port>.
 */
internal object DixsuTunnelManager {

    private val forwarders = ConcurrentHashMap<String, DixsuTunnelForwarder>()

    /**
     * Returns the local loopback port to connect to for the given connection, starting
     * (or restarting, if the slug changed) the forwarder as needed.
     */
    @Synchronized
    fun getLocalPort(connectionId: String, slug: String): Int {
        val existing = forwarders[connectionId]
        if (existing != null) {
            if (existing.slug == slug && !existing.isClosed) {
                return existing.localPort
            }
            existing.close()
        }
        return DixsuTunnelForwarder(slug).also {
            it.start()
            forwarders[connectionId] = it
        }.localPort
    }

    @Synchronized
    fun close(connectionId: String) {
        forwarders.remove(connectionId)?.close()
    }

    @Synchronized
    fun closeAll() {
        forwarders.values.forEach { it.close() }
        forwarders.clear()
    }
}

/**
 * Standalone dixsu-tunnel forwarders for third-party apps (e.g. backup/sync apps) to connect to
 * directly over plain SFTP at 127.0.0.1:<port>, bypassing this app's SAF/DocumentsProvider layer
 * entirely. Deliberately a separate registry from [DixsuTunnelManager]: that one is torn down
 * whenever a VFS client session closes (e.g. [ApacheVfsClient.close]), but a proxy started here
 * is meant to keep running in the background independent of any SAF browsing activity.
 */
object DixsuProxyManager {

    private val forwarders = ConcurrentHashMap<String, DixsuTunnelForwarder>()

    /**
     * Returns the local loopback port to connect to for the given connection, starting
     * (or restarting, if the slug changed) the forwarder as needed. Safe to call repeatedly.
     *
     * Unlike [DixsuTunnelManager], this binds to a port derived deterministically from
     * [connectionId] (falling back to nearby ports if taken) rather than an ephemeral one, so
     * that a third-party app (e.g. FolderSync) configured once with this address keeps working
     * across proxy restarts instead of silently pointing at a stale port.
     */
    @Synchronized
    fun start(connectionId: String, slug: String): Int {
        val existing = forwarders[connectionId]
        if (existing != null) {
            if (existing.slug == slug && !existing.isClosed) {
                return existing.localPort
            }
            existing.close()
        }
        return DixsuTunnelForwarder(slug, preferredPort = dixsuProxyPreferredPort(connectionId)).also {
            it.start()
            forwarders[connectionId] = it
        }.localPort
    }

    @Synchronized
    fun stop(connectionId: String) {
        forwarders.remove(connectionId)?.close()
    }
}

/**
 * Accepts local loopback connections and relays them to `<slug>.dix.su:2222`, sending the
 * "DIXSU:<slug>\n" preamble on the outbound socket before splicing bytes in both directions.
 */
private class DixsuTunnelForwarder(
    val slug: String,
    preferredPort: Int? = null,
) {
    private val serverSocket = bindServerSocket(preferredPort)
    private val closed = AtomicBoolean(false)

    val localPort: Int get() = serverSocket.localPort
    val isClosed: Boolean get() = closed.get()

    fun start() {
        thread(name = "dixsu-tunnel-accept-$slug", isDaemon = true) {
            while (!closed.get()) {
                val local = try {
                    serverSocket.accept()
                } catch (e: IOException) {
                    if (!closed.get()) logE(e)
                    break
                }
                thread(name = "dixsu-tunnel-relay-$slug", isDaemon = true) { relay(local) }
            }
        }
    }

    private fun relay(local: Socket) {
        var remote: Socket? = null
        try {
            remote = Socket()
            remote.connect(InetSocketAddress("$slug$DIXSU_HOST_SUFFIX", DIXSU_PORT), CONNECTION_TIMEOUT)
            remote.getOutputStream().apply {
                write("$DIXSU_PREAMBLE_PREFIX$slug\n".toByteArray(Charsets.UTF_8))
                flush()
            }

            val toRemote = thread(name = "dixsu-tunnel-up-$slug", isDaemon = true) {
                pipe(local.getInputStream(), remote.getOutputStream())
            }
            val toLocal = thread(name = "dixsu-tunnel-down-$slug", isDaemon = true) {
                pipe(remote.getInputStream(), local.getOutputStream())
            }
            toRemote.join()
            toLocal.join()
        } catch (e: IOException) {
            logE(e)
        } finally {
            try { remote?.close() } catch (e: IOException) { logD(e.toString()) }
            try { local.close() } catch (e: IOException) { logD(e.toString()) }
        }
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        try {
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (e: IOException) {
            logD(e.toString())
        } finally {
            try { output.close() } catch (e: IOException) { logD(e.toString()) }
        }
    }

    fun close() {
        if (closed.getAndSet(true)) return
        try { serverSocket.close() } catch (e: IOException) { logD(e.toString()) }
    }

    companion object {
        private const val LOOPBACK_ADDRESS = "127.0.0.1"
        private const val BUFFER_SIZE = 8192
        private const val PORT_FALLBACK_ATTEMPTS = 100

        /** Binds an ephemeral port, or [preferredPort] (falling back to nearby ports if taken). */
        private fun bindServerSocket(preferredPort: Int?): ServerSocket {
            val address = InetAddress.getByName(LOOPBACK_ADDRESS)
            if (preferredPort == null) return ServerSocket(0, 50, address)

            var lastError: IOException? = null
            for (offset in 0 until PORT_FALLBACK_ATTEMPTS) {
                try {
                    return ServerSocket(preferredPort + offset, 50, address)
                } catch (e: IOException) {
                    lastError = e
                }
            }
            throw lastError ?: IOException("Unable to bind proxy port")
        }
    }
}
