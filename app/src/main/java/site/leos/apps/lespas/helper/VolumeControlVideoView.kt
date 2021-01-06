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

    private lateinit var mPlayer: MediaPlayer
    private var isMute = false

    init { setOnPreparedListener(this) }

    override fun onPrepared(mp: MediaPlayer) { mPlayer = mp }

    fun mute() {
        mPlayer.setVolume(0f, 0f)
        isMute = true
    }

    fun unMute() {
        mPlayer.setVolume(1f, 1f)
        isMute = false
    }

    fun isMute(): Boolean = isMute
}