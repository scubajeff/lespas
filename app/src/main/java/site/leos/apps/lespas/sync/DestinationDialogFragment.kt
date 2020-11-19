package site.leos.apps.lespas.sync

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
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
import kotlinx.android.synthetic.main.fragment_destination_dialog.*
import site.leos.apps.lespas.AlbumNameValidator
import site.leos.apps.lespas.DialogShapeDrawable
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.AlbumNameAndId
import site.leos.apps.lespas.album.AlbumViewModel

class DestinationDialogFragment : DialogFragment() {
    private lateinit var albumAdapter: DestinationAdapter
    private val albumNameModel: AlbumViewModel by viewModels()
    private val destinationModel: DestinationViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        albumAdapter = DestinationAdapter(object : DestinationAdapter.OnItemClickListener {
            override fun onItemClick(album: AlbumNameAndId) {
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
            }
        })
        albumNameModel.allAlbumNamesAndIds.observe(this, { albums -> albumAdapter.setDestinations(albums) })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_destination_dialog, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        root.background = DialogShapeDrawable.newInstance(requireContext(), resources.getColor(R.color.color_primary_variant))
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
                        destinationModel.setDestination(AlbumNameAndId("", name))
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

            setBackgroundDrawable(DialogShapeDrawable.newInstance(context, DialogShapeDrawable.NO_STROKE))
            setWindowAnimations(R.style.Theme_LesPas_Dialog_Animation)
        }
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
        albumNameModel.allAlbumNamesAndIds.value!!.forEach { if (it.name == name) existed = true }
        return existed
    }

    class DestinationAdapter(private val itemClickListener: OnItemClickListener): RecyclerView.Adapter<DestinationAdapter.DestViewHolder>() {
        private var destinations = emptyList<AlbumNameAndId>()

        interface OnItemClickListener {
            fun onItemClick(album: AlbumNameAndId)
        }

        inner class DestViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            fun bindViewItems(position: Int, clickListener: OnItemClickListener) {
                itemView.run {
                    if (position == destinations.size) {
                        findViewById<AppCompatImageView>(R.id.cover).apply {
                            setImageResource(R.drawable.ic_baseline_add_24)
                            setColorFilter(0x89000000.toInt(), android.graphics.PorterDuff.Mode.MULTIPLY)   // #89000000 is android's secondaryTextColor, matching text color setting in layout
                        }
                        findViewById<AppCompatTextView>(R.id.name).apply {
                            text = resources.getString(R.string.create_new_album)

                        }
                        setOnClickListener { clickListener.onItemClick(AlbumNameAndId("", "")) }
                    } else {
                        findViewById<AppCompatImageView>(R.id.cover).apply {
                            visibility = View.VISIBLE
                            setImageResource(R.drawable.ic_footprint)
                            clearColorFilter()
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

        fun setDestinations(destinations: List<AlbumNameAndId>) {
            this.destinations = destinations
            notifyDataSetChanged()
        }
    }

    class DestinationViewModel: ViewModel() {
        private var destination = MutableLiveData<AlbumNameAndId>()
        private var inEditing = false

        fun setDestination(newDestination: AlbumNameAndId) { this.destination.value = newDestination }
        fun getDestination(): LiveData<AlbumNameAndId> = destination
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