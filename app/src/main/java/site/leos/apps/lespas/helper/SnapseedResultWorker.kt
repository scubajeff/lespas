/*
 *   Copyright 2019 Jeffrey Liu (scubajeffrey@criptext.com)
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package site.leos.apps.lespas.helper

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import site.leos.apps.lespas.LespasDatabase
import site.leos.apps.lespas.R
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.sync.Action
import java.io.File

class SnapseedResultWorker(private val context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        var result = Result.failure()

        @Suppress("DEPRECATION")
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
        val appRootFolder = Tools.getLocalRoot(context)

        val cr = context.contentResolver
        var imagePath = ""
        var imageName = ""
        var imageSize = 0L
        val photoDao = LespasDatabase.getDatabase(context).photoDao()
        val albumDao = LespasDatabase.getDatabase(context).albumDao()
        val actionDao = LespasDatabase.getDatabase(context).actionDao()
        val uri = Uri.parse(inputData.keyValueMap[KEY_IMAGE_URI] as String)
        val originalPhoto = photoDao.getPhotoById(inputData.keyValueMap[KEY_SHARED_PHOTO] as String)
        val album = albumDao.getThisAlbum(inputData.keyValueMap[KEY_ALBUM] as String)
        val exifInterface: ExifInterface?

        withContext(Dispatchers.IO) {
            cr.query(uri, null, null, null, null)?.use { cursor ->
                cursor.moveToFirst()
                imagePath = cursor.getString(cursor.getColumnIndexOrThrow(pathColumn))
                imageName = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                imageSize = cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))
            }

            // Wait until Snapseed result file is synced to disk. Seems like system media store start scanning it even before the file is written completely and reporting 0 file size
            // TODO any other proper way to do this?
            while (imageSize == 0L) {
                delay(200)

                cr.query(uri, null, null, null, null)?.use { cursor ->
                    cursor.moveToFirst()
                    imageSize = cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))
                }
            }

            if (imagePath.contains("Snapseed/")) {
                // If this is under Snapseed's folder
                if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getString(R.string.snapseed_replace_pref_key), false)) {
                    /* Replace the original */

                    // Make a copy of this file after imageName, e.g. the photo name, so that when new eTag synced back from server, file will not need to be downloaded
                    // If we share this photo to Snapseed again, the share function will use this new name, then snapseed will append another "-01" to the result filename
                    try {
                        @Suppress("BlockingMethodInNonBlockingContext")
                        cr.openInputStream(uri)?.use { input ->
                            // Name new photo filename after Snapseed's output name
                            File(appRootFolder, imageName).outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        // Quit when exception happens during file copy
                        return@withContext
                    }

                    // Replace content of file name after photo ID too, since ImageLoaderViewModel recognize photo ID only
                    // We can't simply change this photo ID to imageName, since that will reorder the photo list
                    try {
                        @Suppress("BlockingMethodInNonBlockingContext")
                        File(appRootFolder, imageName).inputStream().use { input ->
                            File(appRootFolder, originalPhoto.id).outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }

                    } catch (e: Exception) {
                        e.printStackTrace()
                        // Quit when exception happens during file copy
                        return@withContext
                    }

                    // Update local database
                    @Suppress("BlockingMethodInNonBlockingContext")
                    exifInterface = try { ExifInterface("$appRootFolder/$imageName") } catch (e: Exception) { null }
                    val newPhoto = Tools.getPhotoParams(null, exifInterface, "$appRootFolder/$imageName", Photo.DEFAULT_MIMETYPE, imageName).copy(
                        //id = originalPhoto.id, albumId = album.id, name = imageName, eTag = originalPhoto.eTag, shareId = originalPhoto.shareId)
                        // Mark sync status by setting eTag to empty
                        //id = originalPhoto.id, albumId = album.id, name = imageName, eTag = Photo.ETAG_NOT_YET_UPLOADED, shareId = originalPhoto.shareId or Photo.NOT_YET_UPLOADED
                        id = originalPhoto.id, albumId = album.id, name = imageName, shareId = originalPhoto.shareId or Photo.NOT_YET_UPLOADED
                    )
                    photoDao.update(newPhoto)
                    if (album.cover == newPhoto.id) albumDao.fixCoverName(album.id, newPhoto.name)
                    // Invalid image cache to show new image and change CurrentPhotoModel's filename
                    //setProgress(workDataOf( KEY_INVALID_OLD_PHOTO_CACHE to true))

/*
                    // Remove file name after photo id, ImageLoaderViewModel will load file named after photo name instead
                    try { File(appRootFolder, originalPhoto.id).delete() } catch (e: Exception) { e.printStackTrace() }
*/

                    // When the photo being replaced has not being uploaded yet, remove file named after old photo name if any, e.g. only send the latest version
                    try { File(appRootFolder, originalPhoto.name).delete() } catch (e: Exception) {}


                    // Update server
                    with(mutableListOf<Action>()) {
                        // First rename file to new filename on server, then in next ACTION_ADD_FILES_ON_SERVER action, overwrite it with new edition. This way, file's fileId on server will not change
                        add(Action(null, Action.ACTION_RENAME_FILE, album.id, album.name, originalPhoto.name, newPhoto.name, System.currentTimeMillis(), 1))
                        add(Action(null, Action.ACTION_ADD_FILES_ON_SERVER, newPhoto.mimeType, album.name, newPhoto.id, newPhoto.name, System.currentTimeMillis(), album.shareId))
                        if (album.cover == newPhoto.id) add(Action(null, Action.ACTION_UPDATE_ALBUM_META, album.id, album.name, "", "", System.currentTimeMillis(), 1))
/*
                        // Replace the old file with new version, remove then add will force fileId changed, so that OkHttp cache for image preview will not stall
                        add(Action(null, Action.ACTION_DELETE_FILES_ON_SERVER, album.id, album.name, originalPhoto.id, originalPhoto.name, System.currentTimeMillis(), 1))
                        add(Action(null, Action.ACTION_ADD_FILES_ON_SERVER, newPhoto.mimeType, album.name, newPhoto.id, newPhoto.name, System.currentTimeMillis(), album.shareId))
*/

                        actionDao.insert(this)
                    }
                } else {
                    /* Copy Snapseed output */

                    // Append content uri _id as suffix to make a unique filename, this will be use as both fileId and filename
                    val fileName = "${imageName.substringBeforeLast('.')}_${uri.lastPathSegment!!}.${imageName.substringAfterLast('.')}"

                    // Copy file to our private storage area
                    try {
                        @Suppress("BlockingMethodInNonBlockingContext")
                        cr.openInputStream(uri)?.use { input ->
                            File(appRootFolder, fileName).outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        return@withContext
                    }

                    // Create new photo in local database
                    @Suppress("BlockingMethodInNonBlockingContext")
                    exifInterface = try { ExifInterface("$appRootFolder/$fileName") } catch (e: Exception) { null }
                    photoDao.insert(Tools.getPhotoParams(null, exifInterface, "$appRootFolder/$fileName", Photo.DEFAULT_MIMETYPE, fileName).copy(id = fileName, albumId = album.id, name = fileName, shareId = Photo.DEFAULT_PHOTO_FLAG or Photo.NOT_YET_UPLOADED))

                    with(mutableListOf<Action>()) {
                        // Upload changes to server, mimetype passed in folderId property, fileId is the same as fileName, reflecting what it's in local Room table
                        add(Action(null, Action.ACTION_ADD_FILES_ON_SERVER, Photo.DEFAULT_MIMETYPE, album.name, fileName, fileName, System.currentTimeMillis(), album.shareId))

                        actionDao.insert(this)
                    }
                }

                // Remove cache copy
                try { File(context.cacheDir, originalPhoto.name).delete() } catch (e: Exception) { e.printStackTrace() }

                // Remove snapseed output if running on Android Q or lower
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) try { cr.delete(uri, null, null) } catch (e: Exception) { e.printStackTrace() }

                result = Result.success()
            }
        }

        return result
    }

    companion object {
        const val KEY_IMAGE_URI = "IMAGE_URI"
        const val KEY_SHARED_PHOTO = "SHARE_PHOTO"
        const val KEY_ALBUM = "ALBUM"
        //const val KEY_INVALID_OLD_PHOTO_CACHE = "INVALID_OLD_PHOTO_CACHE"
        //const val KEY_PUBLISHED = "IS_ALBUM_PUBLISHED"
    }
}
