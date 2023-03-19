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
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.channels.FileChannel


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
}