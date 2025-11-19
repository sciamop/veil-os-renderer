package com.veil.renderer

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.opengl.GLES30
import android.opengl.GLES11Ext
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.Typeface
import android.view.Gravity
import android.media.MediaCodec
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.content.Intent
// UVCAndroid includes serenegiant classes - use them directly
import com.serenegiant.usb.UVCCamera
import com.serenegiant.usb.UVCParam
import com.serenegiant.usb.USBMonitor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : Activity() {
    companion object {
        private const val CAMERA_WIDTH = 2560
        private const val CAMERA_HEIGHT = 720
        private const val CAMERA_FPS = 60
        private const val REQUEST_CAMERA_PERMISSION = 1001
        private const val REQUEST_RECORD_AUDIO_PERMISSION = 1002
        
        // Available resolutions in order (highest to lowest)
        private val RESOLUTIONS = listOf(
            Pair(3840, 1080),
            Pair(2560, 720),
            Pair(1600, 600),
            Pair(1280, 480),
            Pair(640, 240)
        )
    }

    private lateinit var usbManager: UsbManager
    private var usbMonitor: USBMonitor? = null
    private var uvcCamera: UVCCamera? = null
    private lateinit var glSurfaceView: GLSurfaceView
    private var renderer: StereoRenderer? = null
    private var cameraSurface: Surface? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var currentCtrlBlock: USBMonitor.UsbControlBlock? = null
    private var currentResolutionIndex = 1  // Start at 2560x720 (index 1)
    private var overlayLeftEye: TextView? = null
    private var overlayRightEye: TextView? = null
    private var overlayHideHandler: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Enter immersive fullscreen mode
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Request camera permission for UVC devices®®
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.CAMERA),
                REQUEST_CAMERA_PERMISSION
            )
        }
        
        // Request audio permission for voice recognition
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO_PERMISSION
            )
        }

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        usbMonitor = USBMonitor(this, object : USBMonitor.OnDeviceConnectListener {
            override fun onAttach(device: UsbDevice) {
                requestUsbPermission(device)
            }

            override fun onDetach(device: UsbDevice) {
                // Device detached
            }

            override fun onDeviceOpen(
                device: UsbDevice,
                ctrlBlock: USBMonitor.UsbControlBlock,
                createNew: Boolean
            ) {
                currentCtrlBlock = ctrlBlock
                loadConfiguration()  // Load saved resolution index
                openCamera(ctrlBlock)
            }

            override fun onDeviceClose(
                device: UsbDevice,
                ctrlBlock: USBMonitor.UsbControlBlock
            ) {
                uvcCamera?.destroy()
                uvcCamera = null
            }

            override fun onCancel(device: UsbDevice) {
                // Permission cancelled
            }
        })
        usbMonitor?.register()

        // Create container layout
        val container = FrameLayout(this)
        
        glSurfaceView = GLSurfaceView(this)
        glSurfaceView.setEGLContextClientVersion(3)
        renderer = StereoRenderer()
        glSurfaceView.setRenderer(renderer)
        // Render continuously to keep video visible
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        
        // Request 120Hz refresh rate if supported (Pixel 7 Pro supports this)
        try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val display = windowManager.defaultDisplay
            
            // Try to find 120Hz mode
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val modes = display.supportedModes
                var found120Hz = false
                for (mode in modes) {
                    val refreshRate = mode.refreshRate
                    android.util.Log.d("VeilRenderer", "Available refresh rate: ${refreshRate}Hz")
                    if (refreshRate >= 119.0f && refreshRate <= 121.0f) {
                        // Found 120Hz mode - try to set it via window params
                        val params = window.attributes
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            // API 31+ - use preferredDisplayModeId
                            try {
                                val modeId = mode.modeId
                                params.preferredDisplayModeId = modeId
                                window.attributes = params
                                android.util.Log.d("VeilRenderer", "Set display mode to 120Hz (mode ID: $modeId)")
                                found120Hz = true
                                break
                            } catch (e: Exception) {
                                android.util.Log.w("VeilRenderer", "Could not set display mode", e)
                            }
                        }
                    }
                }
                if (!found120Hz) {
                    android.util.Log.w("VeilRenderer", "120Hz mode not found or could not be set")
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("VeilRenderer", "Could not set refresh rate", e)
        }
        
        container.addView(glSurfaceView)
        
        // Create stereoscopic overlays - one for each eye, centered in each eye's viewport
        overlayLeftEye = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 12f
            setBackgroundColor(android.graphics.Color.argb(180, 0, 0, 0))  // Semi-transparent black
            setPadding(16, 8, 16, 8)
            visibility = View.GONE
        }
        
        overlayRightEye = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 12f
            setBackgroundColor(android.graphics.Color.argb(180, 0, 0, 0))  // Semi-transparent black
            setPadding(16, 8, 16, 8)
            visibility = View.GONE
        }
        
        container.addView(overlayLeftEye)
        container.addView(overlayRightEye)
        
        // Update overlay positions when layout changes
        container.viewTreeObserver.addOnGlobalLayoutListener {
            updateOverlayPositions()
        }
        
        overlayHideHandler = Handler(mainLooper)
        
        setContentView(container)
        
        // Load saved configuration after renderer is created
        glSurfaceView.post {
            loadConfiguration()
        }
        
        // Initialize speech recognizer for continuous listening
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(createRecognitionListener())
            startContinuousListening()
        }

        // USBMonitor will handle device attachment automatically via onAttach callback
    }
    
    private fun getPreferences(): SharedPreferences {
        return getSharedPreferences("VeilRendererConfig", Context.MODE_PRIVATE)
    }
    
    private fun loadConfiguration() {
        val prefs = getPreferences()
        val leftEyeOffset = prefs.getInt("leftEyeOffset", 0)
        val rightEyeOffset = prefs.getInt("rightEyeOffset", 0)
        val convergenceFactor = prefs.getFloat("convergenceFactor", 0.3f)
        currentResolutionIndex = prefs.getInt("resolutionIndex", 1)
        
        // Apply loaded configuration to renderer
        renderer?.setLeftEyeOffset(leftEyeOffset)
        renderer?.setRightEyeOffset(rightEyeOffset)
        renderer?.setConvergenceFactor(convergenceFactor)
        renderer?.setSharpness(prefs.getFloat("sharpness", 0.5f))
        renderer?.setContrast(prefs.getFloat("contrast", 1.0f))
        renderer?.setBrightness(prefs.getFloat("brightness", 0.0f))
        renderer?.setSaturation(prefs.getFloat("saturation", 1.0f))
        renderer?.setVerticalScale(prefs.getFloat("verticalScale", 1.0f))
        
        android.util.Log.d("VeilRenderer", "Loaded config: L=$leftEyeOffset, R=$rightEyeOffset, C=$convergenceFactor, Res=${currentResolutionIndex}")
    }
    

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Camera permission granted, USBMonitor will handle device detection
            } else {
                // Camera permission denied - UVC cameras won't work
            }
        }
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
        if (speechRecognizer != null && ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) 
            == PackageManager.PERMISSION_GRANTED) {
            startContinuousListening()
        }
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
        renderer?.pause()
        uvcCamera?.stopPreview()
        uvcCamera?.destroy()
        stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        speechRecognizer = null
        renderer?.release()
        uvcCamera?.destroy()
        uvcCamera = null
        usbMonitor?.unregister()
        usbMonitor?.destroy()
        usbMonitor = null
    }
    
    private fun startContinuousListening() {
        if (isListening) {
            android.util.Log.d("VeilRenderer", "Already listening, skipping")
            return
        }
        
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            return
        }
        
        if (speechRecognizer == null) return
        
        // Stop any existing listening first
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            // Ignore - might already be stopped
        }
        
        // Small delay to ensure recognizer is ready
        Handler(mainLooper).postDelayed({
            if (isListening) {
                android.util.Log.d("VeilRenderer", "Already listening after delay, skipping")
                return@postDelayed
            }
            
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            
            try {
                isListening = true
                speechRecognizer?.startListening(intent)
                android.util.Log.d("VeilRenderer", "Started listening")
            } catch (e: Exception) {
                isListening = false
                android.util.Log.e("VeilRenderer", "Speech recognition error", e)
            }
        }, 200)  // Wait 200ms after stopping
    }
    
    private fun stopListening() {
        if (!isListening) return
        try {
            speechRecognizer?.stopListening()
            isListening = false
        } catch (e: Exception) {
            isListening = false
        }
    }
    
    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                isListening = false
            }
            override fun onError(error: Int) {
                isListening = false
                
                // Handle ERROR_RECOGNIZER_BUSY specially
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    android.util.Log.w("VeilRenderer", "Recognizer busy - stopping and retrying")
                    try {
                        speechRecognizer?.stopListening()
                        speechRecognizer?.cancel()
                    } catch (e: Exception) {
                        // Ignore
                    }
                    Handler(mainLooper).postDelayed({
                        if (ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.RECORD_AUDIO) 
                            == PackageManager.PERMISSION_GRANTED && !isListening) {
                            startContinuousListening()
                        }
                    }, 1000)  // Wait longer for busy error
                } else {
                    if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        android.util.Log.w("VeilRenderer", "Speech error: $error")
                    }
                    Handler(mainLooper).postDelayed({
                        if (ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.RECORD_AUDIO) 
                            == PackageManager.PERMISSION_GRANTED && !isListening) {
                            startContinuousListening()
                        }
                    }, 500)
                }
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null && matches.isNotEmpty()) {
                    val spokenText = matches[0].lowercase().trim()
                    android.util.Log.d("VeilRenderer", "Recognized: $spokenText")
                    parseVoiceCommand(spokenText)
                }
                Handler(mainLooper).postDelayed({
                    if (ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.RECORD_AUDIO) 
                        == PackageManager.PERMISSION_GRANTED && !isListening) {
                        startContinuousListening()
                    }
                }, 300)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                // Process partial results to keep listening active
                val partialMatches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (partialMatches != null && partialMatches.isNotEmpty()) {
                    val partialText = partialMatches[0].lowercase().trim()
                    android.util.Log.d("VeilRenderer", "Partial: $partialText")
                    // Don't process partial results, just log them - wait for final results
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }
    
    private fun updateOverlayPositions() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        
        overlayLeftEye?.let { left ->
            val params = left.layoutParams as FrameLayout.LayoutParams
            left.measure(
                View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.AT_MOST)
            )
            val leftWidth = left.measuredWidth
            val leftHeight = left.measuredHeight
            // Center of left half: x = screenWidth/4, y = screenHeight/2
            params.leftMargin = (screenWidth / 4) - (leftWidth / 2)
            params.topMargin = (screenHeight / 2) - (leftHeight / 2)
            left.layoutParams = params
        }
        
        overlayRightEye?.let { right ->
            val params = right.layoutParams as FrameLayout.LayoutParams
            right.measure(
                View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.AT_MOST)
            )
            val rightWidth = right.measuredWidth
            val rightHeight = right.measuredHeight
            // Center of right half: x = 3*screenWidth/4, y = screenHeight/2
            params.leftMargin = (3 * screenWidth / 4) - (rightWidth / 2)
            params.topMargin = (screenHeight / 2) - (rightHeight / 2)
            right.layoutParams = params
        }
    }
    
    private fun showOverlay(message: String, durationMs: Long = 2000) {
        overlayHideHandler?.removeCallbacksAndMessages(null)  // Cancel any pending hide
        overlayLeftEye?.let { overlay ->
            overlay.text = message
            overlay.visibility = View.VISIBLE
            updateOverlayPositions()  // Update positions when showing
        }
        overlayRightEye?.let { overlay ->
            overlay.text = message
            overlay.visibility = View.VISIBLE
            updateOverlayPositions()  // Update positions when showing
        }
        overlayHideHandler?.postDelayed({
            overlayLeftEye?.visibility = View.GONE
            overlayRightEye?.visibility = View.GONE
        }, durationMs)
    }
    
    private fun parseVoiceCommand(text: String) {
        val words = text.split("\\s+".toRegex()).map { it.lowercase().trim() }
        android.util.Log.d("VeilRenderer", "Parsing command: $text, words: $words")
        
        try {
            when {
                // Pattern: [left/right] [in/out] [number]
                // "left in 100" or "right out 50"
                // left/right refer to viewer's left/right (left side of screen = viewer's left)
                (words.contains("left") || words.contains("right")) && 
                (words.contains("in") || words.contains("out")) -> {
                    val isLeft = words.contains("left")
                    val isRight = words.contains("right")
                    val isIn = words.contains("in")
                    val isOut = words.contains("out")
                    
                    val numbers = words.mapNotNull { it.toIntOrNull() }
                    val pixels = numbers.lastOrNull() ?: 10
                    
                    // Viewer's left = left side of screen = leftEyeOffset
                    // Viewer's right = right side of screen = rightEyeOffset
                    // "in" = move towards center (viewer's left moves right, viewer's right moves left)
                    // "out" = move away from center (viewer's left moves left, viewer's right moves right)
                    if (isLeft) {
                        // Viewer's left: "in" = move right (positive), "out" = move left (negative)
                        val offset = if (isIn) pixels else -pixels
                        renderer?.adjustLeftEyeOffset(offset)
                        val current = renderer?.getLeftEyeOffset() ?: 0
                        showOverlay("LEFT EYE: ${current}px\nRange: unlimited")
                        android.util.Log.d("VeilRenderer", "✓ Viewer's left ${if (isIn) "in" else "out"} $pixels pixels")
                    } else if (isRight) {
                        // Viewer's right: "in" = move left (negative), "out" = move right (positive)
                        val offset = if (isIn) -pixels else pixels
                        renderer?.adjustRightEyeOffset(offset)
                        val current = renderer?.getRightEyeOffset() ?: 0
                        showOverlay("RIGHT EYE: ${current}px\nRange: unlimited")
                        android.util.Log.d("VeilRenderer", "✓ Viewer's right ${if (isIn) "in" else "out"} $pixels pixels")
                    }
                }
                
                // Pattern: "convergence [number]" or "converge [number]"
                text.contains("converge", ignoreCase = true) -> {
                    val numbers = words.mapNotNull { it.toFloatOrNull() }
                    val value = numbers.lastOrNull() ?: 0.3f
                    val clamped = value.coerceIn(0f, 1f)
                    renderer?.setConvergenceFactor(clamped)
                    showOverlay("CONVERGENCE: ${String.format("%.2f", clamped)}\nRange: 0.0 - 1.0")
                    android.util.Log.d("VeilRenderer", "✓ Convergence: $clamped")
                }
                
                // Resolution control: "UP" or "DOWN"
                words.contains("up") -> {
                    changeResolution(1)  // Increase resolution (lower index)
                }
                
                words.contains("down") -> {
                    changeResolution(-1)  // Decrease resolution (higher index)
                }
                
                // Post-processing: "sharpness [number]", "contrast [number]", "brightness [number]", "saturation [number]"
                text.contains("sharpness", ignoreCase = true) || text.contains("sharp", ignoreCase = true) -> {
                    val numbers = words.mapNotNull { it.toFloatOrNull() }
                    val value = numbers.lastOrNull() ?: 0.5f
                    val clamped = value.coerceIn(0f, 1f)
                    renderer?.setSharpness(clamped)
                    showOverlay("SHARPNESS: ${String.format("%.2f", clamped)}\nRange: 0.0 - 1.0")
                    android.util.Log.d("VeilRenderer", "✓ Sharpness: $clamped")
                }
                
                text.contains("contrast", ignoreCase = true) -> {
                    val numbers = words.mapNotNull { it.toFloatOrNull() }
                    val value = numbers.lastOrNull() ?: 1.0f
                    val clamped = value.coerceIn(0.5f, 1.5f)
                    renderer?.setContrast(clamped)
                    showOverlay("CONTRAST: ${String.format("%.2f", clamped)}\nRange: 0.5 - 1.5")
                    android.util.Log.d("VeilRenderer", "✓ Contrast: $clamped")
                }
                
                text.contains("brightness", ignoreCase = true) -> {
                    val numbers = words.mapNotNull { it.toFloatOrNull() }
                    val value = numbers.lastOrNull() ?: 0.0f
                    val clamped = value.coerceIn(-0.5f, 0.5f)
                    renderer?.setBrightness(clamped)
                    showOverlay("BRIGHTNESS: ${String.format("%.2f", clamped)}\nRange: -0.5 - 0.5")
                    android.util.Log.d("VeilRenderer", "✓ Brightness: $clamped")
                }
                
                text.contains("saturation", ignoreCase = true) -> {
                    val numbers = words.mapNotNull { it.toFloatOrNull() }
                    val value = numbers.lastOrNull() ?: 1.0f
                    val clamped = value.coerceIn(0f, 2f)
                    renderer?.setSaturation(clamped)
                    showOverlay("SATURATION: ${String.format("%.2f", clamped)}\nRange: 0.0 - 2.0")
                    android.util.Log.d("VeilRenderer", "✓ Saturation: $clamped")
                }
                
                // Vertical scaling: "compress [number]" or "stretch [number]"
                text.contains("compress", ignoreCase = true) -> {
                    val numbers = words.mapNotNull { it.toFloatOrNull() }
                    val delta = -(numbers.lastOrNull() ?: 0.1f) / 10.0f  // Negative delta = compress
                    val current = renderer?.getVerticalScale() ?: 1.0f
                    val newValue = (current + delta).coerceIn(0.1f, 3.0f)
                    renderer?.setVerticalScale(newValue)
                    showOverlay("VERTICAL SCALE: ${String.format("%.2f", newValue)}\nRange: 0.1 - 3.0\n(compress = shorter, stretch = taller)")
                    android.util.Log.d("VeilRenderer", "✓ Compress: $newValue")
                }
                
                text.contains("stretch", ignoreCase = true) -> {
                    val numbers = words.mapNotNull { it.toFloatOrNull() }
                    val delta = (numbers.lastOrNull() ?: 0.1f) / 10.0f  // Positive delta = stretch
                    val current = renderer?.getVerticalScale() ?: 1.0f
                    val newValue = (current + delta).coerceIn(0.1f, 3.0f)
                    renderer?.setVerticalScale(newValue)
                    showOverlay("VERTICAL SCALE: ${String.format("%.2f", newValue)}\nRange: 0.1 - 3.0\n(compress = shorter, stretch = taller)")
                    android.util.Log.d("VeilRenderer", "✓ Stretch: $newValue")
                }
                
                // Reset filters and scaling (but NOT eye position or resolution)
                text.contains("reset", ignoreCase = true) -> {
                    renderer?.setSharpness(0.5f)
                    renderer?.setContrast(1.0f)
                    renderer?.setBrightness(0.0f)
                    renderer?.setSaturation(1.0f)
                    renderer?.setVerticalScale(1.0f)
                    showOverlay("RESET:\nSharpness: 0.50\nContrast: 1.00\nBrightness: 0.00\nSaturation: 1.00\nVertical Scale: 1.00")
                    android.util.Log.d("VeilRenderer", "✓ Reset filters and scaling")
                }
                
                else -> {
                    android.util.Log.d("VeilRenderer", "Unknown command: $text")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("VeilRenderer", "Error parsing command: $text", e)
        }
    }

    private fun changeResolution(direction: Int) {
        val newIndex = currentResolutionIndex - direction  // -1 for UP (higher res), +1 for DOWN (lower res)
        val wasAtMin = currentResolutionIndex == RESOLUTIONS.size - 1
        val wasAtMax = currentResolutionIndex == 0
        
        if (newIndex < 0 || newIndex >= RESOLUTIONS.size) {
            // At min or max - flash red
            renderer?.flashRed()
            val res = RESOLUTIONS[currentResolutionIndex]
            showOverlay("RESOLUTION: ${res.first}x${res.second}\n${if (wasAtMax) "MAX" else "MIN"} reached")
            android.util.Log.d("VeilRenderer", "Resolution at ${if (wasAtMax) "max" else "min"} - ${RESOLUTIONS[currentResolutionIndex]}")
            return
        }
        
        currentResolutionIndex = newIndex
        val newRes = RESOLUTIONS[currentResolutionIndex]
        val nextUp = if (currentResolutionIndex > 0) RESOLUTIONS[currentResolutionIndex - 1] else null
        val nextDown = if (currentResolutionIndex < RESOLUTIONS.size - 1) RESOLUTIONS[currentResolutionIndex + 1] else null
        val overlayText = buildString {
            append("RESOLUTION: ${newRes.first}x${newRes.second}\n")
            if (nextUp != null) append("UP: ${nextUp.first}x${nextUp.second}  ")
            else append("UP: MAX  ")
            if (nextDown != null) append("DOWN: ${nextDown.first}x${nextDown.second}")
            else append("DOWN: MIN")
        }
        showOverlay(overlayText)
        android.util.Log.d("VeilRenderer", "Changing resolution to: ${newRes.first}x${newRes.second}")
        
        // Save resolution preference
        val prefs = getPreferences()
        prefs.edit().putInt("resolutionIndex", currentResolutionIndex).apply()
        
        // Reopen camera with new resolution
        currentCtrlBlock?.let { ctrlBlock ->
            // Stop current camera
            uvcCamera?.stopPreview()
            uvcCamera?.destroy()
            uvcCamera = null
            
            // Small delay then reopen
            Handler(mainLooper).postDelayed({
                openCamera(ctrlBlock)
            }, 100)
        }
    }
    
    private fun requestUsbPermission(device: UsbDevice) {
        usbMonitor?.requestPermission(device)
    }

    private fun openCamera(ctrlBlock: USBMonitor.UsbControlBlock) {
        try {
            android.util.Log.d("VeilRenderer", "Opening camera...")
            
            // Create UVCParam for camera configuration
            val uvcParam = UVCParam()
            uvcCamera = UVCCamera(uvcParam)
            
            // Open the camera device using UsbControlBlock
            uvcCamera?.open(ctrlBlock)
            android.util.Log.d("VeilRenderer", "Camera opened")
            
            // Wait for renderer surface to be ready
            glSurfaceView.post {
                cameraSurface = renderer?.getDecoderSurface()
                
                if (cameraSurface != null) {
                    android.util.Log.d("VeilRenderer", "Surface ready, configuring camera")
                    try {
                        // Get supported sizes
                        val supportedSizes = uvcCamera?.supportedSizeList
                        android.util.Log.d("VeilRenderer", "ELP 3D-1080p V85 - Supported sizes: $supportedSizes")
                        
                        // Find MJPEG format size based on current resolution index
                        val targetRes = RESOLUTIONS[currentResolutionIndex.coerceIn(0, RESOLUTIONS.size - 1)]
                        var selectedSize: com.serenegiant.usb.Size? = null
                        if (supportedSizes != null) {
                            // Try to find target resolution MJPEG first
                            selectedSize = supportedSizes.firstOrNull { 
                                it.width == targetRes.first && it.height == targetRes.second && it.type == 7 
                            }
                            if (selectedSize == null) {
                                // Try any matching resolution
                                selectedSize = supportedSizes.firstOrNull { 
                                    it.width == targetRes.first && it.height == targetRes.second 
                                }
                            }
                            if (selectedSize == null) {
                                // Use largest MJPEG
                                selectedSize = supportedSizes.filter { it.type == 7 }
                                    .maxByOrNull { it.width * it.height }
                            }
                            if (selectedSize == null) {
                                // Use largest overall
                                selectedSize = supportedSizes.maxByOrNull { it.width * it.height }
                            }
                        }
                        
                        if (selectedSize != null) {
                            android.util.Log.d("VeilRenderer", "Using size: ${selectedSize.width}x${selectedSize.height}, type: ${selectedSize.type}")
                            
                            // Update renderer with camera dimensions for aspect ratio
                            renderer?.setCameraSize(selectedSize.width, selectedSize.height)
                            
                            // Try different approaches - order might matter
                            var success = false
                            
                            // Approach 1: Set size first, then surface
                            try {
                                android.util.Log.d("VeilRenderer", "Trying: setPreviewSize first, then setPreviewDisplay")
                                val format = if (selectedSize.type == 7) UVCCamera.FRAME_FORMAT_MJPEG else UVCCamera.FRAME_FORMAT_YUYV
                                uvcCamera?.setPreviewSize(selectedSize.width, selectedSize.height, format)
                                uvcCamera?.setPreviewDisplay(cameraSurface)
                                uvcCamera?.startPreview()
                                android.util.Log.d("VeilRenderer", "Success with setPreviewSize(width, height, format)")
                                success = true
                            } catch (e1: Exception) {
                                android.util.Log.w("VeilRenderer", "Approach 1 failed", e1)
                                
                                // Approach 2: Use Size object
                                try {
                                    android.util.Log.d("VeilRenderer", "Trying: setPreviewSize with Size object")
                                    uvcCamera?.setPreviewSize(selectedSize)
                                    uvcCamera?.setPreviewDisplay(cameraSurface)
                                    uvcCamera?.startPreview()
                                    android.util.Log.d("VeilRenderer", "Success with setPreviewSize(Size)")
                                    success = true
                                } catch (e2: Exception) {
                                    android.util.Log.w("VeilRenderer", "Approach 2 failed", e2)
                                    
                                    // Approach 3: Set surface first, then size
                                    try {
                                        android.util.Log.d("VeilRenderer", "Trying: setPreviewDisplay first, then setPreviewSize")
                                        uvcCamera?.setPreviewDisplay(cameraSurface)
                                        val format = if (selectedSize.type == 7) UVCCamera.FRAME_FORMAT_MJPEG else UVCCamera.FRAME_FORMAT_YUYV
                                        uvcCamera?.setPreviewSize(selectedSize.width, selectedSize.height, format)
                                        uvcCamera?.startPreview()
                                        android.util.Log.d("VeilRenderer", "Success with setPreviewDisplay first")
                                        success = true
                                    } catch (e3: Exception) {
                                        android.util.Log.e("VeilRenderer", "All approaches failed", e3)
                                    }
                                }
                            }
                            
                            if (!success) {
                                android.util.Log.e("VeilRenderer", "Could not start preview with any method")
                            }
                        } else {
                            android.util.Log.e("VeilRenderer", "No supported size found!")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("VeilRenderer", "Error configuring camera", e)
                        e.printStackTrace()
                    }
                } else {
                    android.util.Log.e("VeilRenderer", "Surface not available!")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("VeilRenderer", "Error opening camera", e)
            e.printStackTrace()
        }
    }

    inner class StereoRenderer : GLSurfaceView.Renderer {
        private var textureId = 0
        private var surfaceTexture: SurfaceTexture? = null
        private var decoderSurface: Surface? = null
        private var mediaCodec: MediaCodec? = null
        private var shaderProgram = 0
        private var uMVPMatrixLoc = 0
        private var uTexMatrixLoc = 0
        private var aPositionLoc = 0
        private var aTexCoordLoc = 0
        private var uSharpnessLoc = 0
        private var uContrastLoc = 0
        private var uBrightnessLoc = 0
        private var uSaturationLoc = 0
        private var uTexSizeLoc = 0
        
        private val decoderThread = HandlerThread("DecoderThread").apply { start() }
        private val decoderHandler = Handler(decoderThread.looper)
        
        // Reference to GLSurfaceView for requesting renders
        private val glView: GLSurfaceView = glSurfaceView
        
        // Full screen quad: x, y, z, u, v
        private val fullScreenQuad = floatArrayOf(
            -1.0f, -1.0f, 0.0f, 0.0f, 1.0f,
             1.0f, -1.0f, 0.0f, 1.0f, 1.0f,
            -1.0f,  1.0f, 0.0f, 0.0f, 0.0f,
             1.0f,  1.0f, 0.0f, 1.0f, 0.0f
        )
        
        private val mvpMatrix = FloatArray(16)
        private val texMatrix = FloatArray(16)
        
        private var frameAvailable = false
        private var isPaused = false
        
        // Stereoscopic rendering settings
        private var convergenceFactor = 0.3f  // 0.0 = full separation, 1.0 = fully overlapped
        private var leftEyeOffset = 0f  // Pixels to shift left eye
        private var rightEyeOffset = 0f  // Pixels to shift right eye
        
        // Camera dimensions for aspect ratio calculation
        private var cameraWidth = 2560
        private var cameraHeight = 720
        private var screenWidth = 0
        private var screenHeight = 0
        
        // Red flash for min/max resolution warning
        private var redFlashUntil = 0L
        
        // Post-processing parameters
        private var sharpness = 0.5f  // 0.0 = no sharpening, 1.0 = max sharpening
        private var contrast = 1.0f   // 0.5 = low contrast, 1.0 = normal, 1.5 = high contrast
        private var brightness = 0.0f // -0.5 = darker, 0.0 = normal, 0.5 = brighter
        private var saturation = 1.0f // 0.0 = grayscale, 1.0 = normal, 2.0 = oversaturated
        
        // Vertical scaling (compress/stretch)
        private var verticalScale = 1.0f  // 0.5 = compressed (shorter), 1.0 = normal, 2.0 = stretched (taller)
        
        init {
            // Initialize identity matrices
            android.opengl.Matrix.setIdentityM(mvpMatrix, 0)
            android.opengl.Matrix.setIdentityM(texMatrix, 0)
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            // Load shaders
            val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, getVertexShaderSource())
            val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, getFragmentShaderSource())
            
            shaderProgram = GLES30.glCreateProgram()
            GLES30.glAttachShader(shaderProgram, vertexShader)
            GLES30.glAttachShader(shaderProgram, fragmentShader)
            GLES30.glLinkProgram(shaderProgram)
            
            // Get uniform and attribute locations
            uMVPMatrixLoc = GLES30.glGetUniformLocation(shaderProgram, "uMVPMatrix")
            uTexMatrixLoc = GLES30.glGetUniformLocation(shaderProgram, "uTexMatrix")
            aPositionLoc = GLES30.glGetAttribLocation(shaderProgram, "aPosition")
            aTexCoordLoc = GLES30.glGetAttribLocation(shaderProgram, "aTexCoord")
            uSharpnessLoc = GLES30.glGetUniformLocation(shaderProgram, "uSharpness")
            uContrastLoc = GLES30.glGetUniformLocation(shaderProgram, "uContrast")
            uBrightnessLoc = GLES30.glGetUniformLocation(shaderProgram, "uBrightness")
            uSaturationLoc = GLES30.glGetUniformLocation(shaderProgram, "uSaturation")
            uTexSizeLoc = GLES30.glGetUniformLocation(shaderProgram, "uTexSize")
            
            // Create external texture for camera frames
            val textures = IntArray(1)
            GLES30.glGenTextures(1, textures, 0)
            textureId = textures[0]
            
            // Setup texture
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES30.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES30.GL_TEXTURE_MIN_FILTER,
                GLES30.GL_LINEAR
            )
            GLES30.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES30.GL_TEXTURE_MAG_FILTER,
                GLES30.GL_LINEAR
            )
            
            // Create SurfaceTexture and Surface - UVCCamera will write decoded frames here
            surfaceTexture = SurfaceTexture(textureId)
            surfaceTexture?.setOnFrameAvailableListener {
                frameAvailable = true
                // Request render when new frame is available
                glView.post { glView.requestRender() }
            }
            decoderSurface = Surface(surfaceTexture)
            
            // Notify MainActivity that surface is ready
            android.util.Log.d("VeilRenderer", "SurfaceTexture created, surface ready")
            
            // Initialize MediaCodec
            initMediaCodec()
            
            GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            screenWidth = width
            screenHeight = height
            GLES30.glViewport(0, 0, width, height)
            updateMVPMatrix()
        }
        
        private fun updateMVPMatrix() {
            if (screenWidth == 0 || screenHeight == 0 || cameraWidth == 0 || cameraHeight == 0) return
            
            val cameraAspect = cameraWidth.toFloat() / cameraHeight.toFloat()
            val screenAspect = screenWidth.toFloat() / screenHeight.toFloat()
            
            android.opengl.Matrix.setIdentityM(mvpMatrix, 0)
            
            // Flip horizontally (mirror image for AR passthrough)
            android.opengl.Matrix.scaleM(mvpMatrix, 0, -1.0f, 1.0f, 1.0f)
            
            if (cameraAspect > screenAspect) {
                // Camera is wider than screen - letterbox (black bars top/bottom)
                val scale = screenAspect / cameraAspect
                android.opengl.Matrix.scaleM(mvpMatrix, 0, 1.0f, scale, 1.0f)
            } else {
                // Camera is taller than screen - pillarbox (black bars left/right)
                val scale = cameraAspect / screenAspect
                android.opengl.Matrix.scaleM(mvpMatrix, 0, scale, 1.0f, 1.0f)
            }
            
            // Apply vertical scaling (compress/stretch)
            android.opengl.Matrix.scaleM(mvpMatrix, 0, 1.0f, verticalScale, 1.0f)
        }
        
        fun setCameraSize(width: Int, height: Int) {
            cameraWidth = width
            cameraHeight = height
            updateMVPMatrix()
        }
        
        fun adjustLeftEyeOffset(pixels: Int) {
            leftEyeOffset += pixels
            saveConfiguration()
            // Request render to update display
            glView.post { glView.requestRender() }
            android.util.Log.d("VeilRenderer", "Left eye offset adjusted to: $leftEyeOffset")
        }
        
        fun adjustRightEyeOffset(pixels: Int) {
            rightEyeOffset += pixels
            saveConfiguration()
            // Request render to update display
            glView.post { glView.requestRender() }
            android.util.Log.d("VeilRenderer", "Right eye offset adjusted to: $rightEyeOffset")
        }
        
        fun setLeftEyeOffset(pixels: Int) {
            leftEyeOffset = pixels.toFloat()
            saveConfiguration()
            // Request render to update display
            glView.post { glView.requestRender() }
            android.util.Log.d("VeilRenderer", "Left eye offset set to: $leftEyeOffset")
        }
        
        fun setRightEyeOffset(pixels: Int) {
            rightEyeOffset = pixels.toFloat()
            saveConfiguration()
            // Request render to update display
            glView.post { glView.requestRender() }
            android.util.Log.d("VeilRenderer", "Right eye offset set to: $rightEyeOffset")
        }
        
        fun setConvergenceFactor(factor: Float) {
            convergenceFactor = factor.coerceIn(0f, 1f)
            saveConfiguration()
            // Request render to update display
            glView.post { glView.requestRender() }
            android.util.Log.d("VeilRenderer", "Convergence factor set to: $convergenceFactor")
        }
        
        fun setSharpness(value: Float) {
            sharpness = value.coerceIn(0f, 1f)
            saveConfiguration()
            glView.post { glView.requestRender() }
            android.util.Log.d("VeilRenderer", "Sharpness set to: $sharpness")
        }
        
        fun setContrast(value: Float) {
            contrast = value.coerceIn(0.5f, 1.5f)
            saveConfiguration()
            glView.post { glView.requestRender() }
            android.util.Log.d("VeilRenderer", "Contrast set to: $contrast")
        }
        
        fun setBrightness(value: Float) {
            brightness = value.coerceIn(-0.5f, 0.5f)
            saveConfiguration()
            glView.post { glView.requestRender() }
            android.util.Log.d("VeilRenderer", "Brightness set to: $brightness")
        }
        
        fun setSaturation(value: Float) {
            saturation = value.coerceIn(0f, 2f)
            saveConfiguration()
            glView.post { glView.requestRender() }
            android.util.Log.d("VeilRenderer", "Saturation set to: $saturation")
        }
        
        fun setVerticalScale(value: Float) {
            verticalScale = value.coerceIn(0.1f, 3.0f)
            updateMVPMatrix()
            saveConfiguration()
            glView.post { glView.requestRender() }
            android.util.Log.d("VeilRenderer", "Vertical scale set to: $verticalScale")
        }
        
        fun adjustVerticalScale(delta: Float) {
            verticalScale = (verticalScale + delta).coerceIn(0.1f, 3.0f)
            updateMVPMatrix()
            saveConfiguration()
            glView.post { glView.requestRender() }
            android.util.Log.d("VeilRenderer", "Vertical scale adjusted to: $verticalScale")
        }
        
        fun getVerticalScale(): Float = verticalScale
        
        fun adjustConvergence(pixels: Int) {
            // Adjust convergence by moving both eyes symmetrically
            // Positive pixels = move towards center (in)
            // Negative pixels = move away from center (out)
            leftEyeOffset += pixels
            rightEyeOffset -= pixels  // Opposite direction for right eye
            
            saveConfiguration()
            glView.post { glView.requestRender() }
            android.util.Log.d("VeilRenderer", "Convergence adjusted: L=$leftEyeOffset, R=$rightEyeOffset")
        }
        
        fun getLeftEyeOffset(): Int = leftEyeOffset.toInt()
        fun getRightEyeOffset(): Int = rightEyeOffset.toInt()
        fun getConvergenceFactor(): Float = convergenceFactor
        
        private fun saveConfiguration() {
            // Save to SharedPreferences via MainActivity
            (glSurfaceView.context as? Activity)?.let { activity ->
                val prefs = activity.getSharedPreferences("VeilRendererConfig", Context.MODE_PRIVATE)
                val editor = prefs.edit()
                editor.putInt("leftEyeOffset", leftEyeOffset.toInt())
                editor.putInt("rightEyeOffset", rightEyeOffset.toInt())
                editor.putFloat("convergenceFactor", convergenceFactor)
                editor.putFloat("sharpness", sharpness)
                editor.putFloat("contrast", contrast)
                editor.putFloat("brightness", brightness)
                editor.putFloat("saturation", saturation)
                editor.putFloat("verticalScale", verticalScale)
                editor.apply()
                android.util.Log.d("VeilRenderer", "Saved config: L=${leftEyeOffset.toInt()}, R=${rightEyeOffset.toInt()}, C=$convergenceFactor, Sharp=$sharpness, Cont=$contrast, Bright=$brightness, Sat=$saturation, VertScale=$verticalScale")
            }
        }
        
        fun flashRed() {
            redFlashUntil = System.currentTimeMillis() + 500  // Flash for 500ms
        }
        
        override fun onDrawFrame(gl: GL10?) {
            if (isPaused) return
            
            // Ensure viewport is set correctly
            if (screenWidth > 0 && screenHeight > 0) {
                GLES30.glViewport(0, 0, screenWidth, screenHeight)
            }
            
            // Check if we should flash red
            val shouldFlashRed = System.currentTimeMillis() < redFlashUntil
            if (shouldFlashRed) {
                GLES30.glClearColor(1.0f, 0.0f, 0.0f, 1.0f)  // Red
            } else {
                GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)  // Black
            }
            
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            
            // If flashing red, don't render camera - just show red screen
            if (shouldFlashRed) return
            
            // Always update texture if available, but don't skip rendering if no new frame
            if (frameAvailable) {
                try {
                    surfaceTexture?.updateTexImage()
                    surfaceTexture?.getTransformMatrix(texMatrix)
                    frameAvailable = false
                } catch (e: Exception) {
                    // Texture not ready yet - still render with last frame
                    android.util.Log.w("VeilRenderer", "Texture update failed", e)
                }
            }
            
            // Don't render if texture isn't ready
            if (textureId == 0) return
            
            // Render stereoscopic: left eye (left half) and right eye (right half)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES30.glUseProgram(shaderProgram)
            GLES30.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
            
            // Set post-processing uniforms
            if (uSharpnessLoc >= 0) GLES30.glUniform1f(uSharpnessLoc, sharpness)
            if (uContrastLoc >= 0) GLES30.glUniform1f(uContrastLoc, contrast)
            if (uBrightnessLoc >= 0) GLES30.glUniform1f(uBrightnessLoc, brightness)
            if (uSaturationLoc >= 0) GLES30.glUniform1f(uSaturationLoc, saturation)
            if (uTexSizeLoc >= 0 && cameraWidth > 0 && cameraHeight > 0) {
                GLES30.glUniform2f(uTexSizeLoc, cameraWidth.toFloat(), cameraHeight.toFloat())
            }
            
            // Calculate convergence (how close together the images are)
            val halfWidth = 0.5f * (1.0f - convergenceFactor)
            val centerOffset = convergenceFactor * 0.25f
            
            // Convert pixel offsets to normalized screen coordinates
            val leftOffsetNorm = if (screenWidth > 0) leftEyeOffset / (screenWidth / 2.0f) else 0f
            val rightOffsetNorm = if (screenWidth > 0) rightEyeOffset / (screenWidth / 2.0f) else 0f
            
            // Left eye: left half of texture (full height, left half width)
            val leftQuad = floatArrayOf(
                -1.0f + centerOffset + leftOffsetNorm, -1.0f, 0.0f, 0.0f, 1.0f,
                -1.0f + centerOffset + leftOffsetNorm + halfWidth * 2.0f, -1.0f, 0.0f, 0.5f, 1.0f,
                -1.0f + centerOffset + leftOffsetNorm,  1.0f, 0.0f, 0.0f, 0.0f,
                -1.0f + centerOffset + leftOffsetNorm + halfWidth * 2.0f,  1.0f, 0.0f, 0.5f, 0.0f
            )
            
            // Right eye: right half of texture (full height, right half width)
            val rightQuad = floatArrayOf(
                 1.0f - centerOffset - halfWidth * 2.0f + rightOffsetNorm, -1.0f, 0.0f, 0.5f, 1.0f,
                 1.0f - centerOffset + rightOffsetNorm, -1.0f, 0.0f, 1.0f, 1.0f,
                 1.0f - centerOffset - halfWidth * 2.0f + rightOffsetNorm,  1.0f, 0.0f, 0.5f, 0.0f,
                 1.0f - centerOffset + rightOffsetNorm,  1.0f, 0.0f, 1.0f, 0.0f
            )
            
            // Render left eye
            GLES30.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, mvpMatrix, 0)
            drawQuad(leftQuad)
            
            // Render right eye
            drawQuad(rightQuad)
        }
        
        private fun drawQuad(quad: FloatArray) {
            val vertexBuffer = ByteBuffer.allocateDirect(quad.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(quad)
                    position(0)
                }
            
            GLES30.glEnableVertexAttribArray(aPositionLoc)
            GLES30.glEnableVertexAttribArray(aTexCoordLoc)
            
            vertexBuffer.position(0)
            GLES30.glVertexAttribPointer(aPositionLoc, 3, GLES30.GL_FLOAT, false, 20, vertexBuffer)
            
            vertexBuffer.position(3)
            GLES30.glVertexAttribPointer(aTexCoordLoc, 2, GLES30.GL_FLOAT, false, 20, vertexBuffer)
            
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            
            GLES30.glDisableVertexAttribArray(aPositionLoc)
            GLES30.glDisableVertexAttribArray(aTexCoordLoc)
        }
        
        private fun initMediaCodec() {
            // Note: We're not using MediaCodec here - UVCCamera decodes MJPEG directly to SurfaceTexture
            // This method is kept for potential future use if we need to decode raw MJPEG bytes
            // For now, UVCCamera handles decoding when we call setPreviewDisplay()
        }
        
        fun onFrameReceived(frame: ByteArray) {
            if (isPaused || mediaCodec == null) return
            
            decoderHandler.post {
                try {
                    val decoder = mediaCodec ?: return@post
                    val inputBufferIndex = decoder.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                        inputBuffer?.clear()
                        inputBuffer?.put(frame)
                        decoder.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            frame.size,
                            System.nanoTime() / 1000,
                            0
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        fun pause() {
            isPaused = true
        }
        
        fun getDecoderSurface(): Surface? {
            return decoderSurface
        }
        
        fun release() {
            isPaused = true
            decoderHandler.post {
                try {
                    mediaCodec?.stop()
                    mediaCodec?.release()
                    mediaCodec = null
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            decoderThread.quitSafely()
            surfaceTexture?.release()
            decoderSurface?.release()
        }
        
        private fun loadShader(type: Int, shaderCode: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, shaderCode)
            GLES30.glCompileShader(shader)
            return shader
        }
        
        private fun getVertexShaderSource(): String {
            return """
                attribute vec4 aPosition;
                attribute vec2 aTexCoord;
                uniform mat4 uMVPMatrix;
                uniform mat4 uTexMatrix;
                varying vec2 vTextureCoord;
                
                void main() {
                    gl_Position = uMVPMatrix * aPosition;
                    vTextureCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
                }
            """.trimIndent()
        }
        
        private fun getFragmentShaderSource(): String {
            return """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                uniform samplerExternalOES sTexture;
                uniform float uSharpness;
                uniform float uContrast;
                uniform float uBrightness;
                uniform float uSaturation;
                uniform vec2 uTexSize;
                varying vec2 vTextureCoord;
                
                void main() {
                    vec2 texelSize = 1.0 / uTexSize;
                    vec4 color = texture2D(sTexture, vTextureCoord);
                    
                    // Unsharp mask for sharpening
                    vec4 blur = vec4(0.0);
                    blur += texture2D(sTexture, vTextureCoord + vec2(-texelSize.x, -texelSize.y)) * 0.0625;
                    blur += texture2D(sTexture, vTextureCoord + vec2(0.0, -texelSize.y)) * 0.125;
                    blur += texture2D(sTexture, vTextureCoord + vec2(texelSize.x, -texelSize.y)) * 0.0625;
                    blur += texture2D(sTexture, vTextureCoord + vec2(-texelSize.x, 0.0)) * 0.125;
                    blur += texture2D(sTexture, vTextureCoord) * 0.25;
                    blur += texture2D(sTexture, vTextureCoord + vec2(texelSize.x, 0.0)) * 0.125;
                    blur += texture2D(sTexture, vTextureCoord + vec2(-texelSize.x, texelSize.y)) * 0.0625;
                    blur += texture2D(sTexture, vTextureCoord + vec2(0.0, texelSize.y)) * 0.125;
                    blur += texture2D(sTexture, vTextureCoord + vec2(texelSize.x, texelSize.y)) * 0.0625;
                    
                    // Apply sharpening
                    color = mix(color, color + (color - blur) * uSharpness, uSharpness);
                    
                    // Apply contrast (center at 0.5)
                    color.rgb = (color.rgb - 0.5) * uContrast + 0.5;
                    
                    // Apply brightness
                    color.rgb += uBrightness;
                    
                    // Apply saturation
                    float gray = dot(color.rgb, vec3(0.299, 0.587, 0.114));
                    color.rgb = mix(vec3(gray), color.rgb, uSaturation);
                    
                    gl_FragColor = color;
                }
            """.trimIndent()
        }
        
    }
}

