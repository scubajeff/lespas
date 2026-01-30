/*
 *   Copyright 2019 Jeffrey Liu (scubajeffrey@protonmail.ch)
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

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.net.toUri
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

class MediaEditResultWorker(private val context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        var result = Result.failure()

        val uri = (inputData.keyValueMap[KEY_MEDIA_URI] as String).toUri()
        val replaceOriginal = inputData.keyValueMap[KEY_REPLACE_ORIGINAL] as Boolean

        val cr = context.contentResolver
        val appRootFolder = Tools.getLocalRoot(context)

        //val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
        //var mediaPath = ""
        var mediaName = ""
        var mediaSize = 0L
        var mediaNewId = ""

        val photoDao = LespasDatabase.getDatabase(context).photoDao()
        val albumDao = LespasDatabase.getDatabase(context).albumDao()
        val actionDao = LespasDatabase.getDatabase(context).actionDao()
        val album = albumDao.getThisAlbum(inputData.keyValueMap[KEY_ALBUM] as String)

        if ((inputData.keyValueMap[KEY_SHARED_MEDIA] as String).isNotEmpty()) {
            val originalPhoto = photoDao.getPhotoById(inputData.keyValueMap[KEY_SHARED_MEDIA] as String)
            withContext(Dispatchers.IO) {
                cr.query(uri, null, null, null, null)?.use { cursor ->
                    cursor.moveToFirst()
                    //mediaPath = cursor.getString(cursor.getColumnIndexOrThrow(pathColumn))
                    mediaName = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    mediaSize = cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))
                } ?: run { return@withContext }

                // Wait until editing result file is synced to disk. Seems like system media store start scanning it even before the file is written completely and reporting 0 file size.
                // TODO in the latest implementation, this worker will start only when Les Pas app is in resume state, maybe this is not necessary
                while (mediaSize == 0L) {
                    delay(1000)

                    cr.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) mediaSize = cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))
                        else return@withContext
                    } ?: run { return@withContext }
                }

                // TODO need to verify source of retouching app?
                //if (imagePath.contains("Snapseed/") || imagePath.contains("Photoshop Express/"))

                // Setup new name for the media
                if (replaceOriginal) {
                    // Avoid name conflict with original, some retouching apps are really lazy
                    // Retouching output will be copied to private space with this new name, so that when new eTag synced back from server, file will not need to be downloaded again
                    if (mediaName == originalPhoto.name) mediaName = mediaName.substringBeforeLast('.') + "_01." + mediaName.substringAfterLast('.')

                    // When replacing original media, reuse it's old id
                    mediaNewId = originalPhoto.id
                } else {
                    // When adding retouching result to album, in order to get a unique filename, append media store media id to the filename
                    mediaName = "${mediaName.substringBeforeLast('.')}_${uri.lastPathSegment!!}.${mediaName.substringAfterLast('.')}"

                    // Newly added media should use filename as it's temporary id
                    mediaNewId = mediaName
                }

                try {
                    cr.openInputStream(uri)?.use { input ->
                        File(appRootFolder, mediaName).outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    // Quit when exception happens during file copy
                    return@withContext
                }

                prepareNewPhoto(uri, mediaName, appRootFolder, cr, album.id, mediaNewId, originalPhoto).let { newPhoto ->
                    // Meta data retrieving failed, only happen in video meta data extracting
                    if (newPhoto.width == 0 || newPhoto.height == 0) return@withContext

                    if (replaceOriginal) {
                        // Update local DB, result will be shown immediately, take care album cover too
                        photoDao.update(newPhoto)
                        if (album.cover == newPhoto.id) albumDao.fixCoverName(album.id, newPhoto.name)

                        // Remove file name after photo id, ImageLoaderViewModel will load file named after photo name instead
                        //try { File(appRootFolder, originalPhoto.id).delete() } catch (e: Exception) { e.printStackTrace() }

                        // When the photo being replaced has not being uploaded yet, remove file named after old photo name if any, e.g. only send the latest version
                        try { File(appRootFolder, originalPhoto.name).delete() } catch (_: Exception) {}

                        // Update server
                        with(mutableListOf<Action>()) {
                            // First rename file to new filename on server, then in next ACTION_ADD_FILES_ON_SERVER action, overwrite it with new edition. This way, file's fileId on server will not change
                            add(Action(null, Action.ACTION_RENAME_FILE, album.id, album.name, originalPhoto.name, newPhoto.name, System.currentTimeMillis(), 1))
                            add(Action(null, Action.ACTION_ADD_FILES_ON_SERVER, newPhoto.mimeType, album.name, newPhoto.id, newPhoto.name, System.currentTimeMillis(), album.shareId))
                            if (album.cover == newPhoto.id) add(Action(null, Action.ACTION_UPDATE_ALBUM_META, album.id, album.name, "", "", System.currentTimeMillis(), 1))

                            // Another implementation: replace the old file with new version, remove then add will force fileId changed, so that OkHttp cache for image preview will not stall
                            //add(Action(null, Action.ACTION_DELETE_FILES_ON_SERVER, album.id, album.name, originalPhoto.id, originalPhoto.name, System.currentTimeMillis(), 1))
                            //add(Action(null, Action.ACTION_ADD_FILES_ON_SERVER, newPhoto.mimeType, album.name, newPhoto.id, newPhoto.name, System.currentTimeMillis(), album.shareId))

                            actionDao.insert(this)
                        }
                    } else {
                        // Create new photo in local database
                        photoDao.insert(newPhoto)


                        with(mutableListOf<Action>()) {
                            // Upload changes to server, mimetype passed in folderId property, fileId is the same as fileName, reflecting what it's in local Room table
                            add(Action(null, Action.ACTION_ADD_FILES_ON_SERVER, Photo.DEFAULT_MIMETYPE, album.name, mediaName, mediaName, System.currentTimeMillis(), album.shareId))

                            actionDao.insert(this)
                        }
                    }
                }

                // Remove cache copy
                try { File(context.cacheDir, originalPhoto.name).delete() } catch (_: Exception) {}

                // Remove editing output here if running on Android 10 or lower
                // If on Android 12 or above, handle it in AlbumDetailFragment or PhotoSlideFragment where ActivityResultLauncher is available.
                // There is no way to delete the file in Android 11 without user intervention
                if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getString(R.string.remove_editor_output_pref_key), false) && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) try { cr.delete(uri, null, null) } catch (_: Exception) {}

                //result = Result.success(workDataOf(KEY_IMAGE_URI to (inputData.keyValueMap[KEY_IMAGE_URI] as String)))
                result = Result.success()
            }
        }

        return result
    }

    private fun prepareNewPhoto(mediaUri: Uri, mediaName: String, localRoot: String, cr: ContentResolver, albumId: String, photoId: String, originalPhoto: Photo): Photo {
        var exifInterface: ExifInterface? = null
        var metadataRetriever: MediaMetadataRetriever? = null
        var mediaMimeType: String = Photo.DEFAULT_MIMETYPE

        cr.getType(mediaUri)?.let { mimeType ->
            mediaMimeType = mimeType
            if (mimeType.startsWith("video/")) metadataRetriever = try { MediaMetadataRetriever().apply { setDataSource(context, mediaUri) }} catch (_: SecurityException) { null } catch (_: RuntimeException) { null }
            else exifInterface = try { ExifInterface("$localRoot/$mediaName") } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
        }

        val newPhoto: Photo = Tools.getPhotoParams(metadataRetriever, exifInterface, "$localRoot/$mediaName", mediaMimeType, mediaName).copy(
            id = photoId, albumId = albumId, name = mediaName,
            // Preserve original meta
            dateTaken = originalPhoto.dateTaken,
            caption = originalPhoto.caption,
            latitude = originalPhoto.latitude,
            longitude = originalPhoto.longitude,
            altitude = originalPhoto.altitude,
            bearing = originalPhoto.bearing,
            locality = originalPhoto.locality,
            country = originalPhoto.country,
            countryCode = originalPhoto.countryCode,
        )

        metadataRetriever?.release()

        return newPhoto
    }

    companion object {
        const val KEY_MEDIA_URI = "MEDIA_URI"
        const val KEY_SHARED_MEDIA = "SHARE_MEDIA"
        const val KEY_ALBUM = "ALBUM"
        const val KEY_REPLACE_ORIGINAL = "KEY_REPLACE_ORIGINAL"
    }
}