package site.leos.apps.lespas.helper

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import site.leos.apps.lespas.BuildConfig
import site.leos.apps.lespas.R
import site.leos.apps.lespas.settings.SettingsFragment
import site.leos.apps.lespas.sync.SyncAdapter
import java.io.File

class TransferStorageWorker(private val context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @SuppressLint("ApplySharedPref")
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val source: File
        val destination: File
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        val inInternal = sp.getBoolean(SettingsFragment.KEY_STORAGE_LOCATION, true)
        val isSyncEnabled = sp.getBoolean(context.getString(R.string.sync_pref_key), false)
        val message: String
        val accounts = AccountManager.get(context).getAccountsByType(context.getString(R.string.account_type_nc))

        context.getString(R.string.lespas_base_folder_name).apply {
            if (inInternal) {
                source = File(context.filesDir, this)
                destination = File(context.getExternalFilesDirs(null)[1], this)
                message = context.getString(R.string.transfer_to_external)
            } else {
                source = File(context.getExternalFilesDirs(null)[1], this)
                destination = File(context.filesDir, this)
                message = context.getString(R.string.transfer_to_internal)
            }
        }

        setForeground(createForegroundInfo(message))

        try {
            // Stop periodic sync during transferring
            if (isSyncEnabled) ContentResolver.removePeriodicSync(accounts[0], context.getString(R.string.sync_authority), Bundle.EMPTY)

            // Make destination folder
            destination.mkdir()

            source.listFiles()?.let {
                it.forEachIndexed { i, sFile ->
                    @Suppress("DEPRECATION")
                    Notification.Builder(context).setProgress(100, ((i + 1.0) / it.size * 100).toInt(), false).setSmallIcon(R.drawable.ic_notification).setContentTitle(message).setTicker(message).setOngoing(true).apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setChannelId(WORKER_NAME)
                        notificationManager.notify(NOTIFICATION_ID, build())
                    }

                    sFile.inputStream().use { input->
                        File("$destination/${sFile.name}").outputStream().use { output->
                            input.copyTo(output, 8192)
                        }
                    }
                }
            }

            // Update flag value in shared preference
            sp.edit().putBoolean(SettingsFragment.KEY_STORAGE_LOCATION, !inInternal).commit()

            // Remove source folder
            source.deleteRecursively()

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()

            try { destination.deleteRecursively() } catch (e: Exception) { e.printStackTrace() }
            Result.failure()
        } finally {
            // Restore periodic sync
            if (isSyncEnabled) {
                ContentResolver.setSyncAutomatically(accounts[0], context.getString(R.string.sync_authority), true)
                ContentResolver.addPeriodicSync(accounts[0], context.getString(R.string.sync_authority), Bundle().apply { putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_REMOTE_CHANGES) }, 6 * 3600L)
            }
        }
    }

    private fun createForegroundInfo(notificationTitle: String): ForegroundInfo = ForegroundInfo(NOTIFICATION_ID, createNotification(notificationTitle))

    private fun createNotification(title: String): Notification {
        @Suppress("DEPRECATION")
        val builder = Notification.Builder(context).setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setTicker(title).setProgress(100, 0, false).setOngoing(true)
/*
            // TODO cancel transfer worker
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_notification_clear_all),
                    context.getString(android.R.string.cancel),
                    WorkManager.getInstance(context).createCancelPendingIntent(UUID.fromString(SettingsFragment.TransferStorageDialog.TRANSFERRING_WORKER_NAME)))
                    .build()
            )
*/

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            NotificationChannel(WORKER_NAME, WORKER_NAME, NotificationManager.IMPORTANCE_LOW).also {
                notificationManager.createNotificationChannel(it)
                builder.setChannelId(it.id)
            }

        return builder.build()
    }

    companion object {
        const val WORKER_NAME = "${BuildConfig.APPLICATION_ID}.TRANSFER_WORKER"
        const val NOTIFICATION_ID = 8989
    }
}