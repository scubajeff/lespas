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

package site.leos.apps.lespas.sync

import android.app.Dialog
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.MimeTypeMap
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SearchView
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
import site.leos.apps.lespas.helper.FileNameValidator
import site.leos.apps.lespas.helper.LesPasDialogFragment
import site.leos.apps.lespas.helper.SingleLiveEvent
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.Tools.parcelableArrayList
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.publication.NCShareViewModel
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
    private lateinit var nameFilterSearchView: SearchView
    private var remoteAlbumIconDrawableSize = 16

    private var ignoreAlbum = ""

    private var albums = listOf<RemoteAlbum>()
    private var currentFilter = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        albumAdapter = DestinationAdapter(
            { remoteAlbum ->
                remoteAlbum.album.let { album ->
                    if (album.id.isEmpty()) {
                        // Create new album
                        destinationModel.setEditMode(true)

                        TransitionManager.beginDelayedTransition(rootLayout, TransitionInflater.from(rootLayout.context).inflateTransition(R.transition.destination_dialog_new_album))
                        showNewAlbumEditText()
                    }
                    else {
                        // Chosen an existing album
                        lifecycleScope.launch(Dispatchers.IO) {
                            destinationModel.setRemoveOriginal(copyOrMoveToggleGroup.checkedButtonId == R.id.move)
                            val theAlbum: Album = if (remoteAlbum.shareBy.isNotEmpty())
                            album.copy(id = Album.JOINT_ALBUM_ID, coverFileName = "${remoteAlbum.sharePath}/${album.coverFileName}", eTag = album.id)
                            else album

                            withContext(Dispatchers.Main) {
                                destinationModel.setDestination(theAlbum)
                                dismiss()
                            }
                        }
                    }
                }
            },
            { remoteAlbum, view, type ->
                remoteAlbum.album.run {
                    publicationModel.setImagePhoto(
                        NCShareViewModel.RemotePhoto(Photo(
                        id = cover, albumId = id, name = coverFileName, width = coverWidth, height = coverHeight, mimeType = coverMimeType, orientation = coverOrientation,
                        dateTaken = LocalDateTime.MIN, lastModified = LocalDateTime.MIN,
                        // TODO dirty hack, can't fetch cover photo's eTag here, hence by comparing it's id to name, for not yet uploaded file these two should be the same, otherwise use a fake one as long as it's not empty
                        eTag = if (cover == coverFileName) Photo.ETAG_NOT_YET_UPLOADED else Photo.ETAG_FAKE,
                    ), remoteAlbum.sharePath, coverBaseline), view, type)
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
                            publicationModel.setImagePhoto(destinationModel.getRemotePhotos()[position], view, NCShareViewModel.TYPE_GRID)
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
        }.apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }

        clipDataAdapter.submitList(
            requireArguments().parcelableArrayList<Uri>(KEY_URIS)?.toMutableList() ?: run {
                // Mark operation should be carried out on server, if KEY_URIS is null, which means the other argument KEY_REMOTE_PHOTO passed to this fragment
                destinationModel.setOnServer(true)

                val uris = mutableListOf<Uri>()
                (requireArguments().parcelableArrayList<NCShareViewModel.RemotePhoto>(KEY_REMOTE_PHOTO)?.toMutableList() ?: mutableListOf()).apply {
                    forEach { uris.add(Uri.fromParts("lespas", "//${it.remotePath}", "")) }
                    destinationModel.setRemotePhotos(this)
                }
                uris
            }
        )

        ignoreAlbum = requireArguments().getString(KEY_IGNORE_ALBUM) ?: ""
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).also {
            (it as ComponentDialog).onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (currentFilter.isNotEmpty()) {
                        nameFilterSearchView.setQuery("", false)
                        nameFilterSearchView.isIconified = true
                        return
                    }

                    if (tag == ShareReceiverActivity.TAG_DESTINATION_DIALOG) requireActivity().finish()
                    else {
                        // Clear editing mode
                        destinationModel.setEditMode(false)

                        dialog?.dismiss()
                    }
                }
            })
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rootLayout = view.findViewById<ConstraintLayout>(R.id.background).apply {
            // Set the dialog maximum height to 75% of screen/window height
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
        nameFilterSearchView = view.findViewById<SearchView>(R.id.name_filter).apply {
            if (currentFilter.isNotEmpty()) setQuery(currentFilter, false)

            queryHint = getString(R.string.option_menu_name_filter)

            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false
                override fun onQueryTextChange(newText: String?): Boolean {
                    (newText ?: "").let { text ->
                        currentFilter = text
                        setAlbums()
                    }
                    return false
                }
            })
        }

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
            it.getInt(KEY_COPY_OR_MOVE)?.apply { copyOrMoveToggleGroup.check(if (this == 0) R.id.copy else this) }
            currentFilter = it.getString(KEY_NAME_FILTER) ?: ""
        }


        newAlbumTitleTextInputEditText.run {
            setOnEditorActionListener { _, actionId, keyEvent ->
                if (actionId == EditorInfo.IME_ACTION_GO || keyEvent.keyCode == KeyEvent.KEYCODE_ENTER) {
                    // Validate the name
                    error ?: run {
                        val name = this.text.toString().trim()    // Trim the leading and trailing blank
                        if (name.isNotEmpty()) {
                            destinationModel.setRemoveOriginal(copyOrMoveToggleGroup.checkedButtonId == R.id.move)
                            // Return with album id field empty, calling party will know this is a new album
                            destinationModel.setDestination(Album(name = name, lastModified = LocalDateTime.now(), shareId = if (remoteAlbumCheckBox.isChecked) Album.REMOTE_ALBUM else Album.NULL_ALBUM))

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
        albumModel.allAlbumsByEndDate.observe(viewLifecycleOwner) { albums ->
            val nullAlbum = Album(shareId = Album.NULL_ALBUM, lastModified = LocalDateTime.now())
            val base = Tools.getRemoteHome(requireContext())
            val remoteAlbums = mutableListOf(RemoteAlbum(nullAlbum, "", ""))
            albums.forEach { album -> if (album.id != ignoreAlbum) remoteAlbums.add(RemoteAlbum(album, if (Tools.isRemoteAlbum(album)) "${base}/${album.name}" else "", "")) }
            this.albums = remoteAlbums
            setAlbums()

            jointAlbumLiveData.observe(viewLifecycleOwner) { shared ->
                val jointAlbums = mutableListOf<RemoteAlbum>()
                for (publication in shared) {
                    if (publication.permission == NCShareViewModel.PERMISSION_JOINT && publication.albumId != ignoreAlbum) jointAlbums.add(
                        RemoteAlbum(
                            Album(
                                publication.albumId, publication.albumName,
                                LocalDateTime.now(), LocalDateTime.now(),
                                publication.cover.cover, publication.cover.coverBaseline, publication.cover.coverWidth, publication.cover.coverHeight,
                                LocalDateTime.now(), publication.sortOrder, "",
                                Album.REMOTE_ALBUM, 1f,
                                publication.cover.coverFileName, publication.cover.coverMimeType, publication.cover.coverOrientation
                            ),
                            publication.sharePath, publication.shareBy
                        )
                    )
                }
                if (jointAlbums.isNotEmpty()) {
                    this.albums = this.albums.plus(jointAlbums)
                    setAlbums()
                }

                if (shared.isNotEmpty()) jointAlbumLiveData.removeObservers(this)
            }

            // Create new title validator dictionary with current album names
            newAlbumTitleTextInputEditText.addTextChangedListener(FileNameValidator(newAlbumTitleTextInputEditText, arrayListOf<String>().apply { albums.forEach { album -> this.add(album.name) } }))
        }
    }

    override fun onStart() {
        super.onStart()

        dialog?.setCanceledOnTouchOutside(false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_COPY_OR_MOVE, copyOrMoveToggleGroup.checkedButtonId)
        outState.putString(KEY_NAME_FILTER, currentFilter)
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
        nameFilterSearchView.visibility = View.GONE
        nameFilterSearchView.setQuery("", false)
        newAlbumTextInputLayout.apply {
            visibility = View.VISIBLE
            requestFocus()
        }
    }

    private fun setAlbums() {
        albumAdapter.submitList(if (currentFilter.isNotEmpty()) albums.filter { it.album.name.contains(currentFilter, true) } else albums)
    }

    class DestinationAdapter(private val itemClickListener: (RemoteAlbum)-> Unit, private val imageLoader: (RemoteAlbum, ImageView, String)-> Unit, private val avatarLoader: (NCShareViewModel.Sharee, View)-> Unit, private val cancelLoader: (View)-> Unit)
    : ListAdapter<RemoteAlbum, DestinationAdapter.DestViewHolder>(DestinationDiffCallback()) {
        private var coverType: String = NCShareViewModel.TYPE_SMALL_COVER

        inner class DestViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            private var currentAlbumId = ""
            private val ivCover = itemView.findViewById<ImageView>(R.id.cover)
            private val tvName = itemView.findViewById<TextView>(R.id.name)
            private val cloudDrawable: Drawable?

            init {
                val nameDrawableSize = tvName.textSize.toInt()
                cloudDrawable = ContextCompat.getDrawable(tvName.context, R.drawable.ic_baseline_wb_cloudy_24)?.apply { setBounds(0, 0, nameDrawableSize, nameDrawableSize) }
            }

            fun bindViewItems(remoteAlbum: RemoteAlbum) {
                if (remoteAlbum.album.id.isEmpty()) {
                    ivCover.apply {
                        cancelLoader(this)
                        setImageResource(R.drawable.ic_baseline_new_album_24)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    tvName.apply {
                        text = itemView.resources.getString(R.string.create_new_album)
                        setCompoundDrawables(null, null, null, null)
                    }
                } else {
                    ivCover.apply {
                        if (currentAlbumId != remoteAlbum.album.id) {
                            imageLoader(remoteAlbum, this, coverType)
                            currentAlbumId = remoteAlbum.album.id
                        }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                    tvName.apply {
                        text = remoteAlbum.album.name
                        when {
                            remoteAlbum.shareBy.isNotEmpty() -> avatarLoader(NCShareViewModel.Sharee(remoteAlbum.shareBy, "", NCShareViewModel.SHARE_TYPE_USER), itemView.findViewById<TextView>(R.id.avatar))
                            Tools.isRemoteAlbum(remoteAlbum.album) -> setCompoundDrawables(cloudDrawable, null, null, null)
                            else -> setCompoundDrawables(null, null, null, null)
                        }
                    }
                }

                itemView.setOnClickListener { itemClickListener(remoteAlbum) }
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

        override fun onBindViewHolder(holder: DestViewHolder, position: Int) { holder.bindViewItems(currentList[position]) }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            for (i in 0 until currentList.size) recyclerView.findViewHolderForAdapterPosition(i)?.let { holder -> holder.itemView.findViewById<View>(R.id.cover)?.let { cancelLoader(it) }}
            super.onDetachedFromRecyclerView(recyclerView)
        }

        override fun getItemViewType(position: Int): Int = if (currentList[position].shareBy.isNotEmpty()) 1 else 0

        fun setCoverType(smallCover: Boolean) { coverType = if (smallCover) NCShareViewModel.TYPE_SMALL_COVER else NCShareViewModel.TYPE_COVER }
    }

    class DestinationDiffCallback: DiffUtil.ItemCallback<RemoteAlbum>() {
        override fun areItemsTheSame(oldItem: RemoteAlbum, newItem: RemoteAlbum) = oldItem.album.id == newItem.album.id
        override fun areContentsTheSame(oldItem: RemoteAlbum, newItem: RemoteAlbum) = oldItem.album.cover == newItem.album.cover
    }

    class ClipDataAdapter(private val loadClipData: (Uri, ImageView, Int)-> Unit): ListAdapter<Uri, ClipDataAdapter.MediaViewHolder>(ClipDataDiffCallback()) {
        inner class MediaViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            fun bind(uri: Uri, position: Int) { itemView.findViewById<ImageView>(R.id.media)?.let { loadClipData(uri, it, position) }}
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder = MediaViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_clipdata, parent, false))
        override fun onBindViewHolder(holder: MediaViewHolder, position: Int) { holder.bind(currentList[position], position) }
    }

    class ClipDataDiffCallback: DiffUtil.ItemCallback<Uri>() {
        override fun areItemsTheSame(oldItem: Uri, newItem: Uri): Boolean = oldItem == newItem
        override fun areContentsTheSame(oldItem: Uri, newItem: Uri): Boolean = oldItem == newItem
    }

    class DestinationViewModel: ViewModel() {
        private var destination = SingleLiveEvent<Album?>()
        private var inEditing = false
        private var removeOriginal = false
        private var onServer = false    // Checked by fragment AlbumDetailFragment when doing copy/move among albums
        private var remotePhotos = mutableListOf<NCShareViewModel.RemotePhoto>()

        fun setDestination(newDestination: Album) { destination.value = newDestination }
        fun getDestination(): SingleLiveEvent<Album?> = destination

        fun setEditMode(mode: Boolean) { inEditing = mode }
        fun isEditing() = inEditing

        fun setRemoveOriginal(remove: Boolean) { removeOriginal = remove }
        fun shouldRemoveOriginal() = removeOriginal

        fun setOnServer(onServer: Boolean) { this.onServer = onServer }
        fun doOnServer(): Boolean = onServer
        fun setRemotePhotos(remotePhotos: MutableList<NCShareViewModel.RemotePhoto>) {
            this.remotePhotos.clear()
            this.remotePhotos.addAll(remotePhotos)
        }
        fun getRemotePhotos(): MutableList<NCShareViewModel.RemotePhoto> = remotePhotos
    }

    data class RemoteAlbum(
        var album: Album,
        var sharePath: String,
        var shareBy: String,
    )

    companion object {
        const val KEY_URIS = "KEY_URIS"
        const val KEY_CAN_WRITE = "KEY_CAN_WRITE"
        const val KEY_REMOTE_PHOTO = "KEY_REMOTE_PHOTO"
        const val KEY_IGNORE_ALBUM = "KEY_IGNORE_ALBUM"

        private const val KEY_COPY_OR_MOVE = "KEY_COPY_OR_MOVE"
        private const val KEY_NAME_FILTER = "KEY_NAME_FILTER"

        @JvmName("newInstance1")
        @JvmStatic
        fun newInstance(uris: ArrayList<Uri>, canWrite: Boolean, ignoreAlbumId: String = "") = DestinationDialogFragment().apply {
            arguments = Bundle().apply {
                putParcelableArrayList(KEY_URIS, uris)
                putString(KEY_IGNORE_ALBUM, ignoreAlbumId)
                putBoolean(KEY_CAN_WRITE, canWrite)
            }
        }

        @JvmStatic
        fun newInstance(remotePhotos: ArrayList<NCShareViewModel.RemotePhoto>, ignoreAlbumId: String, canWrite: Boolean) = DestinationDialogFragment().apply {
            arguments = Bundle().apply {
                putParcelableArrayList(KEY_REMOTE_PHOTO, remotePhotos)
                putString(KEY_IGNORE_ALBUM, ignoreAlbumId)
                putBoolean(KEY_CAN_WRITE, canWrite)
            }
        }
    }
}