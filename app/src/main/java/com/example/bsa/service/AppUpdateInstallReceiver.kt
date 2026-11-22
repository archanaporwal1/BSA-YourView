package com.example.bsa.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

sealed interface InAppInstallSessionEvent {
    data class PendingUserAction(val confirmIntent: Intent) : InAppInstallSessionEvent
    data object Succeeded : InAppInstallSessionEvent
    data class Failed(val message: String, val userCancelled: Boolean = false) : InAppInstallSessionEvent
}

/**
 * Process-local event bus connecting PackageInstaller session callbacks directly back to BsaViewModel
 * so the in-app updater dialog reflects real-time installation status and handles user confirmation cleanly.
 */
object InAppUpdateSessionBus {
    private val _events = MutableSharedFlow<InAppInstallSessionEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<InAppInstallSessionEvent> = _events.asSharedFlow()

    fun emit(event: InAppInstallSessionEvent) {
        _events.tryEmit(event)
    }
}

/**
 * Internal non-exported BroadcastReceiver that receives PackageInstaller.Session commit results
 * for 100% in-app APK installation without redirecting to a browser or file manager.
 */
class InAppInstallSessionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val statusMsg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = extractParcelableIntent(intent, Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    InAppUpdateSessionBus.emit(InAppInstallSessionEvent.PendingUserAction(confirmIntent))
                    runCatching {
                        context.startActivity(confirmIntent)
                    }.onFailure { err ->
                        Log.w(TAG, "Unable to launch package installer confirmation directly: ${err.message}")
                    }
                } else {
                    InAppUpdateSessionBus.emit(
                        InAppInstallSessionEvent.Failed("System installer confirmation intent was missing.")
                    )
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                cleanupCachedUpdateApks(context)
                markJustUpdated(context)
                InAppUpdateSessionBus.emit(InAppInstallSessionEvent.Succeeded)
                reopenApp(context)
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                InAppUpdateSessionBus.emit(
                    InAppInstallSessionEvent.Failed(
                        message = "Installation was cancelled.",
                        userCancelled = true
                    )
                )
            }

            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                InAppUpdateSessionBus.emit(
                    InAppInstallSessionEvent.Failed(
                        "Signature mismatch with installed version. Uninstall old build or install matching signed release."
                    )
                )
            }

            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                InAppUpdateSessionBus.emit(
                    InAppInstallSessionEvent.Failed("Insufficient device storage to install update.")
                )
            }

            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                InAppUpdateSessionBus.emit(
                    InAppInstallSessionEvent.Failed("Downloaded APK is incompatible with this Android device.")
                )
            }

            else -> {
                val detail = statusMsg.ifBlank { "Package installer returned status code $status." }
                InAppUpdateSessionBus.emit(
                    InAppInstallSessionEvent.Failed("In-app installation failed: $detail")
                )
            }
        }
    }

    companion object {
        private const val TAG = "InAppInstallReceiver"
        const val ACTION_INSTALL_STATUS = "com.example.bsa.ACTION_IN_APP_UPDATE_INSTALL_STATUS"
        private const val PREFS_NAME = "bsa_in_app_update_prefs"
        private const val KEY_UPDATED_VERSION = "just_updated_version_display"

        @Suppress("DEPRECATION")
        private fun extractParcelableIntent(source: Intent, key: String): Intent? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                source.getParcelableExtra(key, Intent::class.java)
            } else {
                source.getParcelableExtra(key)
            }
        }

        internal fun cleanupCachedUpdateApks(context: Context) {
            runCatching {
                val updatesDir = File(context.cacheDir, "updates")
                if (updatesDir.exists()) {
                    updatesDir.listFiles()?.forEach { if (it.isFile) it.delete() }
                }
            }
        }

        internal fun markJustUpdated(context: Context) {
            runCatching {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_UPDATED_VERSION, "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})")
                    .apply()
            }
        }

        internal fun consumeJustUpdatedVersion(context: Context): String? {
            return runCatching {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val value = prefs.getString(KEY_UPDATED_VERSION, null)
                if (!value.isNullOrBlank()) {
                    prefs.edit().remove(KEY_UPDATED_VERSION).apply()
                    value
                } else {
                    null
                }
            }.getOrNull()
        }

        internal fun reopenApp(context: Context) {
            runCatching {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    context.startActivity(launchIntent)
                }
            }.onFailure { err ->
                Log.w(TAG, "Auto-reopen after update failed: ${err.message}")
            }
        }
    }
}

/**
 * System BroadcastReceiver triggered by Android (`ACTION_MY_PACKAGE_REPLACED`) immediately after
 * BSA-YourView finishes updating itself in-app: cleans cached APK files, records the fresh version,
 * and automatically reopens the updated app.
 */
class AppUpdateCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        InAppInstallSessionReceiver.cleanupCachedUpdateApks(context)
        InAppInstallSessionReceiver.markJustUpdated(context)
        InAppInstallSessionReceiver.reopenApp(context)
    }
}
