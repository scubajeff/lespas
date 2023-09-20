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

import android.os.Bundle
import android.view.View
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.LesPasDialogFragment
import site.leos.apps.lespas.photo.PhotoSidecar
import site.leos.apps.lespas.sync.ActionViewModel

class MetaRescanDialogFragment: LesPasDialogFragment(R.layout.fragment_meta_rescan_dialog) {
    private lateinit var albums: Array<String>
    private lateinit var captionButton: MaterialButton
    private lateinit var locationButton: MaterialButton
    private lateinit var dateButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        albums = requireArguments().getStringArray(ARGUMENT_ALBUMS)!!
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        captionButton = view.findViewById(R.id.caption)
        locationButton = view.findViewById(R.id.location)
        dateButton = view.findViewById(R.id.taken_date)
        view.findViewById<MaterialButton>(R.id.ok_button).apply {
            setOnClickListener {
                ViewModelProvider(requireActivity())[ActionViewModel::class.java].rescan(albums.toList(), captionButton.isChecked, locationButton.isChecked, dateButton.isChecked)
                dismiss()
            }
        }
        view.findViewById<MaterialButton>(R.id.cancel_button).apply { setOnClickListener { dismiss() }}
    }

    data class Sidecar(
        val restoreCaption: Boolean,
        val restoreLocation: Boolean,
        val restoreTakenDate: Boolean,
        val photoSidecarData: List<PhotoSidecar>
    ): java.io.Serializable

    companion object {
        private const val ARGUMENT_ALBUMS = "ARGUMENT_ALBUMS"

        @JvmStatic
        fun newInstance(photos: List<String>) = MetaRescanDialogFragment().apply { arguments = Bundle().apply { putStringArray(ARGUMENT_ALBUMS, photos.toTypedArray()) } }
    }
}