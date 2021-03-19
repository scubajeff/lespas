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