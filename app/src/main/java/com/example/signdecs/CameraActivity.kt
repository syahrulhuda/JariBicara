package com.example.signdecs

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.signdecs.ui.theme.SigndecsTheme
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.Locale
import java.util.concurrent.Executors

class CameraActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SigndecsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CameraScreen()
                }
            }
        }
    }
}

@SuppressLint("UnsafeOptInUsageError")
@Suppress("DEPRECATION")
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var classificationResult by remember { mutableStateOf("No sign detected") }
    var typedText by remember { mutableStateOf("") }
    var lastAppendedSign by remember { mutableStateOf<String?>(null) }
    var lastTypedTimestamp by remember { mutableLongStateOf(0L) }
    val typingCooldownMs = 1000L
    
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var cameraSelector by remember { mutableStateOf(CameraSelector.DEFAULT_FRONT_CAMERA) }

    val minConfidence = 95.0

    val database = Firebase.database.reference

    val handLandmarkerHelper = remember {
        HandLandmarkerHelper(
            context = context,
            runningMode = RunningMode.LIVE_STREAM,
            minHandDetectionConfidence = 0.7f,
            minHandTrackingConfidence = 0.7f,
            minHandPresenceConfidence = 0.7f,
            errorListener = object : HandLandmarkerHelper.ErrorListener {
                override fun onError(error: String, errorCode: Int) {
                    Log.e("HandLandmarker", "Error: $error, code: $errorCode")
                }
            },
            handLandmarkerResultsListener = object : HandLandmarkerHelper.ResultsListener {
                override fun onResults(resultBundle: HandLandmarkerHelper.ResultBundle?) {
                    resultBundle?.let {
                        if (it.results.isNotEmpty() && it.results[0].landmarks().isNotEmpty()) {
                            val landmarks = it.results[0].landmarks()[0]
                            val classification = SignLanguageClassifier.classify(landmarks)

                            classificationResult = if (classification != null) {
                                "${classification.sign} (${String.format(Locale.US, "%.2f", classification.confidence)}%)"
                            } else {
                                "No sign detected"
                            }

                            if (classification != null) {
                                val currentTime = System.currentTimeMillis()
                                if (
                                    classification.confidence >= minConfidence &&
                                    classification.sign != lastAppendedSign &&
                                    (currentTime - lastTypedTimestamp) > typingCooldownMs
                                ) {
                                    val charToAppend = if (classification.sign.trim().equals("Space", ignoreCase = true)) {
                                        " "
                                    } else {
                                        classification.sign
                                    }
                                    typedText += charToAppend
                                    lastAppendedSign = classification.sign
                                    lastTypedTimestamp = currentTime
                                }
                            } else {
                                lastAppendedSign = null
                            }
                        } else {
                            classificationResult = "No sign detected"
                            lastAppendedSign = null
                        }
                    }
                }
            }
        )
    }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            cameraExecutor.shutdown()
            handLandmarkerHelper.clearHandLandmarker()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val previewView = remember { PreviewView(context) }

        LaunchedEffect(cameraProviderFuture, cameraSelector) {
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analyzer ->
                        analyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                            try {
                                val bitmap = imageProxy.toBitmap()
                                val matrix = Matrix().apply {
                                    postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                                    if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                                        postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
                                    }
                                }

                                val rotatedBitmap = Bitmap.createBitmap(
                                    bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                                )
                                handLandmarkerHelper.detectLiveStream(rotatedBitmap)
                            } catch (e: Exception) {
                                Log.e("CameraScreen", "Error in image analyzer: ${e.message}", e)
                            } finally {
                                imageProxy.close()
                            }
                        }
                    }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )
            } catch (e: Exception) {
                Log.e("CameraScreen", "Use case binding failed", e)
            }
        }

        AndroidView({ previewView }, modifier = Modifier.fillMaxSize())

        // UI Overlay
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Left: Upload Button
                IconButton(
                    onClick = {
                        if (typedText.isNotBlank()) {
                            val entry = mapOf(
                                "text" to typedText,
                                "timestamp" to System.currentTimeMillis()
                            )
                            database.child("detections").push().setValue(entry)
                                .addOnSuccessListener {
                                    Toast.makeText(context, "Teks berhasil diunggah!", Toast.LENGTH_SHORT).show()
                                }
                                .addOnFailureListener {
                                    Toast.makeText(context, "Gagal mengunggah: ${it.message}", Toast.LENGTH_SHORT).show()
                                }
                        } else {
                            Toast.makeText(context, "Tidak ada teks untuk diunggah", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Upload,
                        contentDescription = "Upload Text",
                        tint = Color.White
                    )
                }

                // Top Right: Camera Switch Button
                IconButton(
                    onClick = {
                        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                            CameraSelector.DEFAULT_BACK_CAMERA
                        } else {
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Cameraswitch,
                        contentDescription = "Switch Camera",
                        tint = Color.White
                    )
                }
            }

            // Bottom: Detection and Typing UI
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = classificationResult,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(vertical = 8.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = typedText,
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        typedText = ""
                        lastAppendedSign = null
                        lastTypedTimestamp = 0L
                    }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear Text",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}