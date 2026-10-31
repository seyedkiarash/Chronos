package com.chronos.ui.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Your request of 31 Oct 2026: an optional lock on the app, and its screens kept out of screenshots
 * and the recent-apps view.
 *
 * The lock is the phone's own: a fingerprint or face if one is set up, else its PIN, pattern or
 * password. Nothing of it is stored by the app. It is asked when the app opens and when you come
 * back after more than a minute away.
 *
 * Screenshots are refused with the window's secure flag. While debugging, a computer connected by
 * adb may allow them for two hours ([CaptureReceiver]); no app on the phone can, because the
 * receiver only takes broadcasts from the adb shell.
 */
object Privacy {
    /** How long away before the lock is asked again. */
    const val AWAY_MS = 60_000L

    private const val ALLOWED = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** Until when screenshots are allowed for debugging (elapsed real time), 0 when not. */
    @Volatile var captureUntil = 0L

    /** Called when the debugging allowance changes, so the open window applies it. */
    @Volatile var onCaptureChange: (() -> Unit)? = null

    fun captureAllowed() = SystemClock.elapsedRealtime() < captureUntil

    /** Whether the phone has a lock the app can ask for; without one the app cannot be locked. */
    fun canLock(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(ALLOWED) == BiometricManager.BIOMETRIC_SUCCESS

    /** The secure flag on or off for a window: on when hiding is chosen and debugging has not allowed capture. */
    fun applySecure(activity: android.app.Activity, hide: Boolean) {
        if (hide && !captureAllowed()) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** Asks for the phone's lock; [done] hears whether it was given. */
    fun unlock(activity: FragmentActivity, title: String, subtitle: String, done: (Boolean) -> Unit) {
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done(true)
                override fun onAuthenticationError(code: Int, message: CharSequence) = done(false)
            })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(ALLOWED)
            .build())
    }
}

/**
 * Debugging only: `adb shell am broadcast -a com.chronos.ALLOW_CAPTURE -n <package>/com.chronos.ui.common.CaptureReceiver`
 * allows screenshots for two hours (`--ez on false` takes it back). Declared only in the debug manifest, and
 * guarded by the DUMP permission, which the adb shell holds and ordinary apps cannot get.
 */
class CaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val on = intent.getBooleanExtra("on", true)
        Privacy.captureUntil = if (on) SystemClock.elapsedRealtime() + 2 * 3_600_000L else 0L
        Privacy.onCaptureChange?.invoke()
    }
}
