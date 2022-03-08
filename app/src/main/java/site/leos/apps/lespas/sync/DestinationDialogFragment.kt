package site.leos.apps.lespas.sync

import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.MimeTypeMap
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.doOnNextLayout
import androidx.core.view.doOnPreDraw
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.TransitionInflater
import androidx.transition.TransitionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumViewModel
import site.leos.apps.lespas.cameraroll.CameraRollFragment
import site.leos.apps.lespas.helper.AlbumNameValidator
import site.leos.apps.lespas.helper.LesPasDialogFragment
import site.leos.apps.lespas.helper.SingleLiveEvent
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.publication.PublicationDetailFragment
import java.time.LocalDateTime
import kotlin.math.roundToInt

class DestinationDialogFragment : LesPasDialogFragment(R.layout.fragment_destination_dialog) {
    private lateinit var albumAdapter: DestinationAdapter
    private lateinit var clipDataAdapter: ClipDataAdapter

    private val albumModel: AlbumViewModel by viewModels()
    private val destinationModel: DestinationViewModel by activityViewModels()
    private val publicationModel: NCShareViewModel by activityViewModels()
    private lateinit var jointAlbumLiveData: LiveData<List<NCShareViewModel.ShareWithMe>>

    private lateinit var rootLayout: ConstraintLayout
    private lateinit var clipDataRecyclerView: RecyclerView
    private lateinit var destinationRecyclerView: RecyclerView
    private lateinit var copyOrMoveToggleGroup: MaterialButtonToggleGroup
    private lateinit var newAlbumTextInputLayout: TextInputLayout
    private lateinit var newAlbumTitleTextInputEditText: TextInputEditText
    private lateinit var toAlbumTextView: TextView
    private lateinit var remoteAlbumCheckBox: CheckBox
    private var remoteAlbumIconDrawableSize = 16

    private var remotePhotos = mutableListOf<NCShareViewModel.RemotePhoto>()

