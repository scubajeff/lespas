package site.leos.apps.lespas.sync

import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.TransitionInflater
import androidx.transition.TransitionManager
import com.google.android.material.color.MaterialColors
import kotlinx.android.synthetic.main.fragment_destination_dialog.*
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumViewModel
import site.leos.apps.lespas.helper.AlbumNameValidator
import site.leos.apps.lespas.helper.DialogShapeDrawable
import site.leos.apps.lespas.helper.ImageLoaderViewModel
import site.leos.apps.lespas.photo.Photo
import java.time.LocalDateTime

class DestinationDialogFragment : DialogFragment() {
    private lateinit var albumAdapter: DestinationAdapter
    private val albumNameModel: AlbumViewModel by viewModels()
    private val destinationModel: DestinationViewModel by activityViewModels()
    private val imageLoaderModel: ImageLoaderViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        albumAdapter = DestinationAdapter(
            { album ->
                if (album.id.isEmpty()) {
                    destinationModel.setEditMode(true)

                    TransitionManager.beginDelayedTransition(root, TransitionInflater.from(requireContext()).inflateTransition(R.transition.destination_dialog_new_album))
                    dialog_title_textview.text = getString(R.string.create_new_album)
                    destination_recyclerview.visibility = View.GONE
                    new_album_textinputlayout.apply {
                        visibility = View.VISIBLE
                        requestFocus()
                    }
                }
                // User choose an existing album
                else {
                    destinationModel.setDestination(album)
                    dismiss()
                }
            },
            { photo, view, type -> imageLoaderModel.loadPhoto(photo, view, type) }
        )
        albumNameModel.allAlbumsByEndDate.observe(this, { albums ->
            albumAdapter.setDestinations(albums)
            //albumAdapter.setCoverType(tag == ShareReceiverActivity.TAG_DESTINATION_DIALOG || tag == CameraRollActivity.TAG_DESTINATION_DIALOG)
            albumAdapter.setCoverType(tag == ShareReceiverActivity.TAG_DESTINATION_DIALOG)
        })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_destination_dialog, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        shape_background.background = DialogShapeDrawable.newInstance(requireContext(), DialogShapeDrawable.NO_STROKE)
        //root.background = DialogShapeDrawable.newInstance(requireContext(), resources.getColor(R.color.color_primary_variant, null))
        root.background = DialogShapeDrawable.newInstance(requireContext(), MaterialColors.getColor(view, R.attr.colorPrimaryVariant))
        destination_recyclerview.adapter = albumAdapter
        name_textinputedittext.run {
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_NULL) {
                    // Validate the name
                    val name = name_textinputedittext.text.toString().trim()    // Trim the leading and trailing blank

                    if (error != null)
                    else if (name.isEmpty())
                    else if (isAlbumExisted(name)) name_textinputedittext.error = getString(R.string.album_existed)
                    else {
                        // Return with album id field empty, calling party will know this is a new album
                        destinationModel.setDestination(Album("", name,
                            LocalDateTime.MAX, LocalDateTime.MIN, "", 0, 0, 0, LocalDateTime.now(), Album.BY_DATE_TAKEN_ASC, "", 0, 1f))
                        dismiss()
                    }
                    true
                } else false
            }
            addTextChangedListener(AlbumNameValidator(this, context))
        }

        // Maintain current mode after screen rotation
        if (destinationModel.isEditing()) {
            dialog_title_textview.text = getString(R.string.create_new_album)
            destination_recyclerview.visibility = View.GONE
            new_album_textinputlayout.apply {
                visibility = View.VISIBLE
                requestFocus()
            }
        }
    }

    override fun onStart() {
        super.onStart()

        dialog!!.window!!.apply {
            // Set dialog width to a fixed ration of screen width
            val width = (resources.displayMetrics.widthPixels * resources.getInteger(R.integer.dialog_width_ratio) / 100)
            setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
            attributes.apply {
                dimAmount = 0.6f
                flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            }

            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setWindowAnimations(R.style.Theme_LesPas_Dialog_Animation)
        }

        dialog?.setCanceledOnTouchOutside(false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        destination_recyclerview.adapter = null
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)

        // Clear editing mode
        destinationModel.setEditMode(false)

        // If called by ShareReceiverActivity, quit immediately, otherwise return normally
        if (tag == ShareReceiverActivity.TAG_DESTINATION_DIALOG) activity?.finish()
    }

    private fun isAlbumExisted(name: String): Boolean {
        var existed = false
        albumNameModel.allAlbumsByEndDate.value!!.forEach { if (it.name == name) existed = true }
        return existed
    }

    class DestinationAdapter(private val itemClickListener: OnItemClickListener, private val imageLoader: OnLoadImage): RecyclerView.Adapter<DestinationAdapter.DestViewHolder>() {
        private var destinations = emptyList<Album>()
        private var covers = mutableListOf<Photo>()
        private var coverType: String = ImageLoaderViewModel.TYPE_SMALL_COVER

        fun interface OnItemClickListener {
            fun onItemClick(album: Album)
        }

        fun interface OnLoadImage {
            fun loadImage(photo: Photo, view: ImageView, type: String)
        }

        inner class DestViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            fun bindViewItems(position: Int, clickListener: OnItemClickListener) {
                with(itemView) {
                    if (position == destinations.size) {
                        findViewById<AppCompatImageView>(R.id.cover).apply {
                            setBackgroundColor(MaterialColors.getColor(this, R.attr.colorSurface))
                            setImageResource(R.drawable.ic_baseline_add_24)
                            scaleType = ImageView.ScaleType.FIT_CENTER
                        }
                        findViewById<AppCompatTextView>(R.id.name).text = resources.getString(R.string.create_new_album)
                        setOnClickListener { clickListener.onItemClick(
                            Album("", "",
                                LocalDateTime.MAX, LocalDateTime.MIN, "", 0, 0, 0, LocalDateTime.now(), Album.BY_DATE_TAKEN_ASC, "", 0, 0f)
                        )}
                    } else {
                        findViewById<AppCompatImageView>(R.id.cover).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            //setBackgroundColor(resources.getColor(R.color.color_secondary_variant, null))
                            setBackgroundColor(MaterialColors.getColor(this, R.attr.colorSurface))
                            imageLoader.loadImage(covers[position], this, coverType)
                        }
                        findViewById<AppCompatTextView>(R.id.name).text = destinations[position].name
                        setOnClickListener { clickListener.onItemClick(destinations[position]) }
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DestViewHolder {
            return DestViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_destination, parent, false))
        }

        override fun onBindViewHolder(holder: DestViewHolder, position: Int) {
            holder.bindViewItems(position, itemClickListener)
        }

        override fun getItemCount(): Int = destinations.size + 1

        fun setDestinations(destinations: List<Album>) {
            this.destinations = destinations
            covers.clear()
            this.destinations.forEach { covers.add(Photo(it.cover, it.id, it.name, "", it.startDate, it.endDate, it.coverWidth, it.coverHeight, "", it.coverBaseline)) }
            notifyDataSetChanged()
        }

        fun setCoverType(smallCover: Boolean) {
            coverType = if (smallCover) ImageLoaderViewModel.TYPE_SMALL_COVER else ImageLoaderViewModel.TYPE_COVER
        }
    }

    class DestinationViewModel: ViewModel() {
        private var destination = MutableLiveData<Album>()
        private var inEditing = false

        fun resetDestination() { destination.value = null }
        fun setDestination(newDestination: Album) { this.destination.value = newDestination }
        fun getDestination(): LiveData<Album> = destination
        fun setEditMode(mode: Boolean) { inEditing = mode }
        fun isEditing() = inEditing
    }

    companion object {
        fun newInstance() = DestinationDialogFragment()
    }
}
/*
                    // User want to create a new album, present them a edittext to get the name
                    // Fade out destination listview
                    destination_recyclerview.run {
                        alpha = 1f
                        translationY = 0f
                        animate().alpha(0f).translationY(-100f).setDuration(500).setInterpolator(AccelerateInterpolator())
                            .setListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: Animator?) {
                                    super.onAnimationEnd(animation)
                                    new_album_textinputlayout.run {
                                        // Hold the edittext space first
                                        visibility = View.VISIBLE
                                        alpha = 0f
                                    }
                                    destination_recyclerview.visibility = View.GONE
                                    dialog_title_textview.text = getString(R.string.create_new_album)

                                    // Fade in new album edittext
                                    new_album_textinputlayout.run {
                                        animate().alpha(1f).setDuration(200).setInterpolator(DecelerateInterpolator())
                                            .setListener(object : AnimatorListenerAdapter() {
                                                override fun onAnimationEnd(animation: Animator?) {
                                                    super.onAnimationEnd(animation)
                                                    new_album_textinputlayout.requestFocus()
                                                }
                                            })
                                    }
                                }
                        })
                    }

 */