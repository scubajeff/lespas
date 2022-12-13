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

package site.leos.apps.lespas.gpx

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.FileNameValidator
import site.leos.apps.lespas.helper.LesPasDialogFragment
import site.leos.apps.lespas.helper.Tools.parcelableArray
import site.leos.apps.lespas.photo.Photo
import java.io.File
import java.time.OffsetDateTime
import java.util.*

class GPXExportDialogFragment: LesPasDialogFragment(R.layout.fragment_gpx_export_dialog) {
    private lateinit var filenameLayout: TextInputLayout
    private lateinit var filenameEditText: TextInputEditText
    private lateinit var writePermissionRequestLauncher: ActivityResultLauncher<String>
    private lateinit var fileSuffix: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fileSuffix = getString(R.string.gpx_suffix)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
            writePermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted -> if (isGranted) exportGPXThenDismiss(filenameEditText.text.toString().trim()) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        filenameLayout = view.findViewById(R.id.filename_textinputlayout)
        filenameEditText = view.findViewById<TextInputEditText>(R.id.filename_textinputedittext).apply {
            if (savedInstanceState == null) {
                setText(requireArguments().getString(ALBUM_NAME)!!)
                requestFocus()
            }

            addTextChangedListener(FileNameValidator(this, arrayListOf()) { hasError -> filenameLayout.suffixText = if (hasError) null else fileSuffix })
            setOnEditorActionListener { view, actionId, keyEvent ->
                if (actionId == EditorInfo.IME_ACTION_GO || keyEvent.keyCode == KeyEvent.KEYCODE_ENTER) {
                    // Trim the leading and trailing blank
                    this.text.toString().trim().let { filename -> if (filename.isNotEmpty()) createGPX(filename) }
                    hideSoftKeyboard(view)
                }
                true
            }
        }

        view.findViewById<MaterialButton>(R.id.cancel_button).setOnClickListener {
            hideSoftKeyboard(it)
            dismiss()
        }
        view.findViewById<MaterialButton>(R.id.ok_button).setOnClickListener {
            hideSoftKeyboard(it)
            filenameEditText.text.toString().trim().let { filename -> if (filename.isNotEmpty()) createGPX(filename) }
        }
    }

    private fun createGPX(filename: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            // Request WRITE_EXTERNAL_STORAGE if needed when running on Android 9 or below
            writePermissionRequestLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else exportGPXThenDismiss(filename)
    }

    private fun exportGPXThenDismiss(filename: String) {
        // TODO might need to put this in long running job
        try {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), filename + fileSuffix).outputStream().use {
                it.write(
                    String.format(GPX_XML.trimIndent(),
                        String.format(GPX_TRACK.trimIndent(),
                            requireArguments().getString(ALBUM_NAME),
                            run {
                                val defaultOffset = OffsetDateTime.now().offset
                                var trackPoints = ""
                                requireArguments().parcelableArray<Photo>(PHOTOS)?.filter { photo -> photo.latitude != Photo.NO_GPS_DATA }?.forEach { photo ->
                                    trackPoints += String.format(Locale.ROOT, GPX_TRACK_POINT,
                                        photo.longitude, photo.latitude,
                                        if (photo.altitude != Photo.NO_GPS_DATA) String.format(Locale.ROOT, GPX_TRACK_POINT_ELEVATION, photo.altitude) else "",
                                        photo.dateTaken.toInstant(defaultOffset).toString()
                                    )
                                }
                                // Remove last line break
                                if (trackPoints.isNotEmpty()) trackPoints.dropLast(1) else trackPoints
                            }
                        )
                    ).toByteArray(Charsets.UTF_8)
                )
            }
        } catch (_: Exception) {}

        dismiss()
    }

    private fun hideSoftKeyboard(view: View) { (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).run { hideSoftInputFromWindow(view.windowToken, 0) }}

    companion object {
        const val MIMETYPE_GPX = "application/octet-stream"
        private const val GPX_XML =
            """
                <?xml version="1.0" encoding="UTF-8"?>
                <gpx 
                  version="1.1"
                  creator="Les Pas - https://github.com/scubajeff/lespas#readme"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  xmlns="http://www.topografix.com/GPX/1/1"
                  xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd"
                  xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
                %s
                </gpx>
            """
        private const val GPX_TRACK =
            """
            <trk>
              <name>%s</name>
              <trkseg>
            %s
              </trkseg>
            </trk>
            """
        private const val GPX_TRACK_POINT = "    <trkpt lon=\"%f\" lat=\"%f\">%s<time>%s</time></trkpt>\n"
        private const val GPX_TRACK_POINT_ELEVATION = "<ele>%f</ele>"
        //const val GPX_ELEMENT = "<wpt lat=\"%f\" lon=\"%f\"><time>%t</time><cmt>%s</cmt></wpt>"

        private const val ALBUM_NAME = "ALBUM_NAME"
        private const val PHOTOS = "PHOTOS"

        @JvmStatic
        fun newInstance(albumName: String, photos: List<Photo>) = GPXExportDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ALBUM_NAME, albumName)
                putParcelableArray(PHOTOS, photos.toTypedArray())
            }
        }
    }
}