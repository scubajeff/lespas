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

package site.leos.apps.lespas.tflite

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import site.leos.apps.lespas.search.Classification
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min


class ObjectDetectionModel(assetManager: AssetManager) {
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

    // object label
    private val labelIndex = arrayListOf<Pair<String, Float>>()

    init {
        BufferedReader(InputStreamReader(assetManager.open("label_mobile_ssd_coco_90.txt"))).use {
            var line = it.readLine()
            while (line != null) {
                line.split(',').apply { labelIndex.add(Pair(this[0], this[1].toFloat())) }
                line = it.readLine()
            }
        }
    }

    fun recognizeImage(bitmap: Bitmap): List<Classification> {
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
        val recognitions: ArrayList<Classification> = ArrayList(numDetectionsOutput)
        for (i in 0 until numDetectionsOutput) {
            val objectIndex = outputClasses[0][i].toInt()
            val objectConfidence = outputScores[0][i]
            val location = RectF(
                outputLocations[0][i][1] * INPUT_SIZE,
                outputLocations[0][i][0] * INPUT_SIZE,
                outputLocations[0][i][3] * INPUT_SIZE,
                outputLocations[0][i][2] * INPUT_SIZE
            )
            /*
            //recognitions.add(Detector.Recognition("" + i, labels[outputClasses[0][i].toInt()], outputScores[0][i], detection))
            recognitions.add(Recognition(outputClasses[0][i].toInt(), outputScores[0][i], detection))
             */
            val found = labelIndex[objectIndex]
            var foundObject = Classification("", Classification.TYPE_UNKNOWN, Classification.OBJECT_UNKNOWN.toString(), 0, 0f, RectF())
            if (objectConfidence > found.second) {
                foundObject = Classification("", Classification.TYPE_OBJECT, found.first, objectIndex, objectConfidence, location)
            } else {
                if ((objectIndex == 51 || objectIndex == 52 || objectIndex == 54) && objectConfidence > 0.3) {
                    // "banana 51", "apple 52", "orange 54", could well be plant
                    foundObject = Classification("", Classification.TYPE_OBJECT, Classification.OBJECT_PLANT.toString(), objectIndex, objectConfidence, location)
                }
                if ((objectIndex == 56 || objectIndex == 22 || objectIndex == 55) && objectConfidence < 0.45 && objectConfidence > 0.24) {
                    // Low confidence "carrot 56", "bear 22", "broccoli 55" could be plant
                    foundObject = Classification("", Classification.TYPE_OBJECT, Classification.OBJECT_PLANT.toString(), objectIndex, objectConfidence, location)
                }
                /* too much false positive
                if ((this.title == "15") && this.confidence < 0.5 && this.confidence > 0.36) {
                    // "bird 15" with confidence range of 0.35~0.5 could be plant
                }
                 */
            }

            recognitions.add(foundObject)
        }
        //Trace.endSection() // "recognizeImage"
        return recognitions
    }

    fun close() {
        odInterpreter?.close()
        odInterpreter = null
    }

    data class Recognition (
        val objectIndex: Int,
        val confidence: Float,
        val location: RectF
    )

    companion object {
        // Only return this many results.
        private const val NUM_DETECTIONS = 10

        // Number of threads in the java app
        private const val NUM_THREADS = 4

        // Config values.
        const val INPUT_SIZE: Int = 300

        // Is model quantized or not
        private const val IS_MODEL_QUANTIZED = true

        // For float model
        private const val IMAGE_MEAN = 127.5f
        private const val IMAGE_STD = 127.5f

        private const val MODEL_OBJECT_DETECT = "objectdetect.tflite"
    }
}