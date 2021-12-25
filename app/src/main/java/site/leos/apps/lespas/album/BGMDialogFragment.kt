package site.leos.apps.lespas.album

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerControlView
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.LesPasDialogFragment
import site.leos.apps.lespas.helper.Tools
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
class BGMDialogFragment: LesPasDialogFragment(R.layout.fragment_bgm_dialog) {
    private lateinit var album: Album
    private lateinit var bgmMedia: String
    private lateinit var bgmPlayer: ExoPlayer

    private lateinit var playButton: ImageButton
    private lateinit var pauseButton: ImageButton

    private lateinit var replaceBGMLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requireArguments().apply {
            album = getParcelable(KEY_ALBUM)!!
            bgmMedia = "${Tools.getLocalRoot(requireContext())}/${album.id}${BGM_FILE_SUFFIX}"
        }

        replaceBGMLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) {
            it?.let { uri ->
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    File(bgmMedia).outputStream().use { output ->
                        input.copyTo(output, 8192)
                    }
                }

                prepareMedia()
            }
        }
    }

    override fun onDestroy() {
        bgmPlayer.stop()
        bgmPlayer.release()

        super.onDestroy()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.apply {
            findViewById<ImageButton>(R.id.replace_bgm).apply {
                setOnClickListener { replaceBGMLauncher.launch("audio/*")}
            }

            pauseButton = findViewById(R.id.exo_pause)
            playButton = findViewById(R.id.exo_play)

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

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        super.onIsPlayingChanged(isPlaying)

                        playButton.isEnabled = !isPlaying
                        playButton.isVisible = !isPlaying
                        pauseButton.isEnabled = isPlaying
                        pauseButton.isVisible = isPlaying
                    }
                })
                if (hasBGM()) prepareMedia()
                else playButton.isEnabled = false
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
    }

    companion object {
        private const val KEY_ALBUM = "KEY_ALBUM"
        const val BGM_FILE_SUFFIX = "_bgm"

        @JvmStatic
        fun newInstance(album: Album) = BGMDialogFragment().apply { arguments = Bundle().apply { putParcelable(KEY_ALBUM, album) } }
    }
}