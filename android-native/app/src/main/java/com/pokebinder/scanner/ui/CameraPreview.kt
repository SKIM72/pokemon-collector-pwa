package com.pokebinder.scanner.ui

import android.util.Size
import android.view.MotionEvent
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.pokebinder.scanner.model.FrameProbe
import com.pokebinder.scanner.scanner.FrameStabilityAnalyzer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Composable
fun CameraPreview(
    onProbe: (FrameProbe) -> Unit,
    onStableFrame: (ByteArray) -> Unit,
    torchEnabled: Boolean,
    onTorchAvailabilityChanged: (Boolean) -> Unit,
    onFocusPointChanged: (Offset?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val analyzer = remember(onProbe, onStableFrame) {
        FrameStabilityAnalyzer(onProbe, onStableFrame)
    }
    val providerFuture = remember { ProcessCameraProvider.getInstance(context) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    AndroidView(
        factory = { previewView },
        update = { view ->
            view.setOnTouchListener { _, event ->
                if (event.action != MotionEvent.ACTION_UP) {
                    return@setOnTouchListener true
                }
                val point = view.meteringPointFactory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(
                    point,
                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
                )
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build()
                camera?.cameraControl?.startFocusAndMetering(action)
                onFocusPointChanged(Offset(event.x, event.y))
                view.performClick()
                true
            }
        },
        modifier = modifier.fillMaxSize(),
    )

    LaunchedEffect(camera, torchEnabled) {
        camera?.cameraControl?.enableTorch(torchEnabled)
    }

    DisposableEffect(lifecycleOwner, analyzer) {
        val listener = Runnable {
            val cameraProvider = providerFuture.get()
            val preview = Preview.Builder()
                .setTargetResolution(Size(720, 1280))
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(720, 1280))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(analyzerExecutor, analyzer) }

            runCatching {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis,
                )
                onTorchAvailabilityChanged(camera?.cameraInfo?.hasFlashUnit() == true)
            }
        }

        providerFuture.addListener(listener, ContextCompat.getMainExecutor(context))

        onDispose {
            camera?.cameraControl?.enableTorch(false)
            camera = null
            onTorchAvailabilityChanged(false)
            if (providerFuture.isDone) {
                runCatching { providerFuture.get().unbindAll() }
            }
            analyzerExecutor.shutdown()
        }
    }
}
