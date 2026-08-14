package com.wa2c.android.cifsdocumentsprovider.data.storage.apache

import com.wa2c.android.cifsdocumentsprovider.common.values.CONNECTION_TIMEOUT
import com.wa2c.android.cifsdocumentsprovider.common.values.URI_START
import com.wa2c.android.cifsdocumentsprovider.data.storage.interfaces.StorageConnection
import com.wa2c.android.cifsdocumentsprovider.data.storage.interfaces.StorageRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.apache.commons.vfs2.FileSystemOptions
import org.apache.commons.vfs2.provider.sftp.BytesIdentityInfo
import org.apache.commons.vfs2.provider.sftp.SftpFileSystemConfigBuilder
import java.io.File
import java.time.Duration

class ApacheSftpClient(
    private val knownHostPath: String,
    private val onKeyRead: (String) -> ByteArray,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): ApacheVfsClient(dispatcher) {

    override fun applyOptions(options: FileSystemOptions, storageConnection: StorageConnection) {
        val sftpConnection = storageConnection as StorageConnection.Sftp

        SftpFileSystemConfigBuilder.getInstance().also { builder ->
            builder.setConnectTimeout(options, Duration.ofMillis(CONNECTION_TIMEOUT.toLong()))
            builder.setSessionTimeout(options, Duration.ofMillis(CONNECTION_TIMEOUT.toLong()))
            builder.setPreferredAuthentications(options, "publickey,password")
            builder.setFileNameEncoding(options, sftpConnection.encoding)
            builder.setUserDirIsRoot(options, false) // true occurs path mismatch
            // Known hosts
            if (storageConnection.ignoreKnownHosts) {
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
    }

}
