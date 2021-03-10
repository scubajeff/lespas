package site.leos.apps.lespas.tflite

import android.content.res.AssetManager
import android.graphics.*
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.lang.Integer.max
import java.lang.Integer.min
import java.nio.channels.FileChannel
import java.util.*
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.pow
import kotlin.math.sqrt


object TensorUtils {
    // Load Tensorflow Lite model file
    // Asset should be non compressed
    fun loadInterpreter(assets: AssetManager, modelFileName: String, threads: Int): Interpreter? {
        val compatibleList = CompatibilityList()
        val options = Interpreter.Options()
        if (compatibleList.isDelegateSupportedOnThisDevice) {
            options.addDelegate(GpuDelegate(compatibleList.bestOptionsForThisDevice))
        } else {
            options.setNumThreads(threads)
            options.setUseXNNPACK(true)
        }

        return try {
            Interpreter(with(assets.openFd(modelFileName)) { FileInputStream(fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength) }, options)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun copyBitmap(bitmap: Bitmap): Bitmap? {
        return try {
            bitmap.copy(bitmap.config, true)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun faceAlign(bitmap: Bitmap, landmarks: Array<Point>): Bitmap {
        val diffEyeX = (landmarks[1].x - landmarks[0].x).toFloat()
        val diffEyeY = (landmarks[1].y - landmarks[0].y).toFloat()
        val fAngle: Float = if (abs(diffEyeY) < 1e-7) 0f else (atan((diffEyeY / diffEyeX).toDouble()) * 180.0f / Math.PI).toFloat()
        val matrix = Matrix()
        matrix.setRotate(-fAngle)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun faceAlignScale(bitmap: Bitmap, landmarks: Array<Point>): Bitmap {
        val diffEyeX = (landmarks[1].x - landmarks[0].x).toFloat()
        val diffEyeY = (landmarks[1].y - landmarks[0].y).toFloat()
        val fAngle: Float = if (abs(diffEyeY) < 1e-7) 0f else (atan((diffEyeY / diffEyeX).toDouble()) * 180.0f / Math.PI).toFloat()
        val matrix = Matrix()
        matrix.setRotate(-fAngle)
        matrix.postScale(112f / bitmap.width, 112f / bitmap.height)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun drawRect(bitmap: Bitmap, rect: Rect, thick: Int) {
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint()
            val r = 255
            val g = 255
            val b = 0
            paint.color = Color.rgb(r, g, b)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = thick.toFloat()
            canvas.drawRect(rect, paint)
            //Log.i("Util","[*]draw rect");
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun drawPoints(bitmap: Bitmap, landmark: Array<Point>, thick: Int) {
        for (i in landmark.indices) {
            val x: Int = landmark[i].x
            val y: Int = landmark[i].y
            drawRect(bitmap, Rect(x - 1, y - 1, x + 1, y + 1), thick)
        }
    }

    fun drawBox(bitmap: Bitmap, box: Box, thick: Int) {
        drawRect(bitmap, box.transform2Rect(), thick)
        drawPoints(bitmap, box.landmark, thick)
    }

    //Flip diagonal
    fun flipDiagonal(data: FloatArray, height: Int, width: Int, stride: Int) {
        val tmp = FloatArray(width * height * stride)
        for (i in 0 until width * height * stride) tmp[i] = data[i]
        for (y in 0 until height) for (x in 0 until width) {
            for (z in 0 until stride) data[(x * height + y) * stride + z] = tmp[(y * width + x) * stride + z]
        }
    }

    //src转为二维存放到dst中
    fun expand(src: FloatArray, dst: Array<FloatArray>) {
        var idx = 0
        for (y in dst.indices) for (x in dst[0].indices) dst[y][x] = src[idx++]
    }

    //src转为三维存放到dst中
    fun expand(src: FloatArray, dst: Array<Array<FloatArray>>) {
        var idx = 0
        for (y in dst.indices) for (x in dst[0].indices) for (c in dst[0][0].indices) dst[y][x][c] = src[idx++]
    }

    //dst=src[:,:,1]
    fun expandProb(src: FloatArray, dst: Array<FloatArray>) {
        var idx = 0
        for (y in dst.indices) for (x in dst[0].indices) dst[y][x] = src[idx++ * 2 + 1]
    }

    //box转化为rect
    fun boxes2rects(boxes: Vector<Box>): Array<Rect> {
        var cnt = 0
        for (i in 0 until boxes.size) if (!boxes[i].deleted) cnt++
        val r =  Array(cnt) { Rect() }
        var idx = 0
        for (i in 0 until boxes.size) if (!boxes[i].deleted) r[idx++] = boxes[i].transform2Rect()
        return r
    }

    //删除做了delete标记的box
    fun updateBoxes(boxes: Vector<Box>): Vector<Box> {
        val b: Vector<Box> = Vector<Box>()
        for (i in 0 until boxes.size) if (!boxes[i].deleted) b.addElement(boxes[i])
        return b
    }

    //按照rect的大小裁剪出人脸
    fun crop(bitmap: Bitmap, rect: Rect): Bitmap {
        return Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top)
    }

    //rect上下左右各扩展pixels个像素
    fun rectExtend(bitmap: Bitmap, rect: Rect, pixels: Int) {
        rect.left = max(0, rect.left - pixels)
        rect.right = min(bitmap.width - 1, rect.right + pixels)
        rect.top = max(0, rect.top - pixels)
        rect.bottom = min(bitmap.height - 1, rect.bottom + pixels)
    }

    fun resize(bitmap: Bitmap, new_width: Int): Bitmap {
        val scale = new_width.toFloat() / bitmap.width
        val matrix = Matrix()
        matrix.postScale(scale, scale)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun l2Normalize(embeddings: FloatArray, epsilon: Double) {
        var squareSum = 0f

        for (element in embeddings) {
            squareSum += element.toDouble().pow(2.0).toFloat()
        }
        val xInvNorm = sqrt(kotlin.math.max(squareSum.toDouble(), epsilon)).toFloat()
        for (j in embeddings.indices) {
            embeddings[j] = embeddings[j] / xInvNorm
        }
    }
}