package dev.daily.todo

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

fun answerLength(text: String): Int = text.codePoints().filter { cp ->
    !Character.isWhitespace(cp) && !Character.isSpaceChar(cp) && !Character.isISOControl(cp) &&
        Character.getType(cp) != Character.FORMAT.toInt()
}.count().toInt()

internal const val MIN_ANSWER_CHARS = 15

internal fun canSubmitAnswer(text: String): Boolean = answerLength(text) >= MIN_ANSWER_CHARS

internal fun isRuleReady(enabled: Boolean, cooldownSeconds: Int, lastSubmittedAt: Long?, now: Long): Boolean =
    enabled && (lastSubmittedAt == null || cooldownSeconds == 0 || now >= lastSubmittedAt + cooldownSeconds * 1000L)

enum class EntryPolicy { EVERY_ENTRY, FIRST_DAILY }

data class AppSettings(
    val dailyQuestion: String = "今天你准备完成什么？为什么这件事值得做？",
    val entryPolicy: EntryPolicy = EntryPolicy.EVERY_ENTRY,
    val hasPin: Boolean = false, val guardPausedUntil: Long = 0, val allPausedUntil: Long = 0
) {
    fun askingAt(now: Long) = hasPin && now >= allPausedUntil
    fun guardingAt(now: Long) = askingAt(now) && now >= guardPausedUntil
}

internal object PinHash {
    const val iterations = 210_000
    fun newSalt(): String = Base64.getEncoder().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))
    private fun derive(pin: String, salt: String, rounds: Int): ByteArray {
        val chars = pin.toCharArray()
        val spec = PBEKeySpec(chars, Base64.getDecoder().decode(salt), rounds, 256)
        chars.fill('\u0000')
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
    fun encode(pin: String, salt: String) = Base64.getEncoder().encodeToString(derive(pin, salt, iterations))
    fun matches(pin: String, salt: String, rounds: Int, hash: String): Boolean =
        MessageDigest.isEqual(derive(pin, salt, rounds), Base64.getDecoder().decode(hash))
}
