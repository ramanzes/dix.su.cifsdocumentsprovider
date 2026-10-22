package com.wa2c.android.cifsdocumentsprovider.common.exception

import java.io.IOException

/**
 * Failure of a file transfer, by the step that failed.
 */
sealed class SendException(message: String, cause: Throwable?) : IOException(message, cause) {
    /** The local file could not be opened or read */
    class ReadSource(cause: Throwable? = null) : SendException("Cannot read the source file.", cause)
    /** The target file could not be created */
    class CreateTarget(cause: Throwable? = null) : SendException("Cannot create the target file.", cause)
    /** The target file could not be opened or written */
    class WriteTarget(cause: Throwable? = null) : SendException("Cannot write the target file.", cause)
}
