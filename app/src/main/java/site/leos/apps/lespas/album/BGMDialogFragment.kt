/*
 *   Copyright 2019 Jeffrey Liu (scubajeffrey@criptext.com)
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

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerControlView
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.LesPasDialogFragment
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.sync.ActionViewModel
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
class BGMDialogFragment: LesPasDialogFragment(R.layout.fragment_bgm_dialog) {
    private lateinit var album: Album
    private lateinit var bgmMedia: String
    private lateinit var bgmPlayer: ExoPlayer

    private lateinit var playButton: ImageButton
    private lateinit var removeButton: ImageButton

    private lateinit var replaceBGMLauncher: ActivityResultLauncher<String>
    private var mimeType = ""

    private lateinit var readAudioPermissionRequestLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requireArguments().apply {
            album = getParcelable(KEY_ALBUM)!!
            bgmMedia = "${Tools.getLocalRoot(requireContext())}/${album.id}${BGM_FILE_SUFFIX}"
        }

        replaceBGMLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) {
            it?.let { uri ->
                requireContext().contentResolver.run {
                    openInputStream(uri)?.use { input -> File(bgmMedia).outputStream().use { output -> input.copyTo(output, 8192) }}
                    mimeType = getType(uri) ?: GENERAL_AUDIO_MIMETYPE
                }

                prepareMedia()
                bgmPlayer.play()
            }
        }

        readAudioPermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) replaceBGMLauncher.launch(GENERAL_AUDIO_MIMETYPE)
        }
    }

    override fun onDestroy() {
        bgmPlayer.stop()
        bgmPlayer.release()

        if (mimeType.isNotEmpty()) ViewModelProvider(requireActivity())[ActionViewModel::class.java].updateBGM(album.name, mimeType, bgmMedia.substringAfterLast('/'))

        super.onDestroy()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.apply {
            findViewById<ImageButton>(R.id.replace_bgm).setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) readAudioPermissionRequestLauncher.launch(android.Manifest.permission.READ_MEDIA_AUDIO)
                else replaceBGMLauncher.launch(GENERAL_AUDIO_MIMETYPE)
            }
            playButton = findViewById(R.id.exo_play)
            removeButton = findViewById<ImageButton>(R.id.remove_bgm).apply {
                setOnClickListener {
                    bgmPlayer.stop()
                    bgmPlayer.playWhenReady = false
                    bgmPlayer.clearMediaItems()
                    playButton.isEnabled = false
                    removeButton.isEnabled = false
                    mimeType = ""

                    File(bgmMedia).delete()
                    ViewModelProvider(requireActivity())[ActionViewModel::class.java].removeBGM(album.name)
                }
            }

            bgmPlayer = ExoPlayer.Builder(requireContext()).build()
            bgmPlayer.apply {
                repeatMode = ExoPlayer.REPEAT_MODE_OFF
                playWhenReady = false
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        super.onPlaybackStateChanged(playbackState)

                        if (playbackState == Player.STATE_ENDED) {
                            bgmPlayer.seekTo(0)
                            bgmPlayer.stop()
                        }
                    }
                })
                if (hasBGM()) prepareMedia()
                else {
                    playButton.isEnabled = false
                    removeButton.isEnabled = false
                }
            }

            findViewById<PlayerControlView>(R.id.bgm_control_view).apply {
                player = bgmPlayer
                showTimeoutMs = 0
            }
        }
    }

    private fun hasBGM(): Boolean = File(bgmMedia).exists()
    private fun prepareMedia() {
        bgmPlayer.setMediaItem(MediaItem.fromUri(bgmMedia))
        bgmPlayer.prepare()
        playButton.isEnabled = true
        removeButton.isEnabled = true
    }

    companion object {
        const val BGM_FILE_SUFFIX = "_bgm"

        private const val GENERAL_AUDIO_MIMETYPE = "audio/*"
        private const val KEY_ALBUM = "KEY_ALBUM"

        @JvmStatic
        fun newInstance(album: Album) = BGMDialogFragment().apply { arguments = Bundle().apply { putParcelable(KEY_ALBUM, album) } }
    }
}