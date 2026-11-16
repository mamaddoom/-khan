package com.mozkhan.shop.ml

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/** Lets the UI grab the next camera frame as a Bitmap (used to take reference photos). */
class FrameGrabber {
    internal val want = AtomicBoolean(false)
    @Volatile internal var callback: ((Bitmap) -> Unit)? = null
    fun grab(cb: (Bitmap) -> Unit) { callback = cb; want.set(true) }
}

/** Frames handed to the image recogniser / reference photos use this size (long side) so both sides compare alike. */
private const val FRAME_MAX_SIDE = 400

/**
 * Live camera with barcode detection (ML Kit) and optional periodic visual frames.
 *
 *  - [scanBarcodes]: run barcode detection (≈6 fps max, saves battery)
 *  - [frameIntervalMs] > 0: deliver a small Bitmap to [onFrame] at that interval (visual recognition)
 *  - [torch]: flashlight; [onTorchAvailable] reports whether the phone has one
 * The camera is bound to the lifecycle so it stops when the screen is in the background.
 */
@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
fun CameraView(
    modifier: Modifier = Modifier,
    scanBarcodes: Boolean = true,
    frameIntervalMs: Long = 0,
    torch: Boolean = false,
    grabber: FrameGrabber? = null,
    onBarcode: (String) -> Unit = {},
    onFrame: (Bitmap) -> Unit = {},
    onTorchAvailable: (Boolean) -> Unit = {},
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val barcodeCb by rememberUpdatedState(onBarcode)
    val frameCb by rememberUpdatedState(onFrame)
    val scanFlag by rememberUpdatedState(scanBarcodes)
    val interval by rememberUpdatedState(frameIntervalMs)
    val torchOn by rememberUpdatedState(torch)
    val torchCb by rememberUpdatedState(onTorchAvailable)
    // must survive recompositions, otherwise the torch effect would see a fresh null camera
    val cameraRef = remember { arrayOfNulls<Camera>(1) }

    DisposableEffect(owner) {
        // created per binding: a re-entered screen never reuses a shut-down executor / closed scanner
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                    Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX,
                    Barcode.FORMAT_ITF,
                ).build(),
        )
        val future = ProcessCameraProvider.getInstance(ctx)
        var provider: ProcessCameraProvider? = null
        var lastBarcodeTs = 0L
        var lastFrameTs = 0L
        // the provider can arrive AFTER the view was removed (camera switched off quickly): never bind then,
        // otherwise the camera would keep running in the background and drain the battery
        val disposed = AtomicBoolean(false)
        val listener = Runnable {
            if (disposed.get()) return@Runnable
            val p = runCatching { future.get() }.getOrNull() ?: return@Runnable
            provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(720, 960))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy: ImageProxy ->
                val now = System.currentTimeMillis()
                // Exactly one owner closes the frame: ML Kit's completion listener when a task was really
                // started, otherwise the finally block below. An unclosed frame would stall the analyzer forever.
                var handedToScanner = false
                try {
                    val wantFrame = grabber?.want?.get() == true
                    val needFrame = wantFrame || (interval > 0 && now - lastFrameTs >= interval)
                    if (needFrame) {
                        lastFrameTs = now
                        val bmp = proxy.toSmallBitmap(FRAME_MAX_SIDE)
                        if (wantFrame && grabber != null) {
                            grabber.want.set(false)
                            grabber.callback?.invoke(bmp)
                        } else frameCb(bmp)
                    }
                    if (scanFlag && !disposed.get() && now - lastBarcodeTs >= 160) {
                        lastBarcodeTs = now
                        val media = proxy.image
                        if (media != null) {
                            val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                            // if process() or adding a listener throws synchronously, handedToScanner stays false
                            // and the frame is closed below
                            val task = scanner.process(img)
                            task.addOnCompleteListener { runCatching { proxy.close() } }
                            handedToScanner = true
                            task.addOnSuccessListener { list -> list.firstNotNullOfOrNull { it.rawValue }?.let { runCatching { barcodeCb(it) } } }
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    if (!handedToScanner) runCatching { proxy.close() }
                }
            }
            try {
                p.unbindAll()
                val cam = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                cameraRef[0] = cam
                val flash = cam.cameraInfo.hasFlashUnit()
                torchCb(flash)
                if (torchOn && flash) cam.cameraControl.enableTorch(true)
            } catch (_: Throwable) {
            }
        }
        future.addListener(listener, ContextCompat.getMainExecutor(ctx))
        onDispose {
            disposed.set(true)
            cameraRef[0] = null
            runCatching { provider?.unbindAll() }
            runCatching { scanner.close() }
            executor.shutdown()
        }
    }
    LaunchedEffect(torch) { runCatching { cameraRef[0]?.cameraControl?.enableTorch(torch) } }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/** Camera frame -> upright bitmap with the long side limited to [maxSide] (keeps recognition cheap on battery and memory). */
private fun ImageProxy.toSmallBitmap(maxSide: Int): Bitmap {
    val raw = toBitmap()
    val scale = min(1f, maxSide / max(raw.width, raw.height).toFloat())
    val m = Matrix().apply { postScale(scale, scale); postRotate(imageInfo.rotationDegrees.toFloat()) }
    val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    if (out !== raw) raw.recycle()
    return out
}
