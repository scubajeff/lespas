package site.leos.apps.lespas.cameraroll

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.github.chrisbanes.photoview.PhotoView
import kotlinx.android.synthetic.main.activity_camera_roll.*
import kotlinx.coroutines.*
import site.leos.apps.lespas.R
import site.leos.apps.lespas.sync.AcquiringDialogFragment
import site.leos.apps.lespas.sync.DestinationDialogFragment
import site.leos.apps.lespas.sync.ShareReceiverActivity

class CameraRollActivity : AppCompatActivity() {
    private lateinit var controls: ConstraintLayout
    private lateinit var info: TextView
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_roll)
        progress = findViewById(R.id.progress_indicator)

        intent.data?.let { if (hasPermission(it)) showMedia(it) } ?: run { finish() }

        info = findViewById(R.id.info)
        controls = findViewById(R.id.controls)
        savedInstanceState?.apply { controls.visibility = this.getInt(CONTROLS_VISIBILITY) }

        // Can not reshare uri with scheme "file"
        if (intent.data!!.scheme == "file") share_button.isEnabled = false

        share_button.setOnClickListener {
            controls.visibility = View.GONE
            val uri = intent.data!!
            startActivity(
                Intent.createChooser(
                    Intent().apply {
                        action = Intent.ACTION_SEND
                        type = contentResolver.getType(uri)
                        putExtra(Intent.EXTRA_STREAM, uri)
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }, null
                )
            )
        }

        lespas_button.setOnClickListener {
            controls.visibility = View.GONE
            val destinationModel: DestinationDialogFragment.DestinationViewModel by viewModels()
            destinationModel.getDestination().observe (this, { album->
                // Acquire files
                if (supportFragmentManager.findFragmentByTag(ShareReceiverActivity.TAG_ACQUIRING_DIALOG) == null)
                    AcquiringDialogFragment.newInstance(arrayListOf(intent.data!!), album).show(supportFragmentManager, ShareReceiverActivity.TAG_ACQUIRING_DIALOG)
            })

            if (supportFragmentManager.findFragmentByTag(TAG_DESTINATION_DIALOG) == null)
                DestinationDialogFragment.newInstance().show(supportFragmentManager, TAG_DESTINATION_DIALOG)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(CONTROLS_VISIBILITY, controls.visibility)
    }

    private fun toggleControls() {
        controls.visibility = if (controls.visibility == View.GONE) View.VISIBLE else View.GONE
    }

    private fun getInfo(uri: Uri): String {
        var info = ""
        /*
        contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media.DATA), MediaStore.Images.Media._ID + "=?",
        arrayOf(DocumentsContract.getDocumentId(uri).split(":")[1]), null)?.apply {
            if (moveToFirst()) {
                info = getString(getColumnIndex(MediaStore.Images.Media.DATA)).substringAfterLast('/')
            }
            close()
        }
        */
        when(uri.scheme) {
            "content"-> {
                contentResolver.query(uri, null, null, null, null)?.use { cursor->
                    cursor.moveToFirst()
                    try {
                        info = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                    } catch(e: Exception) {e.printStackTrace()}

                }
            }
            "file"-> {
                uri.path?.let { info = it.substringAfterLast('/') }
            }
        }

        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeStream(contentResolver.openInputStream(uri), null, this)
        }

        info = "${info}\n${options.outWidth} × ${options.outHeight}"

        return info
    }

    private fun showMedia(uri: Uri) {
        // Show a waiting sign when it takes more than 350ms to load the media
        Handler(Looper.getMainLooper()).postDelayed(showWaitingSign, 350L)

        // Show some statistic first
        GlobalScope.launch(Dispatchers.Default) {
            val photoInfo = getInfo(uri)
            if (photoInfo.isNotEmpty()) withContext(Dispatchers.Main) { info.text = photoInfo }
        }

        GlobalScope.launch(Dispatchers.Default) {
            val mimeType = contentResolver.getType(uri)?.let { Intent.normalizeMimeType(it) } ?: run {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(uri.toString()).toLowerCase()) ?: "image/jpeg"
            }

            if (mimeType.startsWith("video/")) {
                decodeVideo(uri)
                return@launch
            }

            if (mimeType == "image/gif" || mimeType == "image/webp") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(contentResolver, uri))
                    if (drawable is AnimatedImageDrawable) {
                        withContext(Dispatchers.Main) {
                            val picture = layoutInflater.inflate(R.layout.viewpager_item_gif, media_container).findViewById<ImageView>(R.id.media)
                            progress.visibility = View.GONE
                            picture.setImageDrawable(drawable.apply { start() })
                            picture.setOnClickListener { toggleControls() }
                        }
                        return@launch
                    }
                }
            }

            val rotation = if (mimeType == "image/jpeg" || mimeType == "image/tiff") ExifInterface(contentResolver.openInputStream(uri)!!).rotationDegrees else 0
            var bitmap = BitmapFactory.decodeStream(contentResolver.openInputStream(uri)!!)
            if (rotation != 0) bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)

            val picture: PhotoView
            withContext(Dispatchers.Main) {
                picture = layoutInflater.inflate(R.layout.viewpager_item_photo, media_container).findViewById(R.id.media)
                progress.visibility = View.GONE
                picture.setImageBitmap(bitmap)
            }

            with(picture) {
                setOnPhotoTapListener { _, _, _ -> toggleControls() }
                setOnOutsidePhotoTapListener { toggleControls() }
                maximumScale = 5.0f
                mediumScale = 3f
            }
        }
    }

    private fun decodeVideo(uri: Uri) {
    }

    private val showWaitingSign = Runnable {
        progress.visibility = View.VISIBLE
    }

    private fun hasPermission(uri: Uri): Boolean {
        if (uri.scheme == "file") {
            if (ContextCompat.checkSelfPermission(baseContext, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), WRITE_STORAGE_PERMISSION_REQUEST)
                return false
            }
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == WRITE_STORAGE_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) showMedia(intent.data!!)
            else finish()
        }
    }

    companion object {
        private const val CONTROLS_VISIBILITY = "CONTROLS_VISIBILITY"
        private const val WRITE_STORAGE_PERMISSION_REQUEST = 6464
        const val TAG_DESTINATION_DIALOG = "CAMERAROLL_DESTINATION_DIALOG"
    }
}