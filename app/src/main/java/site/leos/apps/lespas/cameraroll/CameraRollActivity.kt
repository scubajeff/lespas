package site.leos.apps.lespas.cameraroll

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.chrisbanes.photoview.PhotoView
import kotlinx.android.synthetic.*
import kotlinx.android.synthetic.main.activity_camera_roll.*
import kotlinx.coroutines.*
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.HeaderItemDecoration
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.VolumeControlVideoView
import site.leos.apps.lespas.sync.AcquiringDialogFragment
import site.leos.apps.lespas.sync.DestinationDialogFragment
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.*
import kotlin.math.roundToInt


class CameraRollActivity : AppCompatActivity(), ConfirmDialogFragment.OnResultListener {
    private lateinit var controls: ConstraintLayout
    private lateinit var fileNameTextView: TextView
    private lateinit var fileSizeTextView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var shareButton: ImageButton
    private lateinit var mediaList: RecyclerView
    private var stopPosition = 0
    private var videoMuted = false

    private var currentMedia: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_roll)
        progress = findViewById(R.id.progress_indicator)
        shareButton = findViewById(R.id.share_button)
        fileNameTextView = findViewById(R.id.name)
        fileSizeTextView = findViewById(R.id.size)
        controls = findViewById(R.id.controls)

        //if (intent.getBooleanExtra(BROWSE_GARLLERY, false) || intent.action == Intent.ACTION_MAIN) {
        if (intent.action == Intent.ACTION_MAIN) {
            mediaList = findViewById(R.id.photo_list)
            findViewById<ConstraintLayout>(R.id.medialist_container).visibility = View.VISIBLE
            browseGallery()
            savedInstanceState?.apply {
                currentMedia = getParcelable(CURRENT_MEDIA)!!
                stopPosition = getInt(STOP_POSITION)
                videoMuted = getBoolean(MUTE_STATUS)
            }
            showMedia()
        }
        else intent.data?.let {
            currentMedia = it
            if (hasPermission(it)) showMedia()
        } ?: run { finish() }

        savedInstanceState?.apply { controls.visibility = this.getInt(CONTROLS_VISIBILITY) }

        shareButton.setOnClickListener {
            controls.visibility = View.GONE
            currentMedia?.let {
                startActivity(
                    Intent.createChooser(
                        Intent().apply {
                            action = Intent.ACTION_SEND
                            type = contentResolver.getType(it)
                            putExtra(Intent.EXTRA_STREAM, it)
                            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                        }, null
                    )
                )
            }
        }

        lespas_button.setOnClickListener {
            controls.visibility = View.GONE
            val destinationModel: DestinationDialogFragment.DestinationViewModel by viewModels()
            destinationModel.getDestination().observe(this, { album ->
                // Acquire files
                if (supportFragmentManager.findFragmentByTag(TAG_ACQUIRING_DIALOG) == null)
                    AcquiringDialogFragment.newInstance(arrayListOf(currentMedia!!), album).show(supportFragmentManager, TAG_ACQUIRING_DIALOG)
            })

            if (supportFragmentManager.findFragmentByTag(TAG_DESTINATION_DIALOG) == null)
                DestinationDialogFragment.newInstance().show(supportFragmentManager, TAG_DESTINATION_DIALOG)
        }

        remove_button.setOnClickListener {
            if (supportFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null)
                ConfirmDialogFragment.newInstance(getString(R.string.confirm_delete), getString(R.string.yes_delete)).show(supportFragmentManager, CONFIRM_DIALOG)
        }
    }

    override fun onResume() {
        super.onResume()
        media_container.findViewById<View>(R.id.media)?.let {
            if (it is VolumeControlVideoView) {
                if (videoMuted) it.mute() else it.unMute()
                it.setSeekOnPrepare(stopPosition)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        media_container.findViewById<View>(R.id.media)?.let {
            if (it is VolumeControlVideoView) {
                stopPosition = it.currentPosition
                videoMuted = it.isMute()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(CONTROLS_VISIBILITY, controls.visibility)
        outState.putInt(STOP_POSITION, stopPosition)
        outState.putBoolean(MUTE_STATUS, videoMuted)
        outState.putParcelable(CURRENT_MEDIA, currentMedia)
    }

    override fun onResult(positive: Boolean, requestCode: Int) {
        controls.visibility = View.GONE
        if (positive) {
            if (intent.action == Intent.ACTION_MAIN) {

                if (mediaList.adapter?.itemCount == 2) {
                    // Last item in camera roll, handle it here for easy finishing activity sake
                    deleteAndFinish(currentMedia!!)
                } else (mediaList.adapter as CameraRollAdapter).removeMedia(currentMedia!!)
            } else deleteAndFinish(currentMedia!!)
        }
    }

    private fun deleteAndFinish(uri: Uri) {
        contentResolver.delete(uri, null, null)
        finish()
    }

    private fun toggleControls() {
        controls.visibility = if (controls.visibility == View.GONE) View.VISIBLE else View.GONE
    }

    fun showMedia() {
        val uri = currentMedia

        if (uri != null) {
            // Show a waiting sign when it takes more than 350ms to load the media
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed(showWaitingSign, 350L)

            controls.visibility = View.GONE

            val mimeType = contentResolver.getType(uri)?.let { Intent.normalizeMimeType(it) } ?: run {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(uri.toString()).toLowerCase(Locale.ROOT)) ?: "image/jpeg"
            }

            if (mimeType.startsWith("video/")) {
                fileSizeTextView.visibility = View.GONE
                fileNameTextView.visibility = View.GONE

                var videoView: VolumeControlVideoView
                var muteButton: ImageButton
                var replayButton: ImageButton

                with(layoutInflater.inflate(R.layout.viewpager_item_video, media_container, true)) {
                    videoView = findViewById(R.id.media)
                    muteButton = findViewById(R.id.mute_button)
                    replayButton = findViewById(R.id.replay_button)
                }
                val root = media_container.findViewById<ConstraintLayout>(R.id.videoview_container)

                fun setMute(mute: Boolean) {
                    if (mute) {
                        videoView.mute()
                        muteButton.setImageResource(R.drawable.ic_baseline_volume_off_24)
                    } else {
                        videoView.unMute()
                        muteButton.setImageResource(R.drawable.ic_baseline_volume_on_24)
                    }
                }

                var width: Int
                var height: Int
                with(MediaMetadataRetriever()) {
                    setDataSource(baseContext, uri)
                    width = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
                    height = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
                    // Swap width and height if rotate 90 or 270 degree
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.let {
                        if (it == "90" || it == "270") {
                            height = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
                            width = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
                        }
                    }
                }

                if (height != 0) with(ConstraintSet()) {
                    clone(root)
                    setDimensionRatio(R.id.media, "${width}:${height}")
                    applyTo(root)
                }

                with(videoView) {
                    setVideoURI(uri)
                    setOnPreparedListener {
                        // Call parent onPrepared!!
                        this.onPrepared(it)

                        // Default mute the video playback during late night
                        with(LocalDateTime.now().hour) { if (this >= 22 || this < 7) setMute(true) }

                        // Restart playing after seek to last stop position
                        it.setOnSeekCompleteListener {
                            mp-> mp.start()
                            // Set mute icon
                            setMute(videoView.isMute())
                        }
                    }
                    setOnCompletionListener {
                        replayButton.visibility = View.VISIBLE
                        this.stopPlayback()
                        setSeekOnPrepare(0)
                    }
                }

                root.setOnClickListener { toggleControls() }
                muteButton.setOnClickListener { setMute(!videoView.isMute()) }
                replayButton.setOnClickListener {
                    it.visibility = View.GONE
                    videoView.setVideoURI(currentMedia!!)
                    videoView.start()
                }

                handler.removeCallbacks(showWaitingSign)
                progress.visibility = View.GONE

                return
            }

            GlobalScope.launch(Dispatchers.IO) {

                // Show some statistic first
                /*
                contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media.DATA), MediaStore.Images.Media._ID + "=?",
                arrayOf(DocumentsContract.getDocumentId(uri).split(":")[1]), null)?.apply {
                    if (moveToFirst()) {
                        info = getString(getColumnIndex(MediaStore.Images.Media.DATA)).substringAfterLast('/')
                    }
                    close()
                }
                */
                var fileName = ""
                var fileSize: Long? = 0L
                var size: String
                when(uri.scheme) {
                    "content" -> {
                        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            cursor.moveToFirst()
                            try {
                                fileName = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                                fileSize = cursor.getString(cursor.getColumnIndex(OpenableColumns.SIZE)).toLongOrNull()
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }

                        }
                    }
                    "file" -> {
                        uri.path?.let { fileName = it.substringAfterLast('/') }
                        // Can not reshare uri with scheme "file"
                        shareButton.isEnabled = false
                    }
                }

                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                    BitmapFactory.decodeStream(contentResolver.openInputStream(uri), null, this)
                }

                if (fileName.isNotEmpty()) withContext(Dispatchers.Main) {
                    fileNameTextView.visibility = View.VISIBLE
                    fileNameTextView.text = fileName
                }

                if (mimeType == "image/gif" || mimeType == "image/webp") {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(contentResolver, uri))
                        if (drawable is AnimatedImageDrawable) {
                            withContext(Dispatchers.Main) {
                                val picture = layoutInflater.inflate(R.layout.viewpager_item_gif, media_container).findViewById<ImageView>(R.id.media)
                                handler.removeCallbacks(showWaitingSign)
                                progress.visibility = View.GONE
                                picture.setImageDrawable(drawable.apply { start() })
                                picture.setOnClickListener { toggleControls() }
                            }
                            return@launch
                        }
                    }
                }

                val rotation = if (mimeType == "image/jpeg" || mimeType == "image/tiff") ExifInterface(contentResolver.openInputStream(uri)!!).rotationDegrees else 0
                size = if (rotation == 90 || rotation == 270) "${options.outHeight} × ${options.outWidth}" else "${options.outWidth} × ${options.outHeight}"
                size += fileSize?.let { "  ${Tools.humanReadableByteCountSI(fileSize!!)}" }
                if (size.isNotEmpty()) withContext(Dispatchers.Main) {
                    fileSizeTextView.visibility = View.VISIBLE
                    fileSizeTextView.text = size
                }

                var bitmap = BitmapFactory.decodeStream(contentResolver.openInputStream(uri)!!)
                if (rotation != 0) bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)

                val picture: PhotoView
                withContext(Dispatchers.Main) {
                    picture = layoutInflater.inflate(R.layout.viewpager_item_photo, media_container).findViewById(R.id.media)
                    handler.removeCallbacks(showWaitingSign)
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

    private fun browseGallery() {
        // Querying MediaStore
        val contents = mutableListOf<CameraMedia>()
        val contentUri = MediaStore.Files.getContentUri("external")
        @Suppress("DEPRECATION")
        val pathSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            pathSelection,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.TITLE
        )
        val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})" + " AND " +
                "($pathSelection LIKE '%DCIM%')"
        contentResolver.query(contentUri, projection, selection, null, "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
        )?.use { cursor->
            if (cursor.count == 0) {
                Toast.makeText(this, getString(R.string.empty_camera_roll), Toast.LENGTH_SHORT).show()
                finish()
            }
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.TITLE)
            val pathColumn = cursor.getColumnIndexOrThrow(pathSelection)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
            val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            var currentDate = LocalDate.now().plusDays(1)
            var date: LocalDate
            val defaultOffset = OffsetDateTime.now().offset
            while(cursor.moveToNext()) {
                // Insert date separator if date changes
                date = LocalDateTime.ofEpochSecond(cursor.getLong(dateColumn), 0, defaultOffset).toLocalDate()
                if (date != currentDate) {
                    contents.add(CameraMedia(null, date.monthValue.toString(), date.dayOfMonth.toString(), "", date))
                    currentDate = date
                }

                // Insert media
                contents.add(CameraMedia(cursor.getString(idColumn), cursor.getString(nameColumn), cursor.getString(pathColumn), cursor.getString(typeColumn), date))
            }
        }

        // Preparing view
        mediaList.let {
            it.adapter = CameraRollAdapter(this,
                object : CameraRollAdapter.OnItemClickListener {
                    override fun onItemClick(uri: Uri) {
                        media_container.removeAllViews()
                        currentMedia = uri
                        showMedia()
                    }
                }
            ).apply { setMedia(contents) }

            it.addItemDecoration(HeaderItemDecoration(it) { itemPosition->
                (it.adapter as CameraRollAdapter).getItemViewType(itemPosition) == CameraRollAdapter.DATE_TYPE
            })

            it.addOnScrollListener(object: RecyclerView.OnScrollListener() {
                var toRight = true
                val separatorWidth = resources.getDimension(R.dimen.camera_roll_date_grid_size).roundToInt()
                val mediaGridWidth = resources.getDimension(R.dimen.camera_roll_grid_size).roundToInt()

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    toRight = dx < 0
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        if ((recyclerView.layoutManager as LinearLayoutManager).findLastVisibleItemPosition() < recyclerView.adapter?.itemCount!! - 1) {
                            // if date separator is approaching the header, perform snapping
                            recyclerView.findChildViewUnder(separatorWidth.toFloat(), 0f)?.apply {
                                if (width == separatorWidth) snapTo(this, recyclerView)
                                else recyclerView.findChildViewUnder(separatorWidth.toFloat() + mediaGridWidth / 3, 0f)?.apply {
                                    if (width == separatorWidth) snapTo(this, recyclerView)
                                }
                            }
                        }
                    }
                }

                private fun snapTo(view: View, recyclerView: RecyclerView) {
                    // Snap to this View if scrolling to left, or it's previous one if scrolling to right
                    if (toRight) recyclerView.smoothScrollBy(view.left - separatorWidth - mediaGridWidth, 0, null, 1000)
                    else recyclerView.smoothScrollBy(view.left, 0, null, 500)
                }
            })
        }

        // Assign currentMedia so that showMedia() works
        if (contents.isNotEmpty()) currentMedia = ContentUris.withAppendedId(contentUri, contents[1].id!!.toLong())
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == WRITE_STORAGE_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) showMedia()
            else finish()
        }
    }

    class CameraRollAdapter(context: Context, private val itemClickListener: OnItemClickListener): RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        //private var media = emptyList<CameraMedia>()
        private var media = mutableListOf<CameraMedia>()
        private var cr: ContentResolver = context.contentResolver
        private val jobMap = HashMap<Int, Job>()
        private val contentUri = MediaStore.Files.getContentUri("external")

        interface OnItemClickListener {
            fun onItemClick(uri: Uri)
        }

        inner class CameraRollViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            fun bindViewItems(cameraMedia: CameraMedia) {
                val uri = ContentUris.withAppendedId(contentUri, cameraMedia.id!!.toLong())
                val thumbnailUri =
                    ContentUris.withAppendedId(
                        if (cameraMedia.mimeType.startsWith("image/")) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        cameraMedia.id.toLong()
                    )
                with(itemView.findViewById<ImageView>(R.id.photo)) {
                    val job = GlobalScope.launch(Dispatchers.IO) {
                        try {
                            var bmp: Bitmap
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                                if (cameraMedia.mimeType.startsWith("video/")) {
                                    @Suppress("DEPRECATION")
                                    bmp = MediaStore.Video.Thumbnails.getThumbnail(cr, cameraMedia.id.toLong(), MediaStore.Video.Thumbnails.MINI_KIND, null)
                                } else {
                                    @Suppress("DEPRECATION")
                                    bmp = MediaStore.Images.Thumbnails.getThumbnail(cr, cameraMedia.id.toLong(), MediaStore.Images.Thumbnails.MINI_KIND, null)
                                    val rotation = if (cameraMedia.mimeType == "image/jpeg" || cameraMedia.mimeType == "image/tiff") ExifInterface(cr.openInputStream(uri)!!).rotationDegrees else 0
                                    if (rotation != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
                                }
                            }
                            else bmp = cr.loadThumbnail(thumbnailUri, Size(240, 240), null)

                            if (isActive) withContext(Dispatchers.Main) { setImageBitmap(bmp) }
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                    replacePreviousJob(System.identityHashCode(this), job)
                    setOnClickListener { itemClickListener.onItemClick(uri) }
                }
                itemView.findViewById<ImageView>(R.id.play_mark).visibility = if (cameraMedia.mimeType.startsWith("video/")) View.VISIBLE else View.GONE
            }
        }

        inner class DateViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            fun bindViewItems(cameraMedia: CameraMedia) {
                itemView.findViewById<TextView>(R.id.month).text = cameraMedia.name
                itemView.findViewById<TextView>(R.id.day).text = cameraMedia.path
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == MEDIA_TYPE) CameraRollViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_cameraroll, parent, false))
            else DateViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_cameraroll_date, parent, false))

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is CameraRollViewHolder) holder.bindViewItems(media[position])
            else if (holder is DateViewHolder) holder.bindViewItems(media[position])
        }

        override fun getItemCount(): Int = media.size

        override fun getItemViewType(position: Int): Int = media[position].id?.let { MEDIA_TYPE } ?: run { DATE_TYPE }

        fun setMedia(media: List<CameraMedia>) { this.media.addAll(0, media) }

        fun removeMedia(uri: Uri) {
            val index = media.indexOfFirst { it.id == uri.lastPathSegment }
            val nextUri: Uri
            var last1inDate = false

            if (index < media.lastIndex) {
                // Not the last 1 in list, find next 1 to the right
                //if (media[index + 1].id != null)
                if (getItemViewType(index + 1) == MEDIA_TYPE)
                    // Next 1 in list is a photo
                    nextUri = ContentUris.withAppendedId(contentUri, media[index + 1].id!!.toLong())
                else {
                    // Next 1 in list is date separator, get next to next 1
                    nextUri = ContentUris.withAppendedId(contentUri, media[index + 2].id!!.toLong())
                    // If previous 1 and next 1 are all date separators, this one is the only one left in this date, should also remove it's date separator
                    //last1inDate = (media[index - 1].id == null)
                    last1inDate = (getItemViewType(index - 1) == DATE_TYPE)
                }
            } else {
                // Last 1 in list, should find next 1 to the left
                // The case of only one left in list is handled in button's onclicklistener for easy finishing the activity
                //if (media[index - 1].id != null)
                if (getItemViewType(index - 1) == MEDIA_TYPE)
                    // Previous 1 in list is a photo
                    nextUri = ContentUris.withAppendedId(contentUri, media[index - 1].id!!.toLong())
                else {
                    // Previous 1 in list is date separator
                    nextUri = ContentUris.withAppendedId(contentUri, media[index - 2].id!!.toLong())
                    // Since this is the last 1, that means it's the only 1 in this date
                    last1inDate = true
                }
            }

            // Removing
            if (cr.delete(uri, null, null) == 1) {
                media.removeAt(index)
                notifyItemRemoved(index)
                if (last1inDate) {
                    media.removeAt(index - 1)
                    notifyItemRemoved(index - 1)
                }

                // Show next media
                itemClickListener.onItemClick(nextUri)
            }
        }

        private fun replacePreviousJob(key: Int, newJob: Job) {
            jobMap[key]?.cancel()
            jobMap[key] = newJob
        }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            super.onDetachedFromRecyclerView(recyclerView)
            jobMap.forEach { if (it.value.isActive) it.value.cancel() }
        }

        companion object {
            private const val MEDIA_TYPE = 0
            const val DATE_TYPE = 1
        }
    }

    data class CameraMedia(
        val id: String?,
        val name: String,
        val path: String,
        val mimeType: String,
        val date: LocalDate,
    )

    companion object {
        private const val CONTROLS_VISIBILITY = "CONTROLS_VISIBILITY"
        private const val WRITE_STORAGE_PERMISSION_REQUEST = 6464
        private const val CURRENT_MEDIA = "CURRENT_MEDIA"
        private const val STOP_POSITION = "STOP_POSITION"
        private const val MUTE_STATUS = "MUTE_STATUS"
        const val TAG_DESTINATION_DIALOG = "CAMERAROLL_DESTINATION_DIALOG"
        const val TAG_ACQUIRING_DIALOG = "CAMERAROLL_ACQUIRING_DIALOG"
        //const val BROWSE_GARLLERY = "site.leos.apps.lespas.BROWSE_GARLLERY"

        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
    }
}