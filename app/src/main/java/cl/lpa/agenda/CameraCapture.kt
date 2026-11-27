package cl.lpa.agenda

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File

private fun lifecycleOwnerOf(c: Context): LifecycleOwner? {
    var x: Context = c
    while (x is ContextWrapper) {
        if (x is LifecycleOwner) return x
        x = x.baseContext
    }
    return null
}

/**
 * Camara propia de la app, pensada para sacar la MEJOR calidad que permita la tablet:
 * camara trasera, la resolucion mas alta disponible, JPEG al 100 %, enfoque tocando la pantalla y flash.
 * Si algo falla (permiso, equipo sin CameraX) se llama a [onFallback] para usar la camara del sistema.
 */
@Composable
fun CameraCaptureDialog(outFile: File, onResult: (Boolean) -> Unit, onFallback: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) {
            Toast.makeText(ctx, "Sin permiso de cámara: activa “Cámara” en Ajustes de Android → Aplicaciones → Agenda LPA.", Toast.LENGTH_LONG).show()
            onResult(false)
        }
    }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(Manifest.permission.CAMERA) }
    if (!granted) return

    val owner = remember { lifecycleOwnerOf(ctx) }
    val capture = remember {
        val sel = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
            .build()
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(sel)
            .setJpegQuality(100)
            .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
            .build()
    }
    val previewView = remember {
        PreviewView(ctx).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var flash by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_AUTO) }
    var busy by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            try {
                val lo = owner ?: throw IllegalStateException("sin lifecycle")
                val provider = future.get()
                val preview = Preview.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
                    ).build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                provider.unbindAll()
                camera = provider.bindToLifecycle(lo, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            } catch (e: Exception) {
                onFallback()
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { runCatching { future.get().unbindAll() } }
    }

    Dialog(
        onDismissRequest = { onResult(false) },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    previewView.apply {
                        setOnTouchListener { v, ev ->
                            if (ev.action == MotionEvent.ACTION_UP) {
                                val pt = meteringPointFactory.createPoint(ev.x, ev.y)
                                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(pt).build())
                                v.performClick()
                            }
                            true
                        }
                    }
                }
            )
            Column(
                Modifier.align(Alignment.CenterEnd).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                DialogButton(
                    when (flash) {
                        ImageCapture.FLASH_MODE_ON -> "⚡ Flash: sí"
                        ImageCapture.FLASH_MODE_OFF -> "⚡ Flash: no"
                        else -> "⚡ Flash: auto"
                    }
                ) {
                    flash = when (flash) {
                        ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                        ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
                        else -> ImageCapture.FLASH_MODE_AUTO
                    }
                    capture.flashMode = flash
                }
                Box(
                    Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(6.dp, Color(0xFFB8BDD1), CircleShape)
                        .clickable(enabled = !busy) {
                            busy = true
                            outFile.parentFile?.mkdirs()
                            capture.targetRotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
                            capture.takePicture(
                                ImageCapture.OutputFileOptions.Builder(outFile).build(),
                                ContextCompat.getMainExecutor(ctx),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                        busy = false
                                        onResult(true)
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        busy = false
                                        Toast.makeText(ctx, "No se pudo sacar la foto.", Toast.LENGTH_LONG).show()
                                        onResult(false)
                                    }
                                }
                            )
                        }
                )
                Text("Toca la pantalla para enfocar", color = Color.White, fontSize = 12.sp)
                DialogButton("✕ Cancelar") { onResult(false) }
            }
        }
    }
}