    private var ignoreAlbum = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        albumAdapter = DestinationAdapter(
            { album ->
                if (album.id.isEmpty()) {
                    destinationModel.setEditMode(true)

                    TransitionManager.beginDelayedTransition(rootLayout, TransitionInflater.from(requireContext()).inflateTransition(R.transition.destination_dialog_new_album))
                    showNewAlbumEditText()
                }
                // User choose an existing album
                else {
                    lifecycleScope.launch(Dispatchers.IO) {
                        destinationModel.setRemoveOriginal(copyOrMoveToggleGroup.checkedButtonId == R.id.move)
                        val theAlbum: Album = if (album.lastModified == LocalDateTime.MAX)
                        //theAlbum = Album(PublicationDetailFragment.JOINT_ALBUM_ID, album.cover.substringBeforeLast('/'), LocalDateTime.MIN, LocalDateTime.MAX, "", 0, 0, 0, LocalDateTime.now(), 0, album.id, Album.NULL_ALBUM, 1f)
                            Album(
                                id = PublicationDetailFragment.JOINT_ALBUM_ID,
                                name = album.cover.substringBeforeLast('/'),
                                lastModified = LocalDateTime.now(),
                                eTag = album.id,
                                shareId = Album.NULL_ALBUM,
                            )
                        else albumModel.getThisAlbum(album.id)

                        withContext(Dispatchers.Main) {
                            destinationModel.setDestination(theAlbum)
                            dismiss()
                        }
                    }
                }
            },
            { album, view, type ->
                album.run {
                    publicationModel.setImagePhoto(
                        NCShareViewModel.RemotePhoto(Photo(
                        id = cover, albumId = id,
                        name = if ((Tools.isRemoteAlbum(album) && cover != coverFileName.substringAfterLast('/')) || lastModified == LocalDateTime.MAX) coverFileName.substringAfterLast('/') else coverFileName,
                        width = coverWidth, height = coverHeight, mimeType = coverMimeType, orientation = coverOrientation,
                        dateTaken = LocalDateTime.MIN, lastModified = LocalDateTime.MIN,
                        // TODO dirty hack, can't fetch cover photo's eTag here, hence by comparing it's id to name, for not yet uploaded file these two should be the same, otherwise use a fake one as long as it's not empty
                        eTag = if (cover == coverFileName) Photo.ETAG_NOT_YET_UPLOADED else Photo.ETAG_FAKE,
                    ), if ((Tools.isRemoteAlbum(album) && cover != coverFileName.substringAfterLast('/')) || lastModified == LocalDateTime.MAX) coverFileName.substringBeforeLast('/') else "", coverBaseline), view, type)
/*
                    if ((Tools.isRemoteAlbum(album) && cover != coverFileName.substringAfterLast('/')) || lastModified == LocalDateTime.MAX)
                        publicationModel.getPhoto(NCShareViewModel.RemotePhoto(Photo(
                            id = cover, name = coverFileName.substringAfterLast('/'),
                            mimeType = coverMimeType, width = coverWidth, height = coverHeight, orientation = coverOrientation, dateTaken = LocalDateTime.MIN, lastModified = LocalDateTime.MIN
                        ), coverFileName.substringBeforeLast('/'), coverBaseline), view, type)
                    else
                        imageLoaderModel.loadPhoto(Photo(
                            id = cover, albumId = id, name = coverFileName,
                            width = coverWidth, height = coverHeight, mimeType = coverMimeType, shareId = coverBaseline, orientation = coverOrientation,
                            dateTaken = LocalDateTime.MIN, lastModified = LocalDateTime.MIN
                        ), view, type)
*/
                }
            },
            { user, view -> publicationModel.getAvatar(user, view, null) },
            { view -> publicationModel.cancelSetImagePhoto(view) }
        ).apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            setCoverType(tag == ShareReceiverActivity.TAG_DESTINATION_DIALOG)
        }

        clipDataAdapter = ClipDataAdapter { uri, view, position ->
            lifecycleScope.launch(Dispatchers.IO) {
                val cr = requireContext().contentResolver
                val bitmap: Bitmap? =
                    when {
                        uri.scheme == "lespas"-> {
                            publicationModel.setImagePhoto(remotePhotos[position], view, NCShareViewModel.TYPE_GRID)
                            null
                        }
                        (cr.getType(uri) ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(uri.toString())) ?: "image/*").startsWith("image") -> {
                            try {
                                BitmapFactory.decodeStream(cr.openInputStream(uri), null, BitmapFactory.Options().apply { inSampleSize = 8 })
                            } catch (e: Exception) {
                                e.printStackTrace()
                                null
                            }
                        }
                        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1 -> {
                            try {
                                val retriever = MediaMetadataRetriever()
                                retriever.setDataSource(requireContext(), uri)
                                (retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC, 64, 64)).also { retriever.release() }
                            } catch (e: Exception) {
                                e.printStackTrace()
                                null
                            }
                        }
                        else -> null
                    }

                if (uri.scheme != "lespas") withContext(Dispatchers.Main) { view.setImageBitmap(bitmap ?: ContextCompat.getDrawable(requireContext(), R.drawable.ic_baseline_imagefile_24)!!.toBitmap()) }
            }
        }.apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }
        clipDataAdapter.submitList(
            requireArguments().getParcelableArrayList<Uri>(KEY_URIS)?.toMutableList() ?: run {
                val uris = mutableListOf<Uri>()
                remotePhotos = requireArguments().getParcelableArrayList<NCShareViewModel.RemotePhoto>(KEY_REMOTE_PHOTO)?.toMutableList() ?: mutableListOf()
                remotePhotos.forEach {
                    uris.add(Uri.fromParts("lespas", "//${it.remotePath}", ""))
                }
                uris
            }
        )

        ignoreAlbum = requireArguments().getString(KEY_IGNORE_ALBUM) ?: ""
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rootLayout = view.findViewById<ConstraintLayout>(R.id.background).apply {
            // Set the dialog maximum height to 70% of screen/window height
            doOnNextLayout {
                ConstraintSet().run {
                    val height = with(resources.displayMetrics) { (heightPixels.toFloat() * 0.75 - copyOrMoveToggleGroup.measuredHeight - clipDataRecyclerView.measuredHeight - TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 88f, this)).roundToInt() }
                    clone(rootLayout)
                    constrainHeight(R.id.destination_recyclerview, ConstraintSet.MATCH_CONSTRAINT)
                    constrainMaxHeight(R.id.destination_recyclerview, height)
                    applyTo(rootLayout)
                }
            }
        }
        clipDataRecyclerView = view.findViewById(R.id.clipdata_recyclerview)
        destinationRecyclerView = view.findViewById(R.id.destination_recyclerview)
        copyOrMoveToggleGroup = view.findViewById(R.id.move_or_copy)
        newAlbumTextInputLayout = view.findViewById<TextInputLayout?>(R.id.new_album_textinputlayout).apply {
            this.editText?.run {
                compoundDrawablePadding = 16
                TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(currentTextColor))
                remoteAlbumIconDrawableSize = textSize.toInt()
            }
        }
        newAlbumTitleTextInputEditText = view.findViewById(R.id.name_textinputedittext)
        toAlbumTextView = view.findViewById(R.id.to)
        remoteAlbumCheckBox = view.findViewById<CheckBox?>(R.id.create_remote_album).apply {
            setOnCheckedChangeListener { _, isChecked ->
                newAlbumTitleTextInputEditText.setCompoundDrawables(
                    if (isChecked) ContextCompat.getDrawable(context, R.drawable.ic_baseline_wb_cloudy_24)?.apply { setBounds(0, 0, remoteAlbumIconDrawableSize, remoteAlbumIconDrawableSize) } else null,
                    null, null, null
                )
            }
        }

        clipDataRecyclerView.adapter = clipDataAdapter
        destinationRecyclerView.adapter = albumAdapter
        destinationRecyclerView.doOnPreDraw {
            if (savedInstanceState == null && (tag == ShareReceiverActivity.TAG_DESTINATION_DIALOG || tag == CameraRollFragment.TAG_FROM_CAMERAROLL_ACTIVITY)) {
                publicationModel.refresh()
            }
        }

        view.findViewById<MaterialButton>(R.id.move).isEnabled = arguments?.getBoolean(KEY_CAN_WRITE) == true
        savedInstanceState?.let {
            it.getInt(COPY_OR_MOVE).apply { copyOrMoveToggleGroup.check(if (this == 0) R.id.copy else this) }
        }

        newAlbumTitleTextInputEditText.run {
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_NULL) {
                    // Validate the name
                    error ?: run {
                        val name = this.text.toString().trim()    // Trim the leading and trailing blank
                        if (name.isNotEmpty()) {
                            destinationModel.setRemoveOriginal(copyOrMoveToggleGroup.checkedButtonId == R.id.move)
                            // Return with album id field empty, calling party will know this is a new album
                            destinationModel.setDestination(
/*
                                Album(
                                    "", name,
                                    LocalDateTime.MAX, LocalDateTime.MIN,
                                    "", 0, 0, 0,
                                    LocalDateTime.now(), Album.BY_DATE_TAKEN_ASC, "",
                                    if (remoteAlbumCheckBox.isChecked) Album.REMOTE_ALBUM else Album.NULL_ALBUM,
                                    1f
                                )
*/
                                Album(name = name, lastModified = LocalDateTime.now(), shareId = if (remoteAlbumCheckBox.isChecked) Album.REMOTE_ALBUM else Album.NULL_ALBUM)
                            )

                            // Clear editing mode
                            destinationModel.setEditMode(false)

                            dismiss()
                        }
                    }
                    true
                } else false
            }
        }

        // Maintain current mode after screen rotation
        if (destinationModel.isEditing()) showNewAlbumEditText()

        jointAlbumLiveData = publicationModel.shareWithMe.asLiveData()
        albumModel.allAlbumsByEndDate.observe(viewLifecycleOwner, Observer {
            val nullAlbum = Album(shareId = Album.NULL_ALBUM, lastModified = LocalDateTime.now())
            val base = getString(R.string.lespas_base_folder_name)
            val albums = mutableListOf<Album>()
            it.forEach { album ->
                if (Tools.isRemoteAlbum(album) && album.cover != album.coverFileName) album.coverFileName = "${base}/${album.name}/${album.coverFileName}"
                albums.add(album)
            }
            albumAdapter.submitList(albums.plus(nullAlbum).toMutableList())

            jointAlbumLiveData.observe(viewLifecycleOwner, Observer { shared->
                val jointAlbums = mutableListOf<Album>()
                for (publication in shared) {
                    if (publication.permission == NCShareViewModel.PERMISSION_JOINT && publication.albumId != ignoreAlbum) jointAlbums.add(
                        //Album(publication.albumId, publication.albumName, LocalDateTime.now(), LocalDateTime.now(), "${publication.sharePath}/${publication.coverFileName}", publication.cover.coverBaseline, publication.cover.coverWidth, publication.cover.coverHeight, LocalDateTime.now(), publication.sortOrder, publication.shareBy, Album.NULL_ALBUM, JOINT_PUBLICATION)
                        Album(
                            publication.albumId, publication.albumName,
                            LocalDateTime.now(), LocalDateTime.now(),
                            "", publication.cover.coverBaseline, publication.cover.coverWidth, publication.cover.coverHeight,
                            LocalDateTime.MAX,      // Denotes publication
                            publication.sortOrder,
                            publication.shareBy,    // Pass shareby in property eTag
                            Album.NULL_ALBUM, 1f,
                            "${publication.sharePath}/${publication.cover.coverFileName}", publication.cover.coverMimeType, publication.cover.coverOrientation
                        )
                    )
                }
                if (jointAlbums.isNotEmpty()) {
                    val newAlbumList = albumAdapter.currentList.toMutableList()
                    if (newAlbumList.last().id.isEmpty()) newAlbumList.removeLast()
                    newAlbumList.addAll(jointAlbums)
                    albumAdapter.submitList(newAlbumList.plus(nullAlbum).toMutableList())
                }
                if (shared.isNotEmpty()) jointAlbumLiveData.removeObservers(this)
            })

            // Create new title validator dictionary with current album names
            newAlbumTitleTextInputEditText.addTextChangedListener(AlbumNameValidator(newAlbumTitleTextInputEditText, arrayListOf<String>().apply { it.forEach { album-> this.add(album.name)} }))
        })
    }

    override fun onStart() {
        super.onStart()

        dialog?.setCanceledOnTouchOutside(false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(COPY_OR_MOVE, copyOrMoveToggleGroup.checkedButtonId)
    }

    override fun onDestroyView() {
        destinationModel.setRemoveOriginal(copyOrMoveToggleGroup.checkedButtonId == R.id.move)

        destinationRecyclerView.adapter = null
        clipDataRecyclerView.adapter = null

        super.onDestroyView()
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)

        // Clear editing mode
        destinationModel.setEditMode(false)

        // If called by ShareReceiverActivity, quit immediately, otherwise return normally
        if (tag == ShareReceiverActivity.TAG_DESTINATION_DIALOG) activity?.finish()
    }

    private fun showNewAlbumEditText() {
        toAlbumTextView.text = getString(R.string.to_new_album)
        destinationRecyclerView.visibility = View.GONE
        newAlbumTextInputLayout.apply {
            visibility = View.VISIBLE
            requestFocus()
        }
    }

    class DestinationAdapter(private val itemClickListener: (Album)-> Unit, private val imageLoader: (Album, ImageView, String)-> Unit, private val avatarLoader: (NCShareViewModel.Sharee, View)-> Unit, private val cancelLoading: (ImageView)-> Unit)
    : ListAdapter<Album, DestinationAdapter.DestViewHolder>(DestinationDiffCallback()) {
        private var coverType: String = NCShareViewModel.TYPE_SMALL_COVER

        inner class DestViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            private val ivCover = itemView.findViewById<ImageView>(R.id.cover)
            private val tvName = itemView.findViewById<TextView>(R.id.name)
            private val cloudDrawable: Drawable?

            init {
                val nameDrawableSize = tvName.textSize.toInt()
                cloudDrawable = ContextCompat.getDrawable(tvName.context, R.drawable.ic_baseline_wb_cloudy_24)?.apply { setBounds(0, 0, nameDrawableSize, nameDrawableSize) }
            }

            fun bindViewItems(album: Album) {
                if (album.id.isEmpty()) {
                    ivCover.apply {
                        cancelLoading(this)
                        setImageResource(R.drawable.ic_baseline_add_24)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    tvName.apply {
                        text = itemView.resources.getString(R.string.create_new_album)
                        setCompoundDrawables(null, null, null, null)
                    }
                } else {
                    ivCover.apply {
                        imageLoader(album, this, coverType)
                        if (album.lastModified == LocalDateTime.MAX) avatarLoader(NCShareViewModel.Sharee(album.eTag, "", NCShareViewModel.SHARE_TYPE_USER), itemView.findViewById<TextView>(R.id.avatar))
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                    tvName.apply {
                        text = album.name
                        setCompoundDrawables(if (Tools.isRemoteAlbum(album)) cloudDrawable else null, null, null, null)
                    }
                }

                itemView.setOnClickListener { itemClickListener(album) }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DestViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(if (viewType == 1) R.layout.recyclerview_item_destination_joint else R.layout.recyclerview_item_destination, parent, false)
            view.findViewById<TextView>(R.id.name)?.apply {
                compoundDrawablePadding = 16
                TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(currentTextColor))
            }
            return DestViewHolder(view)
        }

        override fun onBindViewHolder(holder: DestViewHolder, position: Int) {
            holder.bindViewItems(currentList[position])
        }

        override fun getItemViewType(position: Int): Int = if (currentList[position].lastModified == LocalDateTime.MAX) 1 else 0

        fun setCoverType(smallCover: Boolean) {
            coverType = if (smallCover) NCShareViewModel.TYPE_SMALL_COVER else NCShareViewModel.TYPE_COVER
        }
    }

    class DestinationDiffCallback: DiffUtil.ItemCallback<Album>() {
        override fun areItemsTheSame(oldItem: Album, newItem: Album) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Album, newItem: Album) = oldItem.id == newItem.id && oldItem.cover == newItem.cover
    }

    class ClipDataAdapter(private val loadClipData: (Uri, ImageView, Int)-> Unit): ListAdapter<Uri, ClipDataAdapter.MediaViewHolder>(ClipDataDiffCallback()) {
        inner class MediaViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            fun bind(uri: Uri, position: Int) {
                with(itemView.findViewById<ImageView>(R.id.media)) {
                    loadClipData(uri, this, position)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder =
            MediaViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_clipdata, parent, false))

        override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
            holder.bind(currentList[position], position)
        }
    }

    class ClipDataDiffCallback: DiffUtil.ItemCallback<Uri>() {
        override fun areItemsTheSame(oldItem: Uri, newItem: Uri): Boolean = oldItem == newItem
        override fun areContentsTheSame(oldItem: Uri, newItem: Uri): Boolean = oldItem == newItem
    }

    class DestinationViewModel: ViewModel() {
        private var destination = SingleLiveEvent<Album?>()
        private var inEditing = false
        private var removeOriginal = false

        fun setDestination(newDestination: Album) { destination.value = newDestination }
        fun getDestination(): SingleLiveEvent<Album?> = destination
        fun setEditMode(mode: Boolean) { inEditing = mode }
        fun isEditing() = inEditing
        fun setRemoveOriginal(remove: Boolean) { removeOriginal = remove }
        fun shouldRemoveOriginal() = removeOriginal
    }

    companion object {
        const val KEY_URIS = "KEY_URIS"
        const val KEY_CAN_WRITE = "KEY_CAN_WRITE"
        const val KEY_REMOTE_PHOTO = "KEY_REMOTE_PHOTO"
        const val KEY_IGNORE_ALBUM = "KEY_IGNORE_ALBUM"

        private const val COPY_OR_MOVE = "COPY_OR_MOVE"

        @JvmStatic
        fun newInstance(uris: ArrayList<Uri>, canWrite: Boolean) = DestinationDialogFragment().apply {
            arguments = Bundle().apply {
                putParcelableArrayList(KEY_URIS, uris)
                putBoolean(KEY_CAN_WRITE, canWrite)
            }
        }

        @JvmStatic
        fun newInstance(remotePhotos: ArrayList<NCShareViewModel.RemotePhoto>, ignoreAlbumId: String) = DestinationDialogFragment().apply {
            arguments = Bundle().apply {
                putParcelableArrayList(KEY_REMOTE_PHOTO, remotePhotos)
                putString(KEY_IGNORE_ALBUM, ignoreAlbumId)
                putBoolean(KEY_CAN_WRITE, false)        // TODO could be true for joint album
            }
        }
    }
}