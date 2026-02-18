package com.example.signdecs

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

class HandLandmarkerHelper(
    var minHandDetectionConfidence: Float = 0.5f,
    var minHandTrackingConfidence: Float = 0.5f,
    var minHandPresenceConfidence: Float = 0.5f,
    var runningMode: RunningMode = RunningMode.IMAGE,
    val context: Context,
    val handLandmarkerResultsListener: ResultsListener? = null,
    val errorListener: ErrorListener? = null
) {

    private var handLandmarker: HandLandmarker? = null

    init {
        setupHandLandmarker()
    }

    fun clearHandLandmarker() {
        handLandmarker?.close()
        handLandmarker = null
    }

    private fun setupHandLandmarker() {
        // Set general options for MediaPipe Hand Landmarker
        val baseOptionsBuilder = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")

        // Use GPU acceleration if available, otherwise default to CPU
        baseOptionsBuilder.setDelegate(Delegate.GPU)

        val baseOptions = baseOptionsBuilder.build()

        // Set options for Hand Landmarker
        val optionsBuilder =
            HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinHandDetectionConfidence(minHandDetectionConfidence)
                .setMinTrackingConfidence(minHandTrackingConfidence)
                .setMinHandPresenceConfidence(minHandPresenceConfidence)
                .setRunningMode(runningMode)

        if (runningMode == RunningMode.LIVE_STREAM) {
            optionsBuilder
                .setResultListener(this::returnLivestreamResult)
                .setErrorListener(this::returnLivestreamError)
        }

        val options = optionsBuilder.build()

        try {
            handLandmarker = HandLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            errorListener?.onError(
                "Hand Landmarker failed to initialize. See error logs for details"
            )
            Log.e(
                "HandLandmarkerHelper", "Hand Landmarker failed to initialize: " + e.message
            )
        }
    }

    fun detectLiveStream(bitmap: Bitmap) {
        if (handLandmarker == null) {
            setupHandLandmarker()
        }
        val frameTime = SystemClock.uptimeMillis()

        // Convert the [Bitmap] and provide it to the HandLandmarkerGame
        val image = BitmapImageBuilder(bitmap).build()

        handLandmarker?.detectAsync(image, frameTime)
    }

    private fun returnLivestreamResult(
        result: HandLandmarkerResult,
        input: com.google.mediapipe.framework.image.MPImage
    ) {
        val finishTimeMs = SystemClock.uptimeMillis()
        val inferenceTime = finishTimeMs - result.timestampMs()
        handLandmarkerResultsListener?.onResults(
            ResultBundle(
                listOf(result),
                inferenceTime,
                input.height,
                input.width
            )
        )
    }

    private fun returnLivestreamError(error: RuntimeException) {
        errorListener?.onError(
            error.message ?: "An unknown error has occurred"
        )
    }

    /**
     * Helper class that holds the various attributes needed for a
     * successful [HandLandmarkerGame] result.
     */
    data class ResultBundle(
        val results: List<HandLandmarkerResult>,
        val inferenceTime: Long,
        val inputImageHeight: Int,
        val inputImageWidth: Int,
    )

    interface ResultsListener {
        fun onResults(resultBundle: ResultBundle?)
    }

    interface ErrorListener {
        fun onError(error: String, errorCode: Int = -1)
    }
}