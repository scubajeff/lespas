package site.leos.apps.lespas.tflite

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min


class ObjectDetectionModel(assetManager: AssetManager): Detector {
    // Pre-allocated buffers.
    private val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)

    // outputLocations: array of shape [Batchsize, NUM_DETECTIONS,4]
    // contains the location of detected boxes
    private var outputLocations = Array(1) { Array(NUM_DETECTIONS) { FloatArray(4) }}

    // outputClasses: array of shape [Batchsize, NUM_DETECTIONS]
    // contains the classes of detected boxes
    private var outputClasses = Array(1) { FloatArray(NUM_DETECTIONS) }

    // outputScores: array of shape [Batchsize, NUM_DETECTIONS]
    // contains the scores of detected boxes
    private var outputScores = Array(1) { FloatArray(NUM_DETECTIONS) }

    // numDetections: array of shape [Batchsize]
    // contains the number of detected boxes
    private var numDetections = FloatArray(1) { 0f }

    private val imgData = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * if (IS_MODEL_QUANTIZED) 1 else 4).apply { order(ByteOrder.nativeOrder()) }

    private var odInterpreter: Interpreter? = TensorUtils.loadInterpreter(assetManager, MODEL_OBJECT_DETECT, NUM_THREADS)

    override fun recognizeImage(bitmap: Bitmap): List<Detector.Recognition> {
        // Log this method so that it can be analyzed with systrace.
        //Trace.beginSection("recognizeImage")
        //Trace.beginSection("preprocessBitmap")

        // Preprocess the image data from 0-255 int to normalized float based on the provided parameters.
        Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true).getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        imgData.rewind()
        for (i in 0 until INPUT_SIZE) {
            for (j in 0 until INPUT_SIZE) {
                val pixelValue: Int = pixels[i * INPUT_SIZE + j]

                if (IS_MODEL_QUANTIZED) {
                    // Quantized model
                    imgData.put(((pixelValue shr 16) and 0xFF).toByte())
                    imgData.put(((pixelValue shr 8) and 0xFF).toByte())
                    imgData.put((pixelValue and 0xFF).toByte())
                } else {
                    imgData.putFloat((((pixelValue shr 16) and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
                    imgData.putFloat((((pixelValue shr 8) and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
                    imgData.putFloat(((pixelValue and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
                }
            }
        }

        //Trace.endSection() // preprocessBitmap

        // Copy the input data into TensorFlow.
        //Trace.beginSection("feed")
        val inputArray = arrayOf(imgData)
        val outputMap: MutableMap<Int, Any> = HashMap()
        outputMap[0] = outputLocations
        outputMap[1] = outputClasses
        outputMap[2] = outputScores
        outputMap[3] = numDetections
        //Trace.endSection()

        // Run the inference call.
        //Trace.beginSection("run")
        odInterpreter?.runForMultipleInputsOutputs(inputArray, outputMap)
        //Trace.endSection()

        // Show the best detections.
        // after scaling them back to the input size.
        // You need to use the number of detections from the output and not the NUM_DETECTONS variable
        // declared on top
        // because on some models, they don't always output the same total number of detections
        // For example, your model's NUM_DETECTIONS = 20, but sometimes it only outputs 16 predictions
        // If you don't use the output's numDetections, you'll get nonsensical data
        val numDetectionsOutput: Int = min(NUM_DETECTIONS, numDetections[0].toInt()) // cast from float to integer, use min for safety
        val recognitions: ArrayList<Detector.Recognition> = ArrayList(numDetectionsOutput)
        for (i in 0 until numDetectionsOutput) {
            val detection = RectF(
                outputLocations[0][i][1] * INPUT_SIZE,
                outputLocations[0][i][0] * INPUT_SIZE,
                outputLocations[0][i][3] * INPUT_SIZE,
                outputLocations[0][i][2] * INPUT_SIZE
            )
            //recognitions.add(Detector.Recognition("" + i, labels[outputClasses[0][i].toInt()], outputScores[0][i], detection))
            recognitions.add(Detector.Recognition("" + i, outputClasses[0][i].toInt().toString(), outputScores[0][i], detection))
        }
        //Trace.endSection() // "recognizeImage"
        return recognitions
    }

    override fun close() {
        odInterpreter?.close()
        odInterpreter = null
    }

    companion object {
        // Only return this many results.
        private const val NUM_DETECTIONS = 10

        // Number of threads in the java app
        private const val NUM_THREADS = 4

        // Config values.
        private const val INPUT_SIZE: Int = 300

        // Is model quantized or not
        private const val IS_MODEL_QUANTIZED = true

        // For float model
        private const val IMAGE_MEAN = 127.5f
        private const val IMAGE_STD = 127.5f

        private const val MODEL_OBJECT_DETECT = "objectdetect.tflite"
    }
}