package com.wa2c.android.cifsdocumentsprovider.domain.model

import android.app.AuthenticationRequiredException
import com.wa2c.android.cifsdocumentsprovider.common.exception.SendException
import com.wa2c.android.cifsdocumentsprovider.common.exception.StorageException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Why a file transfer failed.
 */
data class SendFailure(
    val reason: Reason,
    /** Message of the innermost cause, as reported by the system or the server */
    val detail: String?,
) {
    enum class Reason {
        /** The connection has the read-only option set */
        READ_ONLY,
        /** The server refused access */
        PERMISSION,
        /** No space left on the server */
        NO_SPACE,
        /** Login to the server failed */
        AUTH,
        /** Connection to the server lost or timed out */
        NETWORK,
        /** The local file could not be read */
        READ_SOURCE,
        /** The file could not be created on the server */
        CREATE_TARGET,
        /** The file could not be written on the server */
        WRITE_TARGET,
        OTHER,
    }
}

/**
 * Classifies a transfer exception: specific causes anywhere in the chain (read-only option,
 * permission, space, auth, network) first, then the step that failed.
 */
fun Throwable.toSendFailure(): SendFailure {
    val chain = generateSequence(this) { it.cause }.take(16).toList()
    val text = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
    val isSourceSide = chain.any { it is SendException.ReadSource }
    val reason = when {
        chain.any { it is StorageException.Operation.ReadOnly || it is StorageException.Operation.AccessMode } -> SendFailure.Reason.READ_ONLY
        isSourceSide -> SendFailure.Reason.READ_SOURCE
        "permission denied" in text || chain.any { it is SecurityException && it !is AuthenticationRequiredException } -> SendFailure.Reason.PERMISSION
        "no space" in text || "quota" in text || "disk full" in text -> SendFailure.Reason.NO_SPACE
        chain.any { it is StorageException.Security || it is AuthenticationRequiredException } || "auth fail" in text -> SendFailure.Reason.AUTH
        chain.any { it is StorageException.Transaction || it is SocketException || it is SocketTimeoutException || it is UnknownHostException }
                || "session is down" in text || "connection" in text && ("closed" in text || "reset" in text || "refused" in text) -> SendFailure.Reason.NETWORK
        chain.any { it is SendException.CreateTarget } -> SendFailure.Reason.CREATE_TARGET
        chain.any { it is SendException.WriteTarget } -> SendFailure.Reason.WRITE_TARGET
        else -> SendFailure.Reason.OTHER
    }
    // Our own wrappers only say which step failed - the reason already does that
    val detail = chain.lastOrNull { it !is SendException }?.let { it.localizedMessage ?: it.javaClass.simpleName }
    return SendFailure(reason, detail)
}
