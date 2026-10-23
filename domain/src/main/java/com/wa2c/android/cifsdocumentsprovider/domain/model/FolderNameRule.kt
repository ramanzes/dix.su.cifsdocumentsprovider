package com.wa2c.android.cifsdocumentsprovider.domain.model

/**
 * Names accepted for a new folder on the server (the WD My Cloud share name rule): 1 to
 * [MAX_LENGTH] Latin letters or digits, no spaces. Case is kept as typed - names that differ only
 * in case are different folders.
 */
object FolderNameRule {

    const val MAX_LENGTH = 32

    private val ALLOWED = Regex("[A-Za-z0-9]+")

    fun check(name: String): Problem? {
        return when {
            name.isEmpty() -> Problem.EMPTY
            name.length > MAX_LENGTH -> Problem.TOO_LONG
            !ALLOWED.matches(name) -> Problem.INVALID_CHARACTERS
            else -> null
        }
    }

    fun isValid(name: String): Boolean = check(name) == null

    enum class Problem {
        EMPTY,
        TOO_LONG,
        /** Spaces, punctuation or non-Latin letters */
        INVALID_CHARACTERS,
    }
}
