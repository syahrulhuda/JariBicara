package com.example.signdecs

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

// Data class to hold the classification result
data class ClassificationResult(
    val sign: String,
    val confidence: Float
)

object SignLanguageClassifier {

    private const val MODEL_PATH = "revised_hand_sign_model.tflite"
    private const val LABELS_PATH = "labels.txt"
    private const val BATCH_SIZE = 1
    private const val INPUT_SIZE = 21 * 2 // 21 landmarks, each with x and y coordinates
    private const val BYTES_PER_FLOAT = 4
    private const val NUM_CLASSES = 27

    private var interpreter: Interpreter? = null
    private var labels: List<String>? = null

    fun init(context: Context) {
        try {
            interpreter = Interpreter(loadModelFile(context, MODEL_PATH))
            labels = loadLabels(context, LABELS_PATH)
            Log.d("SignLanguageClassifier", "TFLite model and labels loaded successfully.")
        } catch (e: IOException) {
            Log.e("SignLanguageClassifier", "Error loading model or labels from assets: ${e.message}")
        } catch (e: Exception) {
            Log.e("SignLanguageClassifier", "Failed to initialize the interpreter: ${e.message}")
        }
    }

    @Throws(IOException::class)
    private fun loadModelFile(context: Context, modelPath: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    @Throws(IOException::class)
    private fun loadLabels(context: Context, labelsPath: String): List<String> {
        return context.assets.open(labelsPath).bufferedReader().useLines { it.toList() }
    }

    fun classify(landmarks: List<NormalizedLandmark>): ClassificationResult? {
        if (interpreter == null || labels == null || labels!!.isEmpty()) {
            // This log is important for debugging
            Log.e("SignLanguageClassifier", "Classifier not initialized or labels are empty.")
            return null
        }

        // Prepare input ByteBuffer
        val inputBuffer = ByteBuffer.allocateDirect(BATCH_SIZE * INPUT_SIZE * BYTES_PER_FLOAT)
        inputBuffer.order(ByteOrder.nativeOrder())

        for (landmark in landmarks) {
            inputBuffer.putFloat(landmark.x())
            inputBuffer.putFloat(landmark.y())
        }
        inputBuffer.rewind()

        // Prepare output buffer
        val outputBuffer = ByteBuffer.allocateDirect(BATCH_SIZE * NUM_CLASSES * BYTES_PER_FLOAT)
        outputBuffer.order(ByteOrder.nativeOrder())

        // Run inference
        interpreter?.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()

        val probabilities = FloatArray(NUM_CLASSES)
        for (i in 0 until NUM_CLASSES) {
            probabilities[i] = outputBuffer.float
        }

        val topResultIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: -1

        return if (topResultIndex != -1 && topResultIndex < labels!!.size) {
            val confidence = probabilities[topResultIndex] * 100
            ClassificationResult(labels!![topResultIndex], confidence)
        } else {
            null
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        labels = null
    }
}