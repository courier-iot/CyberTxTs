package com.cybertxts.app

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * App lock: a PIN (and optionally the phone's fingerprint / face unlock) before CyberTxTs opens.
 * The PIN is never stored, only a slow salted hash of it. Wrong guesses lock the keypad for longer each time.
 */
object AppLock {
    val locked = MutableStateFlow(false)
    /** Settings shown in the Security page (kept as state so the UI updates). */
    val enabled = MutableStateFlow(false)
    val biometric = MutableStateFlow(false)
    val afterSec = MutableStateFlow(0)          // 0 = lock as soon as you leave the app
    val privacyScreen = MutableStateFlow(true)  // hide content in the app switcher and block screenshots
    val hideAlerts = MutableStateFlow(false)    // notifications don't show who or what

    private lateinit var prefs: SharedPreferences
    @Volatile private var leftAt = 0L
    @Volatile private var holdUntil = 0L

    /** Call just before opening a picker or permission screen, so coming back from it doesn't lock the app. */
    fun holdOpen() { holdUntil = System.currentTimeMillis() + 120_000 }

    fun init(p: SharedPreferences) {
        prefs = p
        enabled.value = p.getString("lock_hash", null) != null
        biometric.value = p.getBoolean("lock_bio", false)
        afterSec.value = p.getInt("lock_after", 60)
        privacyScreen.value = p.getBoolean("lock_secure", true)
        hideAlerts.value = p.getBoolean("lock_hide", false)
        AppState.hideAlertText = hideAlerts.value
        if (enabled.value) locked.value = true            // cold start: always locked
    }

    private fun hash(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        return Crypto.sha256Hex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded)
    }

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString("lock_hash", hash(pin, salt)).putString("lock_salt", b64(salt))
            .putInt("lock_fails", 0).putLong("lock_until", 0L).apply()
        enabled.value = true
    }

    fun disable() {
        prefs.edit().remove("lock_hash").remove("lock_salt").remove("lock_fails").remove("lock_until").putBoolean("lock_bio", false).apply()
        enabled.value = false; biometric.value = false; locked.value = false
    }

    fun setBiometric(on: Boolean) { prefs.edit().putBoolean("lock_bio", on).apply(); biometric.value = on }
    fun setAfter(sec: Int) { prefs.edit().putInt("lock_after", sec).apply(); afterSec.value = sec }
    fun setPrivacyScreen(on: Boolean) { prefs.edit().putBoolean("lock_secure", on).apply(); privacyScreen.value = on }
    fun setHideAlerts(on: Boolean) {
        prefs.edit().putBoolean("lock_hide", on).apply(); hideAlerts.value = on
        AppState.hideAlertText = on
    }

    /** Milliseconds until the keypad accepts another try (0 = now). */
    fun waitMs(): Long = maxOf(0L, prefs.getLong("lock_until", 0L) - System.currentTimeMillis())

    /** Checks a PIN. Doesn't unlock by itself; the caller does that on true. */
    fun check(pin: String): Boolean {
        if (waitMs() > 0) return false
        val h = prefs.getString("lock_hash", null) ?: return true
        val salt = unb64(prefs.getString("lock_salt", "") ?: "") ?: return false
        val ok = MessageDigest.isEqual(hash(pin, salt).toByteArray(), h.toByteArray())
        if (ok) {
            prefs.edit().putInt("lock_fails", 0).putLong("lock_until", 0L).apply()
        } else {
            val fails = prefs.getInt("lock_fails", 0) + 1
            // 5 free tries, then 30 s, 1 min, 2 min ... up to an hour
            val wait = if (fails < 5) 0L else minOf(3_600_000L, 30_000L shl minOf(fails - 5, 7))
            prefs.edit().putInt("lock_fails", fails).putLong("lock_until", System.currentTimeMillis() + wait).apply()
        }
        return ok
    }

    fun failures(): Int = prefs.getInt("lock_fails", 0)

    fun unlock() { locked.value = false }

    fun onLeave() { leftAt = System.currentTimeMillis() }

    fun onReturn() {
        if (!enabled.value || locked.value) return
        if (System.currentTimeMillis() < holdUntil) { holdUntil = 0L; return }
        if (leftAt == 0L || System.currentTimeMillis() - leftAt >= afterSec.value * 1000L) locked.value = true
    }
}
