package site.leos.apps.lespas.helper

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import site.leos.apps.lespas.LespasDatabase
import site.leos.apps.lespas.R
import site.leos.apps.lespas.sync.Action
import java.io.File

class SnapseedResultWorker(private val context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {
    @Suppress("DEPRECATION")
    private val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
    private val appRootFolder = "${context.filesDir}${context.getString(R.string.lespas_base_folder_name)}"

    override suspend fun doWork(): Result {
        var imagePath = ""
        var imageName = ""
        val photoDao = LespasDatabase.getDatabase(context).photoDao()
        val albumDao = LespasDatabase.getDatabase(context).albumDao()
        val actionDao = LespasDatabase.getDatabase(context).actionDao()
        val uri = Uri.parse(inputData.keyValueMap[KEY_IMAGE_URI] as String)
        val sharedPhoto = photoDao.getPhotoById(inputData.keyValueMap[KEY_SHARED_PHOTO] as String)
        val album = albumDao.getAlbumById(inputData.keyValueMap[KEY_ALBUM] as String)
        val outputInvalidCache: Pair<String, Boolean>

        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            cursor.moveToFirst()
            imagePath = cursor.getString(cursor.getColumnIndexOrThrow(pathColumn))
            imageName = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
        if (imagePath.contains("Snapseed/")) {
            // If this is under Snapseed's folder
            if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getString(R.string.snapseed_replace_pref_key), false)) {
                /* Replace the original */

                // Copy new file to our private storage area
                try {
                    @Suppress("BlockingMethodInNonBlockingContext")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        // Name new photo filename after Snapseed's output name
                        File(appRootFolder, sharedPhoto.id).outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    // Quit when exception happens during file copy
                    return Result.failure()
                }
                // Make a copy of this file after imageName so that when new eTag synced back from server, SyncAdapter can use this to replace the file named after id, kind of stupid but...
                try {
                    @Suppress("BlockingMethodInNonBlockingContext")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        // Name new photo filename after Snapseed's output name
                        File(appRootFolder, imageName).outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    // Quit when exception happens during file copy
                    return Result.failure()
                }
                // Remove file named after old photo name if any
                try {
                    File(appRootFolder, sharedPhoto.name).delete()
                } catch (e: Exception) { e.printStackTrace() }

                // Update local database
                val newPhoto = with(imageName) {
                    Tools.getPhotoParams("$appRootFolder/$this", JPEG, this).copy(id = sharedPhoto.id, albumId = album.id, name = this, eTag = sharedPhoto.eTag, shareId = sharedPhoto.shareId)
                }
                photoDao.update(newPhoto)

                // Update server
                with(mutableListOf<Action>()) {
                    // Rename file to new filename on server
                    add(Action(null, Action.ACTION_RENAME_FILE, album.id, album.name, sharedPhoto.name, newPhoto.name, System.currentTimeMillis(), 1))
                    // Upload new photo to server. Photo mimeType passed in folderId property
                    add(Action(null, Action.ACTION_UPDATE_FILE, newPhoto.mimeType, album.name, newPhoto.id, newPhoto.name, System.currentTimeMillis(), 1))
                    //add(Action(null, Action.ACTION_DELETE_FILES_ON_SERVER, album.id, album.name, sharedPhoto.id, sharedPhoto.name, System.currentTimeMillis(), 1))
                    actionDao.insert(this)
                }

                // Invalid image cache to show new image
                outputInvalidCache = KEY_INVALID_OLD_PHOTO_CACHE to true
            }
            else {
                /* Copy Snapseed output */

                // Append content uri _id as suffix to make a unique filename
                val fileName = "${imageName.substringBeforeLast('.')}_${uri.lastPathSegment!!}.${imageName.substringAfterLast('.')}"

                // Copy file to our private storage area
                try {
                    @Suppress("BlockingMethodInNonBlockingContext")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        File(appRootFolder, fileName).outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    return Result.failure()
                }

                // Create new photo in local database
                photoDao.insert(Tools.getPhotoParams("$appRootFolder/$fileName", JPEG, fileName).copy(id = fileName, albumId = album.id, name = fileName))

                // Upload changes to server, mimetype passed in folderId property
                actionDao.insert(Action(null, Action.ACTION_ADD_FILES_ON_SERVER, JPEG, album.name, fileName, fileName, System.currentTimeMillis(), 1))

                // No need to invalid original image
                outputInvalidCache = KEY_INVALID_OLD_PHOTO_CACHE to false
            }

            // Remove cache copy
            try {
                File(context.cacheDir, sharedPhoto.name).delete()
            } catch (e: Exception) { e.printStackTrace() }

            // Remove snapseed output
            context.contentResolver.delete(uri, null, null)
            return Result.success(workDataOf(outputInvalidCache))
        }

        return Result.failure()
    }

    companion object {
        private const val JPEG = "image/jpeg"

        const val KEY_IMAGE_URI = "IMAGE_URI"
        const val KEY_SHARED_PHOTO = "SHARE_PHOTO"
        const val KEY_ALBUM = "ALBUM"
        const val KEY_INVALID_OLD_PHOTO_CACHE = "INVALID_OLD_PHOTO_CACHE"
    }
}
