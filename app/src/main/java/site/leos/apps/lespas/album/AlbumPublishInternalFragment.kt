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

package site.leos.apps.lespas.album

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.annotation.LayoutRes
import androidx.appcompat.widget.AppCompatAutoCompleteTextView
import androidx.core.view.isNotEmpty
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.Tools.parcelable
import site.leos.apps.lespas.helper.Tools.parcelableArrayList
import site.leos.apps.lespas.publication.NCShareViewModel

class AlbumPublishInternalFragment: Fragment() {
    private val publishModel: NCShareViewModel by activityViewModels()
    private var shareeJob: Job? = null
    private lateinit var currentShare: NCShareViewModel.ShareByMe
    private var currentRecipients = mutableListOf<NCShareViewModel.Recipient>()
    private var selectedSharees = arrayListOf<NCShareViewModel.Sharee>()
    private var allSharees: List<NCShareViewModel.Sharee>? = null

    private lateinit var autoCompleteTextView: AppCompatAutoCompleteTextView
    private lateinit var recipientChipGroup: ChipGroup
    private lateinit var publicationTypeToggleGroup: MaterialButtonToggleGroup
    private lateinit var recipientsScrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //currentShare = arguments?.getParcelable(CURRENT_SHARE)!!
        currentShare = requireArguments().parcelable(CURRENT_SHARE)!!
        currentRecipients = mutableListOf<NCShareViewModel.Recipient>().apply { addAll(currentShare.with.filter { it.sharee.type != NCShareViewModel.SHARE_TYPE_PUBLIC }) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_album_publish_internal, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recipientsScrollView = view.findViewById(R.id.recipients)
        recipientChipGroup = view.findViewById(R.id.recipient_chips)
        publicationTypeToggleGroup = view.findViewById(R.id.publication_type)
        view.findViewById<TextInputLayout>(R.id.recipient_textinputlayout).apply {
            requestFocus()
        }
        autoCompleteTextView = view.findViewById<AppCompatAutoCompleteTextView>(R.id.recipient_textinputedittext).apply {
            setOnEditorActionListener { v, actionId, event ->
                if (actionId == EditorInfo.IME_NULL && event.action == KeyEvent.ACTION_UP) {
                    val input = v.text.toString().lowercase()
                    // TODO user/group selection for new name
                    (allSharees?.find { it.name == input })?.let { s-> addRecipientChip(s, null) } ?: run { addRecipientChip(null, input) }
                }
                true
            }
            setOnItemClickListener { _, _, _, id -> allSharees?.get(id.toInt())?.let { addRecipientChip(it, null) }}
        }

        view.findViewById<MaterialButton>(R.id.cancel_button).setOnClickListener { (parentFragment as AlbumPublishDialogFragment).dismiss() }
        view.findViewById<MaterialButton>(R.id.ok_button).setOnClickListener {
            val newRecipients = mutableListOf<NCShareViewModel.Recipient>().apply { for (s in selectedSharees) add(NCShareViewModel.Recipient("", if (publicationTypeToggleGroup.checkedButtonId == R.id.joint_album) NCShareViewModel.PERMISSION_JOINT else NCShareViewModel.PERMISSION_CAN_READ, 0L, "", s)) }
            val permissionUnChanged = if (currentRecipients.isNotEmpty() && newRecipients.isNotEmpty()) currentRecipients[0].permission == newRecipients[0].permission else false
            val recipientsToRemove = currentRecipients.toMutableList().apply {
                if (permissionUnChanged) {
                    for (old in currentRecipients) {
                        for (new in newRecipients) {
                            if (old.sharee.name == new.sharee.name && old.sharee.type == new.sharee.type) remove(old)
                        }
                    }
                }
            }
            val recipientsToAdd = newRecipients.toMutableList().apply {
                if (permissionUnChanged) {
                    for (new in newRecipients) {
                        for (old in currentRecipients) {
                            if (old.sharee.name == new.sharee.name && old.sharee.type == new.sharee.type) remove(new)
                        }
                    }
                }
            }
            publishModel.updateInternalPublish(NCShareViewModel.ShareByMe(currentShare.fileId, currentShare.folderName, recipientsToAdd.toMutableList()), recipientsToRemove)

            (parentFragment as AlbumPublishDialogFragment).dismiss()
        }

        // Get selected recipients from calling argument or saved instance state
        //(savedInstanceState?.getParcelableArrayList(SELECTED_RECIPIENTS)
        (savedInstanceState?.parcelableArrayList(SELECTED_RECIPIENTS)
            ?: run {
                if (currentRecipients.isNotEmpty()) publicationTypeToggleGroup.check( if (NCShareViewModel.PERMISSION_JOINT == currentRecipients[0].permission) R.id.joint_album else R.id.solo_album)
                arrayListOf<NCShareViewModel.Sharee>().apply {
                    for(recipient in currentRecipients) add(recipient.sharee)
                }
            }
        ).forEach { addRecipientChip(it, null) }

        view.findViewById<MaterialButton>(R.id.unpublish_button).apply {
            isEnabled = recipientChipGroup.isNotEmpty()
            setOnClickListener {
                // Remove all current recipients, hence un-publish current album
                publishModel.updateInternalPublish(NCShareViewModel.ShareByMe(currentShare.fileId, currentShare.folderName, mutableListOf()), currentRecipients)

                (parentFragment as AlbumPublishDialogFragment).dismiss()
            }
        }
    }

