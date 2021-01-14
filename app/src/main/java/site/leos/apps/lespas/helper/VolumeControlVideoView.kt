package site.leos.apps.lespas.helper

import android.content.Context
import android.media.MediaPlayer
import android.util.AttributeSet
import android.widget.VideoView

class VolumeControlVideoView: VideoView, MediaPlayer.OnPreparedListener {
    constructor(context: Context): super(context)
    constructor(context: Context, attributeSet: AttributeSet): super(context, attributeSet)
    constructor(context: Context, attributeSet: AttributeSet, defStyleAttr: Int): super(context, attributeSet, defStyleAttr)
    constructor(context: Context, attributeSet: AttributeSet, defStyleAttr: Int, defStyleRes: Int): super(context, attributeSet, defStyleAttr, defStyleRes)

    private var mPlayer: MediaPlayer? = null
    private var isMute = false
    // Video restart position
    private var seekWhenPrepare = 0

    init { setOnPreparedListener(this) }

    override fun onPrepared(mp: MediaPlayer) {
        mPlayer = mp

        // Go to last stop position, video will be started in implementation's onSeekCompleteListener
        mp.seekTo(seekWhenPrepare)
    }

    fun mute() {
        try { mPlayer?.setVolume(0f, 0f) } catch (e:IllegalStateException) { e.printStackTrace() }
        isMute = true
    }

    fun unMute() {
        try { mPlayer?.setVolume(1f, 1f) } catch (e:IllegalStateException) { e.printStackTrace() }
        isMute = false
    }

    fun isMute(): Boolean = isMute

    fun setSeekOnPrepare(position: Int) { this.seekWhenPrepare = position }
    fun getSeekOnPrepare(): Int = seekWhenPrepare
}