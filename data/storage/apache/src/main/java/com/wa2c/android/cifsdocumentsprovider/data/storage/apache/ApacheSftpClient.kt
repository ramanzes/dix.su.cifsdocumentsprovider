package com.wa2c.android.cifsdocumentsprovider.data.storage.apache

import com.jcraft.jsch.ConfigRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Logger
import com.wa2c.android.cifsdocumentsprovider.common.values.CONNECTION_TIMEOUT
import com.wa2c.android.cifsdocumentsprovider.common.values.URI_START
import com.wa2c.android.cifsdocumentsprovider.data.storage.interfaces.StorageConnection
import com.wa2c.android.cifsdocumentsprovider.data.storage.interfaces.StorageRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.vfs2.FileSystemOptions
import org.apache.commons.vfs2.provider.sftp.BytesIdentityInfo
import org.apache.commons.vfs2.provider.sftp.SftpFileSystemConfigBuilder
import java.io.File
import java.time.Duration

class ApacheSftpClient(
    private val knownHostPath: String,
    private val onKeyRead: (String) -> ByteArray,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
): ApacheVfsClient(dispatcher) {

    override fun applyOptions(options: FileSystemOptions, storageConnection: StorageConnection) {
        val sftpConnection = storageConnection as StorageConnection.Sftp

        SftpFileSystemConfigBuilder.getInstance().also { builder ->
            builder.setConnectTimeout(options, Duration.ofMillis(CONNECTION_TIMEOUT.toLong()))
            builder.setSessionTimeout(options, Duration.ofMillis(CONNECTION_TIMEOUT.toLong()))
            // JSch sends a keepalive each time the socket read times out (the session timeout above)
            // and disconnects after ServerAliveCountMax unanswered ones - keeps NAT/tunnel from
            // silently dropping idle connections. ServerAliveInterval is not set: JSch takes it in
            // milliseconds and VFS overrides the resulting socket timeout with the session timeout anyway.
            builder.setConfigRepository(options, KeepAliveConfigRepository)
            builder.setPreferredAuthentications(options, "publickey,password")
            builder.setFileNameEncoding(options, sftpConnection.encoding)
            builder.setUserDirIsRoot(options, false) // true occurs path mismatch
            // Falls back to no compression if the server does not offer it
            if (sftpConnection.compressionEnabled) {
                builder.setCompression(options, COMPRESSION_PROPOSAL)
            }
            // Known hosts - dixsu tunnel connections always go through the local loopback
            // forwarder, never the real host, so known-hosts checking can't meaningfully apply
            // regardless of what ignoreKnownHosts happens to be set to on the connection.
            if (sftpConnection.ignoreKnownHosts || sftpConnection.isDixsuTunnel) {
                builder.setStrictHostKeyChecking(options, "no")
            } else {
                builder.setStrictHostKeyChecking(options, "ask")
                builder.setKnownHosts(options, File(knownHostPath))
            }
            // Key
            (sftpConnection.keyData?.encodeToByteArray() ?: sftpConnection.keyFileUri?.let { uri ->
                try { onKeyRead(uri) } catch (e: Exception) { null }
            })?.let { keyBinary ->
                val identity = BytesIdentityInfo(keyBinary, sftpConnection.keyPassphrase?.encodeToByteArray())
                builder.setIdentityProvider(options, identity)
            }
        }
    }

    /**
     * Ask the server for compression without logging in: the algorithms are agreed on before
     * authentication, and JSch logs the result. No key or password is sent.
     * @return true if the server agreed to compress, false if it offers no compression,
     * null if the server could not be reached.
     */
    suspend fun checkCompression(connection: StorageConnection.Sftp): Boolean? = withContext(dispatcher) {
        val (host, port) = if (connection.isDixsuTunnel && connection.dixsuSlug.isNotBlank()) {
            LOOPBACK_HOST to DixsuTunnelManager.getLocalPort(connection.id, connection.dixsuSlug)
        } else {
            connection.host to (connection.port?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_SFTP_PORT)
        }
        var negotiated: String? = null
        val session = JSch().getSession(connection.user.orEmpty(), host, port).apply {
            setConfig("StrictHostKeyChecking", "no")
            setConfig("compression.s2c", COMPRESSION_PROPOSAL)
            setConfig("compression.c2s", COMPRESSION_PROPOSAL)
            // Stop at the method list the server answers to the "none" request
            setConfig("PreferredAuthentications", "none")
            setLogger(object : Logger {
                override fun isEnabled(level: Int): Boolean = level == Logger.INFO
                override fun log(level: Int, message: String) {
                    if (message.startsWith("kex: server->client")) {
                        negotiated = message.substringAfter(" compression: ", "").trim()
                    }
                }
            })
        }
        try {
            session.connect(CONNECTION_TIMEOUT)
        } catch (e: Exception) {
            // Expected: authentication is refused after the algorithms are agreed on
        } finally {
            session.disconnect()
        }
        negotiated?.let { it.isNotEmpty() && it != "none" }
    }

    override fun resolveUri(request: StorageRequest): String {
        val sftpConnection = request.connection as StorageConnection.Sftp
        val slug = sftpConnection.dixsuSlug
        if (!sftpConnection.isDixsuTunnel || slug.isBlank()) return request.uri

        val localPort = DixsuTunnelManager.getLocalPort(sftpConnection.id, slug)
        val tunnelAuthority = "127.0.0.1:$localPort"
        return request.uri.replaceFirst("$URI_START${originalAuthority(sftpConnection)}", "$URI_START$tunnelAuthority")
    }

    override fun restoreUri(url: String, connection: StorageConnection): String {
        val sftpConnection = connection as? StorageConnection.Sftp ?: return url
        if (!sftpConnection.isDixsuTunnel || sftpConnection.dixsuSlug.isBlank()) return url
        return url.replaceFirst(LOOPBACK_AUTHORITY_REGEX, "$URI_START${originalAuthority(sftpConnection)}")
    }

    override fun onClose() {
        DixsuTunnelManager.closeAll()
    }

    /**
     * Authority (host[:port]) produced by getUriText() from this connection's host/port, i.e.
     * what [resolveUri] replaces and [restoreUri] must put back.
     */
    private fun originalAuthority(connection: StorageConnection.Sftp): String {
        val portInt = connection.port?.toIntOrNull()
        return connection.host + if (portInt == null || portInt <= 0) "" else ":${connection.port}"
    }

    companion object {
        private val LOOPBACK_AUTHORITY_REGEX = Regex("""://127\.0\.0\.1:\d+""")
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val DEFAULT_SFTP_PORT = 22
        /** zlib after login (OpenSSH), plain zlib, else uncompressed */
        private const val COMPRESSION_PROPOSAL = "zlib@openssh.com,zlib,none"
    }

}

private object KeepAliveConfigRepository : ConfigRepository {
    override fun getConfig(host: String): ConfigRepository.Config = KeepAliveConfig

    private object KeepAliveConfig : ConfigRepository.Config {
        // null / -1 = keep the host, user and port passed to getSession() (a non-null hostname replaces it)
        override fun getHostname(): String? = null
        override fun getUser(): String? = null
        override fun getPort(): Int = -1
        override fun getValue(key: String): String? = when (key) {
            "ServerAliveCountMax" -> "3"
            else -> null
        }
        override fun getValues(key: String): Array<String>? = null
    }
}