    override fun onStart() {
        super.onStart()

        // Fill sharees to auto complete selection
        shareeJob = lifecycleScope.launch { publishModel.sharees.collect {
            it.let {
                allSharees = it
                autoCompleteTextView.setAdapter(RecipientAutoCompleteAdapter(autoCompleteTextView.context, android.R.layout.simple_spinner_dropdown_item, it) { name, view ->
                    publishModel.getAvatar(name, view, null)
                })
            }
        }}
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelableArrayList(SELECTED_RECIPIENTS, selectedSharees)
    }

    override fun onStop() {
        shareeJob?.cancel()

        super.onStop()
    }

    // Show selected recipient in chip group and maintain a array of selected recipients
    @SuppressLint("InflateParams", "RestrictedApi")
    private fun addRecipientChip(sharee: NCShareViewModel.Sharee?, newName: String?) {
        val recipient = sharee ?: run { NCShareViewModel.Sharee(newName!!, newName, NCShareViewModel.SHARE_TYPE_USER) }

        // TODO nextcloud share_by_me api return wrong circle id with it's last character missing
        //selectedSharees.indexOfFirst { it.type == recipient.type && recipient.name == it.name }.let { index->
        selectedSharees.indexOfFirst { it.type == recipient.type && if (it.type == NCShareViewModel.SHARE_TYPE_CIRCLES) recipient.name.contains(it.name) else recipient.name == it.name }.let { index->
            if (index == -1) {
                // selected sharee not in selected recipients group yet
                recipientChipGroup.addView(
                    (LayoutInflater.from(recipientChipGroup.context).inflate(R.layout.chip_recipient, null) as Chip).apply {
                        this.text = recipient.label
                        publishModel.getAvatar(recipient, this, null)
                        setOnClickListener { chipView ->
                            chipView.startAnimation(
                                AlphaAnimation(1f, 0.1f).apply {
                                    duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
                                    setAnimationListener(object : Animation.AnimationListener {
                                        override fun onAnimationRepeat(animation: Animation?) {}
                                        override fun onAnimationStart(animation: Animation?) {}
                                        override fun onAnimationEnd(animation: Animation?) {
                                            with(chipView as Chip) {
                                                selectedSharees.find { i-> i.label == text }?.let { s-> selectedSharees.remove(s) }
                                                recipientChipGroup.removeView(this)
                                            }
                                        }
                                    })
                                }
                            )
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) recipientsScrollView.post { recipientsScrollView.scrollToDescendant(this) }
                        else recipientsScrollView.post { recipientsScrollView.fullScroll(View.FOCUS_DOWN) }
                    }
                )
                selectedSharees.add(recipient)
                //recipientsScrollView.post { recipientsScrollView.fullScroll(View.FOCUS_DOWN) }
            } else {
                // Flash it's chip if sharee is already selected
                recipientChipGroup.getChildAt(index).let {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) recipientsScrollView.post { recipientsScrollView.scrollToDescendant(it) }
                    it.startAnimation(AlphaAnimation(1f, 0f).apply {
                        duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
                        repeatCount = 1
                    })
                }
            }
        }
        autoCompleteTextView.setText("")
    }

    class RecipientAutoCompleteAdapter(context: Context, @LayoutRes private val layoutRes: Int, private val sharees: List<NCShareViewModel.Sharee>, private val avatarLoader: (NCShareViewModel.Sharee, View) -> Unit
    ): ArrayAdapter<NCShareViewModel.Sharee>(context, layoutRes, sharees), Filterable {
        private val matchedColor = Tools.getAttributeColor(context, android.R.attr.colorSecondary)
        private var filteredSharees = sharees
        private var currentInput: String? = ""

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            ((convertView ?: run { LayoutInflater.from(context).inflate(layoutRes, parent, false) }) as TextView).apply {
                val sharee = filteredSharees[position]
                text = SpannableString("${sharee.label}(${if (sharee.type != NCShareViewModel.SHARE_TYPE_CIRCLES) sharee.name else sharee.label})").apply {
                    val startPos = indexOfLast { it == '(' }
                    //setSpan(StyleSpan(Typeface.ITALIC), startPos, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(ForegroundColorSpan(Color.GRAY), startPos, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

                    // Mark matched string in sharee's label
                    var matchedStartPos =  if (!currentInput.isNullOrEmpty()) indexOf(currentInput!!, 0, true) else -1
                    if (matchedStartPos > startPos) matchedStartPos = -1
                    if (matchedStartPos != -1) setSpan(ForegroundColorSpan(matchedColor), matchedStartPos, matchedStartPos + currentInput!!.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    // Mark matched string in sharee's name
                    matchedStartPos =  if (!currentInput.isNullOrEmpty()) indexOf(currentInput!!, startPos, true) else -1
                    if (matchedStartPos != -1) setSpan(ForegroundColorSpan(matchedColor), matchedStartPos, matchedStartPos + currentInput!!.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

                    compoundDrawablePadding = context.resources.getDimension(R.dimen.small_padding).toInt()
                }
                avatarLoader(sharee, this)
            }

        @Suppress("UNCHECKED_CAST")
        override fun getFilter(): Filter = object : Filter() {
            // Show only the sharee unique name as auto complete result
            override fun convertResultToString(resultValue: Any?): CharSequence = (resultValue as NCShareViewModel.Sharee).name

            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val query = constraint?.toString()?.lowercase()
                currentInput = query

                return FilterResults().apply {
                    values = if (query.isNullOrEmpty()) sharees else sharees.filter { it.label.lowercase().contains(query) || it.name.lowercase().contains(query) }
                    count = (values as List<NCShareViewModel.Sharee>).size
                }
            }

            override fun publishResults(constraint: CharSequence?, results: FilterResults) {
                filteredSharees = results.values as List<NCShareViewModel.Sharee>
                if (results.count > 0) notifyDataSetChanged() else notifyDataSetInvalidated()
            }
        }

        override fun getCount(): Int = filteredSharees.size
        override fun getItem(position: Int): NCShareViewModel.Sharee = filteredSharees[position]
        // Provide item's position in all recipients array as item's unique ID
        override fun getItemId(position: Int): Long = sharees.indexOf(filteredSharees[position]).toLong()
    }

    companion object {
        private const val SELECTED_RECIPIENTS = "SELECTED_RECIPIENTS"
        private const val CURRENT_SHARE = "CURRENT_SHARE"

        @JvmStatic
        fun newInstance(currentShare: NCShareViewModel.ShareByMe) = AlbumPublishInternalFragment().apply {
            arguments = Bundle().apply { putParcelable(CURRENT_SHARE, currentShare) }
        }
    }
}