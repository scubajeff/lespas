package site.leos.apps.lespas.tflite

import android.graphics.Bitmap
import android.graphics.RectF

interface Detector {
    fun recognizeImage(bitmap: Bitmap): List<Recognition>
    fun close()

    /** An immutable result returned by a Detector describing what was recognized.  */
    class Recognition(
        /**
         * A unique identifier for what has been recognized. Specific to the class, not the instance of
         * the object.
         */
        val id: String,
        /** Display name for the recognition.  */
        val title: String,
        /**
         * A sortable score for how good the recognition is relative to others. Higher should be better.
         */
        val confidence: Float,
        /** Optional location within the source image for the location of the recognized object.  */
        private var location: RectF
    ) {
        fun getLocation(): RectF = RectF(location)
        fun setLocation(location: RectF) { this.location = location }
    }
}