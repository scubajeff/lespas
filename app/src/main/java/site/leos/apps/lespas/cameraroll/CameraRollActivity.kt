package site.leos.apps.lespas.cameraroll

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import kotlinx.android.synthetic.main.activity_camera_roll.*
import site.leos.apps.lespas.R
import site.leos.apps.lespas.sync.AcquiringDialogFragment
import site.leos.apps.lespas.sync.DestinationDialogFragment
import site.leos.apps.lespas.sync.ShareReceiverActivity

class CameraRollActivity : AppCompatActivity() {
    private lateinit var controls: ConstraintLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_roll)

        controls = findViewById(R.id.controls)

        savedInstanceState?.apply { controls.visibility = this.getInt(CONTROLS_VISIBILITY) }

        intent.data?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                picture.setImageDrawable(ImageDecoder.decodeDrawable(ImageDecoder.createSource(contentResolver, it)).apply { if (this is AnimatedImageDrawable) this.start() })
            else
                picture.setImageURI(it)

            with(getInfo(it)) {
                if (this.isNotEmpty()) info.text = this
                else info.visibility = View.GONE
            }
        } ?: run { finish() }

        picture.setOnClickListener { toggleControls() }

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

        contentResolver.query(uri, null, null, null, null)?.use { cursor->
            cursor.moveToFirst()
            try {
                info = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
            } catch(e: Exception) {e.printStackTrace()}

        }

        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeStream(contentResolver.openInputStream(uri), null, this)
        }

        info = "${info}\n${options.outWidth}w × ${options.outHeight}h"

        return info
    }

    companion object {
        private const val CONTROLS_VISIBILITY = "CONTROLS_VISIBILITY"
        const val TAG_DESTINATION_DIALOG = "CAMERAROLL_DESTINATION_DIALOG"
    }
}