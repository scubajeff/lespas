package site.leos.apps.lespas.sync

import android.content.DialogInterface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.AlbumNameAndId
import site.leos.apps.lespas.album.AlbumViewModel
import java.util.regex.Pattern

class DestinationDialogFragment : DialogFragment() {
    private lateinit var albumAdapter: DestinationAdapter
    private val albumNameModel: AlbumViewModel by viewModels()
    private val destinationModel: DestinationViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setStyle(STYLE_NO_TITLE, R.style.Theme_LesPas_Dialog)

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
            addTextChangedListener(object: TextWatcher {
                val slashesPattern =  Pattern.compile("[^\\\\/]+(?<![.])\\z")
                val devPattern = Pattern.compile("\\A(?!(?:COM[0-9]|CON|LPT[0-9]|NUL|PRN|AUX|com[0-9]|con|lpt[0-9]|nul|prn|aux)|\\s{2,}).{1,254}(?<![.])\\z")
                val leadingDotPattern = Pattern.compile("^\\..*")

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { error = null }
                override fun afterTextChanged(s: Editable?) {
                    val txt = s!!.toString()

                    if (txt.isEmpty()) error = null
                    else if (txt.length > 200) name_textinputedittext.error = getString(R.string.name_too_long)
                    else if (!slashesPattern.matcher(txt).matches()) name_textinputedittext.error = getString(R.string.invalid_character_found)
                    else if (!devPattern.matcher(txt).matches()) name_textinputedittext.error = getString(R.string.invalid_name_found)
                    else if (leadingDotPattern.matcher(txt).matches()) name_textinputedittext.error = getString(R.string.leading_dots_found)
                }
            })
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

    override fun onResume() {
        // Set dialog width to a fixed ration of screen width
        val width = (resources.displayMetrics.widthPixels * resources.getInteger(R.integer.dialog_width_ratio) / 100)
        dialog!!.window!!.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)

        super.onResume()
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)

        // If called by ShareReceiverActivity, quit immediately, otherwise return normally
        if (tag == ShareReceiverActivity.TAG_DESTINATION_DIALOG) activity?.apply {
            finish()
            overridePendingTransition(0, 0)
        }

        // Clear editing mode
        destinationModel.setEditMode(false)
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