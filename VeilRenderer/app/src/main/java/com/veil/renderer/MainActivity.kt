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
import android.media.AudioManager
import android.media.MediaCodec
import android.media.ToneGenerator
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
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : Activity() {
    companion object {
        private const val CAMERA_WIDTH = 2560
        private const val CAMERA_HEIGHT = 720
        private const val CAMERA_FPS = 60
        private const val REQUEST_CAMERA_PERMISSION = 1001
        private const val REQUEST_RECORD_AUDIO_PERMISSION = 1002
        private const val DEFAULT_LENS_K1 = -0.34f
        private const val DEFAULT_LENS_K2 = 0.12f
        private const val DEFAULT_LENS_K3 = -0.02f
        private const val DEFAULT_LENS_CENTER_X = 0.5f
        private const val DEFAULT_LENS_CENTER_Y = 0.5f

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
    private var isChangingResolution = false  // Prevent concurrent resolution changes
    private var overlayLeftEye: TextView? = null
    private var overlayRightEye: TextView? = null
    private var overlayHideHandler: Handler? = null
    private var toneGenerator: ToneGenerator? = null
    private var recordingIndicator: View? = null
    
    // Audio hack for silencing beep during speech recognition
    private lateinit var audioManager: AudioManager
    private var originalNotificationVolume = 0
    private var originalAlarmVolume = 0

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
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
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

        // Create overlay - only in left eye, smaller and centered
        overlayLeftEye = TextView(this).apply {
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            params.gravity = Gravity.NO_GRAVITY  // Use explicit margins
            layoutParams = params
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 7f  // 30% smaller (was 10f)
            setBackgroundColor(android.graphics.Color.argb(180, 0, 0, 0))  // Semi-transparent black
            setPadding(12, 6, 12, 6)  // Smaller padding
            gravity = android.view.Gravity.CENTER  // Center text within the TextView itself
            visibility = View.GONE
        }

        overlayRightEye = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 8f  // 30% smaller (was 12f)
            setBackgroundColor(android.graphics.Color.argb(180, 0, 0, 0))  // Semi-transparent black
            setPadding(16, 8, 16, 8)
            visibility = View.GONE
        }

        container.addView(overlayLeftEye)
        container.addView(overlayRightEye)
        // Keep right eye overlay hidden - overlay only shows in left eye
        overlayRightEye?.visibility = View.GONE

        // Create green recording indicator circle (4dp diameter, positioned in left eye)
        val indicatorSize = (4 * resources.displayMetrics.density).toInt()  // 4dp to pixels
        recordingIndicator = View(this).apply {
            val params = FrameLayout.LayoutParams(indicatorSize, indicatorSize)
            params.gravity = Gravity.NO_GRAVITY
            layoutParams = params
            setBackgroundColor(android.graphics.Color.GREEN)
            // Make it circular
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.GREEN)
            }
            visibility = View.GONE
        }
        container.addView(recordingIndicator)

        // Update overlay positions when layout changes
        container.viewTreeObserver.addOnGlobalLayoutListener {
            updateOverlayPositions()
        }

        overlayHideHandler = Handler(mainLooper)

        // Initialize tone generator for chime feedback (lower volume for subtle click)
        try {
            toneGenerator = ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 30)  // Lower volume (30 instead of 100) for subtle sound
        } catch (e: Exception) {
            android.util.Log.w("VeilRenderer", "Could not initialize tone generator", e)
        }

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

    private fun saveAllSettings() {
        // Save resolution
        val prefs = getPreferences()
        prefs.edit().putInt("resolutionIndex", currentResolutionIndex).commit()
        
        // Force renderer to save all its current settings
        renderer?.forceSaveConfiguration()
        
        android.util.Log.d("VeilRenderer", "All settings saved (resolution: $currentResolutionIndex)")
    }

    private fun getConfigSummary(): String {
        val r = renderer ?: return "No renderer"
        val res = RESOLUTIONS.getOrNull(currentResolutionIndex) ?: Pair(0, 0)
        val lensCoeffs = r.getLensCoefficients()
        
        // Convert values to 0-100 display scale
        val sharpnessDisplay = (r.getSharpness() * 100).toInt()
        val contrastDisplay = ((r.getContrast() - 0.5f) / 1.0f * 100).toInt().coerceIn(0, 100)
        val brightnessDisplay = ((r.getBrightness() + 0.5f) / 1.0f * 100).toInt().coerceIn(0, 100)
        val saturationDisplay = (r.getSaturation() / 2.0f * 100).toInt()
        val verticalScaleDisplay = ((r.getVerticalScale() - 0.1f) / 2.9f * 100).toInt().coerceIn(0, 100)
        
        return buildString {
            append("CONFIG SAVED\n")
            append("─────────────\n")
            append("Resolution: ${res.first}x${res.second}\n")
            append("Left Eye: H=${r.getLeftEyeOffset()}px V=${r.getLeftEyeOffsetY()}px\n")
            append("Right Eye: H=${r.getRightEyeOffset()}px V=${r.getRightEyeOffsetY()}px\n")
            append("Convergence: ${(r.getConvergenceFactor() * 100).toInt()}%\n")
            append("Vertical Scale: ${verticalScaleDisplay}%\n")
            append("Sharpness: ${sharpnessDisplay}%\n")
            append("Contrast: ${contrastDisplay}%\n")
            append("Brightness: ${brightnessDisplay}%\n")
            append("Saturation: ${saturationDisplay}%\n")
            if (lensCoeffs.isNotEmpty()) {
                append("Lens K1: ${String.format("%.3f", lensCoeffs[0])}\n")
                append("Lens K2: ${String.format("%.3f", lensCoeffs[1])}\n")
                append("Lens K3: ${String.format("%.3f", lensCoeffs[2])}")
            }
        }
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
        renderer?.setLeftEyeOffsetY(prefs.getInt("leftEyeOffsetY", 0))
        renderer?.setRightEyeOffsetY(prefs.getInt("rightEyeOffsetY", 0))
        renderer?.setConvergenceFactor(convergenceFactor)
        renderer?.setSharpness(prefs.getFloat("sharpness", 0.5f))
        renderer?.setContrast(prefs.getFloat("contrast", 1.0f))
        renderer?.setBrightness(prefs.getFloat("brightness", 0.0f))
        renderer?.setSaturation(prefs.getFloat("saturation", 1.0f))
        renderer?.setVerticalScale(prefs.getFloat("verticalScale", 1.0f))
        val k1 = prefs.getFloat("lensK1", DEFAULT_LENS_K1)
        val k2 = prefs.getFloat("lensK2", DEFAULT_LENS_K2)
        val k3 = prefs.getFloat("lensK3", DEFAULT_LENS_K3)
        renderer?.setLensCoefficients(k1, k2, k3)
        renderer?.setLensCenters(
            prefs.getFloat("lensCenterLeftX", DEFAULT_LENS_CENTER_X),
            prefs.getFloat("lensCenterLeftY", DEFAULT_LENS_CENTER_Y),
            prefs.getFloat("lensCenterRightX", DEFAULT_LENS_CENTER_X),
            prefs.getFloat("lensCenterRightY", DEFAULT_LENS_CENTER_Y)
        )

        android.util.Log.d("VeilRenderer", "Loaded config: L=$leftEyeOffset, R=$rightEyeOffset, LY=${prefs.getInt("leftEyeOffsetY", 0)}, RY=${prefs.getInt("rightEyeOffsetY", 0)}, C=$convergenceFactor, VertScale=${prefs.getFloat("verticalScale", 1.0f)}, Res=${currentResolutionIndex}, K1=$k1, K2=$k2, K3=$k3")
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
        // Save all settings before pausing
        saveAllSettings()
        glSurfaceView.onPause()
        renderer?.pause()
        uvcCamera?.stopPreview()
        uvcCamera?.destroy()
        stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Save all settings before destroying
        saveAllSettings()
        speechRecognizer?.destroy()
        speechRecognizer = null
        renderer?.release()
        uvcCamera?.destroy()
        uvcCamera = null
        usbMonitor?.unregister()
        usbMonitor?.destroy()
        usbMonitor = null
        toneGenerator?.release()
        toneGenerator = null
        unmuteSystemSound()  // Restore system sounds when app closes
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
                // Use default timeout settings - custom timeouts were preventing recognition
            }

            try {
                isListening = true
                speechRecognizer?.startListening(intent)
                // Indicator will show in onReadyForSpeech callback
                android.util.Log.d("VeilRenderer", "Started listening")
            } catch (e: Exception) {
                isListening = false
                recordingIndicator?.visibility = View.GONE
                android.util.Log.e("VeilRenderer", "Speech recognition error", e)
            }
        }, 200)  // Wait 200ms after stopping
    }

    private fun stopListening() {
        if (!isListening) return
        try {
            speechRecognizer?.stopListening()
            isListening = false
            recordingIndicator?.visibility = View.GONE
            unmuteSystemSound()  // Restore system sounds when stopping
        } catch (e: Exception) {
            isListening = false
            recordingIndicator?.visibility = View.GONE
            unmuteSystemSound()  // Restore system sounds even on error
        }
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                recordingIndicator?.visibility = View.VISIBLE
                muteSystemSound()  // Mute system sounds when mic is hot
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                // Don't set isListening = false here - wait for results or error
                // This keeps the mic indicator on during processing
            }
            override fun onError(error: Int) {
                isListening = false
                recordingIndicator?.visibility = View.GONE
                unmuteSystemSound()  // Restore system sounds when mic stops
                android.util.Log.d("VeilRenderer", "Speech recognition error: $error")

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
                    }, 1000L)  // Wait longer for busy error
                } else {
                    // ERROR_NO_MATCH and ERROR_SPEECH_TIMEOUT are normal - just restart quickly
                    if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        android.util.Log.w("VeilRenderer", "Speech error: $error")
                    }
                    // Always restart - these errors are normal for continuous listening
                    Handler(mainLooper).postDelayed({
                        if (ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED && !isListening) {
                            android.util.Log.d("VeilRenderer", "Restarting listening after error")
                            startContinuousListening()
                        }
                    }, 200L)  // Quick restart for all errors
                }
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                recordingIndicator?.visibility = View.GONE
                unmuteSystemSound()  // Restore system sounds when recognition completes
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null && matches.isNotEmpty()) {
                    val spokenText = matches[0].lowercase().trim()
                    android.util.Log.d("VeilRenderer", "Recognized: $spokenText")
                    parseVoiceCommand(spokenText)
                }
                // Restart quickly after processing results to maintain continuous listening
                Handler(mainLooper).postDelayed({
                    if (ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED && !isListening) {
                        startContinuousListening()
                    }
                }, 100)  // Reduced delay for faster restart
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
        // Get screen dimensions from display metrics
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        overlayLeftEye?.let { left ->
            val parent = left.parent as? FrameLayout
            if (parent != null) {
                val parentWidth = parent.width
                val parentHeight = parent.height

                if (parentWidth > 0 && parentHeight > 0) {
                    val params = left.layoutParams as FrameLayout.LayoutParams
                    // Constrain measurement to left half of screen
                    left.measure(
                        View.MeasureSpec.makeMeasureSpec(parentWidth / 2, View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(parentHeight, View.MeasureSpec.AT_MOST)
                    )
                    val leftWidth = left.measuredWidth
                    val leftHeight = left.measuredHeight
                    // Center of left eye frame: x = parentWidth/4 (center of left half), y = parentHeight/2 (vertical center)
                    params.leftMargin = (parentWidth / 4) - (leftWidth / 2)
                    params.topMargin = (parentHeight / 2) - (leftHeight / 2)
                    params.gravity = Gravity.NO_GRAVITY  // Use explicit margins, not gravity
                    left.layoutParams = params
                    android.util.Log.d("VeilRenderer", "Overlay positioned: leftMargin=${params.leftMargin}, topMargin=${params.topMargin}, screen=$parentWidth x $parentHeight")
                }
            }
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

        // Position recording indicator: 100px from left, 100px from bottom (in left eye viewport)
        recordingIndicator?.let { indicator ->
            val parent = indicator.parent as? FrameLayout
            if (parent != null) {
                val parentWidth = parent.width
                val parentHeight = parent.height

                if (parentWidth > 0 && parentHeight > 0) {
                    val params = indicator.layoutParams as FrameLayout.LayoutParams
                    val indicatorSize = (4 * resources.displayMetrics.density).toInt()
                    params.width = indicatorSize
                    params.height = indicatorSize
                    // Position in left eye frame: left half of screen, 100px from left edge of left eye, 100px from bottom
                    // Since left eye is left half, 100px from left edge of screen is correct
                    // But ensure it doesn't go beyond the left half boundary
                    params.leftMargin = 100.coerceAtMost(parentWidth / 2 - indicatorSize)  // 100px from left, but stay within left half
                    params.topMargin = parentHeight - 100 - indicatorSize  // 100 pixels from bottom
                    params.gravity = Gravity.NO_GRAVITY
                    indicator.layoutParams = params
                }
            }
        }
    }

    private fun showOverlay(message: String, durationMs: Long = 2000) {
        overlayHideHandler?.removeCallbacksAndMessages(null)  // Cancel any pending hide
        // Only show overlay in left eye
        overlayLeftEye?.let { overlay ->
            overlay.text = message
            overlay.visibility = View.VISIBLE
            updateOverlayPositions()  // Update positions when showing
        }
        // Keep right eye overlay hidden
        overlayRightEye?.visibility = View.GONE
        overlayHideHandler?.postDelayed({
            overlayLeftEye?.visibility = View.GONE
        }, durationMs)
    }

    private fun playChime() {
        // Disabled - tone generator not producing subtle click sound
        // Could implement with MediaPlayer and a click sound file if needed
    }

    // --- AUDIO HACK FOR SILENCING BEEP ---
    private fun muteSystemSound() {
        try {
            originalNotificationVolume = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
            originalAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            
            // Mute Notification and Alarm streams
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun unmuteSystemSound() {
        try {
            // Restore original volumes
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, originalNotificationVolume, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalAlarmVolume, 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
                        playChime()
                        android.util.Log.d("VeilRenderer", "✓ Viewer's left ${if (isIn) "in" else "out"} $pixels pixels")
                    } else if (isRight) {
                        // Viewer's right: "in" = move left (negative), "out" = move right (positive)
                        val offset = if (isIn) -pixels else pixels
                        renderer?.adjustRightEyeOffset(offset)
                        val current = renderer?.getRightEyeOffset() ?: 0
                        showOverlay("RIGHT EYE: ${current}px\nRange: unlimited")
                        playChime()
                        android.util.Log.d("VeilRenderer", "✓ Viewer's right ${if (isIn) "in" else "out"} $pixels pixels")
                    }
                }

                // Pattern: [left/right] [up/down] [number]
                // "left up 50" or "right down 30"
                (words.contains("left") || words.contains("right")) &&
                (words.contains("up") || words.contains("down")) -> {
                    val isLeft = words.contains("left")
                    val isRight = words.contains("right")
                    val isUp = words.contains("up")
                    val isDown = words.contains("down")

                    val numbers = words.mapNotNull { it.toIntOrNull() }
                    val pixels = numbers.lastOrNull() ?: 10

                    // "up" = move up (positive Y), "down" = move down (negative Y)
                    val offset = if (isUp) pixels else -pixels
                    if (isLeft) {
                        renderer?.adjustLeftEyeOffsetY(offset)
                        val current = renderer?.getLeftEyeOffsetY() ?: 0
                        showOverlay("LEFT EYE VERTICAL: ${current}px\nRange: unlimited")
                        playChime()
                        android.util.Log.d("VeilRenderer", "✓ Left eye ${if (isUp) "up" else "down"} $pixels pixels")
                    } else if (isRight) {
                        renderer?.adjustRightEyeOffsetY(offset)
                        val current = renderer?.getRightEyeOffsetY() ?: 0
                        showOverlay("RIGHT EYE VERTICAL: ${current}px\nRange: unlimited")
                        playChime()
                        android.util.Log.d("VeilRenderer", "✓ Right eye ${if (isUp) "up" else "down"} $pixels pixels")
                    }
                }

                // Pattern: "convergence [0-100]" or "converge [0-100]"
                text.contains("converge", ignoreCase = true) -> {
                    val numbers = words.mapNotNull { it.toIntOrNull() }
                    val inputValue = numbers.lastOrNull() ?: 30  // Default to 30 (middle)
                    val clampedInput = inputValue.coerceIn(0, 100)
                    // Convert 0-100 to 0.0-1.0
                    val filterValue = clampedInput / 100.0f
                    renderer?.setConvergenceFactor(filterValue)
                    showOverlay("CONVERGENCE: $clampedInput\nRange: 0 - 100")
                    playChime()
                    android.util.Log.d("VeilRenderer", "✓ Convergence: $clampedInput -> $filterValue")
                }

                // Resolution control: "UP" or "DOWN"
                words.contains("up") -> {
                    changeResolution(1)  // Increase resolution (lower index)
                }

                words.contains("down") -> {
                    changeResolution(-1)  // Decrease resolution (higher index)
                }

                // Post-processing: "sharpness [0-100]", "contrast [0-100]", "brightness [0-100]", "saturation [0-100]"
                text.contains("sharpness", ignoreCase = true) || text.contains("sharp", ignoreCase = true) -> {
                    val numbers = words.mapNotNull { it.toIntOrNull() }
                    val inputValue = numbers.lastOrNull() ?: 50  // Default to 50 (middle)
                    val clampedInput = inputValue.coerceIn(0, 100)
                    // Convert 0-100 to 0.0-1.0
                    val filterValue = clampedInput / 100.0f
                    renderer?.setSharpness(filterValue)
                    showOverlay("SHARPNESS: $clampedInput\nRange: 0 - 100")
                    playChime()
                    android.util.Log.d("VeilRenderer", "✓ Sharpness: $clampedInput -> $filterValue")
                }

                text.contains("contrast", ignoreCase = true) -> {
                    val numbers = words.mapNotNull { it.toIntOrNull() }
                    val inputValue = numbers.lastOrNull() ?: 50  // Default to 50 (middle)
                    val clampedInput = inputValue.coerceIn(0, 100)
                    // Convert 0-100 to 0.5-1.5 (0.5 + (value/100) * 1.0)
                    val filterValue = 0.5f + (clampedInput / 100.0f) * 1.0f
                    renderer?.setContrast(filterValue)
                    showOverlay("CONTRAST: $clampedInput\nRange: 0 - 100")
                    playChime()
                    android.util.Log.d("VeilRenderer", "✓ Contrast: $clampedInput -> $filterValue")
                }

                text.contains("brightness", ignoreCase = true) -> {
                    val numbers = words.mapNotNull { it.toIntOrNull() }
                    val inputValue = numbers.lastOrNull() ?: 50  // Default to 50 (middle)
                    val clampedInput = inputValue.coerceIn(0, 100)
                    // Convert 0-100 to -0.5-0.5 ((value/100) * 1.0 - 0.5)
                    val filterValue = (clampedInput / 100.0f) * 1.0f - 0.5f
                    renderer?.setBrightness(filterValue)
                    showOverlay("BRIGHTNESS: $clampedInput\nRange: 0 - 100")
                    playChime()
                    android.util.Log.d("VeilRenderer", "✓ Brightness: $clampedInput -> $filterValue")
                }

                text.contains("saturation", ignoreCase = true) -> {
                    val numbers = words.mapNotNull { it.toIntOrNull() }
                    val inputValue = numbers.lastOrNull() ?: 50  // Default to 50 (middle)
                    val clampedInput = inputValue.coerceIn(0, 100)
                    // Convert 0-100 to 0.0-2.0 (value/100 * 2.0)
                    val filterValue = (clampedInput / 100.0f) * 2.0f
                    renderer?.setSaturation(filterValue)
                    showOverlay("SATURATION: $clampedInput\nRange: 0 - 100")
                    playChime()
                    android.util.Log.d("VeilRenderer", "✓ Saturation: $clampedInput -> $filterValue")
                }

                // Vertical scaling: "compress [0-100]" or "stretch [0-100]" or "vertical scale [0-100]"
                text.contains("vertical scale", ignoreCase = true) || text.contains("vertical", ignoreCase = true) -> {
                    val numbers = words.mapNotNull { it.toIntOrNull() }
                    val inputValue = numbers.lastOrNull() ?: 50  // Default to 50 (middle)
                    val clampedInput = inputValue.coerceIn(0, 100)
                    // Convert 0-100 to 0.1-3.0 (0.1 + (value/100) * 2.9)
                    val filterValue = 0.1f + (clampedInput / 100.0f) * 2.9f
                    renderer?.setVerticalScale(filterValue)
                    showOverlay("VERTICAL SCALE: $clampedInput\nRange: 0 - 100\n(0 = compressed, 100 = stretched)")
                    playChime()
                    android.util.Log.d("VeilRenderer", "✓ Vertical Scale: $clampedInput -> $filterValue")
                }

                text.contains("compress", ignoreCase = true) -> {
                    val numbers = words.mapNotNull { it.toIntOrNull() }
                    val inputValue = numbers.lastOrNull() ?: 10  // Default to 10
                    val clampedInput = inputValue.coerceIn(0, 100)
                    // Convert 0-100 to 0.1-3.0, then apply as negative delta
                    val targetValue = 0.1f + (clampedInput / 100.0f) * 2.9f
                    val current = renderer?.getVerticalScale() ?: 1.0f
                    val newValue = (current - (current - targetValue) * 0.1f).coerceIn(0.1f, 3.0f)
                    renderer?.setVerticalScale(newValue)
                    val displayValue = ((newValue - 0.1f) / 2.9f * 100f).toInt().coerceIn(0, 100)
                    showOverlay("VERTICAL SCALE: $displayValue\nRange: 0 - 100\n(compress = shorter)")
                    playChime()
                    android.util.Log.d("VeilRenderer", "✓ Compress: $clampedInput -> $newValue")
                }

                text.contains("stretch", ignoreCase = true) -> {
                    val numbers = words.mapNotNull { it.toIntOrNull() }
                    val inputValue = numbers.lastOrNull() ?: 10  // Default to 10
                    val clampedInput = inputValue.coerceIn(0, 100)
                    // Convert 0-100 to 0.1-3.0, then apply as positive delta
                    val targetValue = 0.1f + (clampedInput / 100.0f) * 2.9f
                    val current = renderer?.getVerticalScale() ?: 1.0f
                    val newValue = (current + (targetValue - current) * 0.1f).coerceIn(0.1f, 3.0f)
                    renderer?.setVerticalScale(newValue)
                    val displayValue = ((newValue - 0.1f) / 2.9f * 100f).toInt().coerceIn(0, 100)
                    showOverlay("VERTICAL SCALE: $displayValue\nRange: 0 - 100\n(stretch = taller)")
                    playChime()
                    android.util.Log.d("VeilRenderer", "✓ Stretch: $clampedInput -> $newValue")
                }

                // Save configuration and show summary
                text.contains("save", ignoreCase = true) -> {
                    saveAllSettings()
                    val summary = getConfigSummary()
                    showOverlay(summary, 5000)  // Show for 5 seconds
                    playChime()
                    android.util.Log.d("VeilRenderer", "✓ Configuration saved")
                }

                // Reset filters and scaling (but NOT eye position or resolution)
                text.contains("reset", ignoreCase = true) -> {
                    renderer?.setSharpness(0.5f)  // 50 in 0-100 scale
                    renderer?.setContrast(1.0f)   // 50 in 0-100 scale
                    renderer?.setBrightness(0.0f) // 50 in 0-100 scale
                    renderer?.setSaturation(1.0f) // 50 in 0-100 scale
                    renderer?.setVerticalScale(1.0f) // ~34 in 0-100 scale
                    showOverlay("RESET:\nSharpness: 50\nContrast: 50\nBrightness: 50\nSaturation: 50\nVertical Scale: 34")
                    playChime()
                    android.util.Log.d("VeilRenderer", "✓ Reset filters and scaling")
                }

                text.contains("lens k1", ignoreCase = true) -> {
                    val value = extractLastFloat(words) ?: DEFAULT_LENS_K1
                    val clamped = value.coerceIn(-2.0f, 2.0f)
                    renderer?.let { r ->
                        val coeffs = r.getLensCoefficients()
                        r.setLensCoefficients(clamped, coeffs[1], coeffs[2])
                        val formatted = formatLensCoefficient(clamped)
                        showOverlay("LENS K1: $formatted")
                        android.util.Log.d("VeilRenderer", "✓ Lens k1 set to $formatted")
                    }
                }

                text.contains("lens k2", ignoreCase = true) -> {
                    val value = extractLastFloat(words) ?: DEFAULT_LENS_K2
                    val clamped = value.coerceIn(-2.0f, 2.0f)
                    renderer?.let { r ->
                        val coeffs = r.getLensCoefficients()
                        r.setLensCoefficients(coeffs[0], clamped, coeffs[2])
                        val formatted = formatLensCoefficient(clamped)
                        showOverlay("LENS K2: $formatted")
                        android.util.Log.d("VeilRenderer", "✓ Lens k2 set to $formatted")
                    }
                }

                text.contains("lens k3", ignoreCase = true) -> {
                    val value = extractLastFloat(words) ?: DEFAULT_LENS_K3
                    val clamped = value.coerceIn(-2.0f, 2.0f)
                    renderer?.let { r ->
                        val coeffs = r.getLensCoefficients()
                        r.setLensCoefficients(coeffs[0], coeffs[1], clamped)
                        val formatted = formatLensCoefficient(clamped)
                        showOverlay("LENS K3: $formatted")
                        android.util.Log.d("VeilRenderer", "✓ Lens k3 set to $formatted")
                    }
                }

                // Barrel distortion adjustment: "barrel up" (increase) or "barrel down" (decrease)
                text.contains("barrel", ignoreCase = true) && words.contains("up") -> {
                    val delta = 0.05f  // Step size for barrel adjustment
                    renderer?.adjustBarrelDistortion(delta)
                    renderer?.let { r ->
                        val coeffs = r.getLensCoefficients()
                        showOverlay("BARREL UP\nK1: ${String.format("%.3f", coeffs[0])}\nK2: ${String.format("%.3f", coeffs[1])}\nK3: ${String.format("%.3f", coeffs[2])}")
                        playChime()
                        android.util.Log.d("VeilRenderer", "✓ Barrel distortion increased")
                    }
                }

                text.contains("barrel", ignoreCase = true) && words.contains("down") -> {
                    val delta = -0.05f  // Step size for barrel adjustment (negative to decrease)
                    renderer?.adjustBarrelDistortion(delta)
                    renderer?.let { r ->
                        val coeffs = r.getLensCoefficients()
                        showOverlay("BARREL DOWN\nK1: ${String.format("%.3f", coeffs[0])}\nK2: ${String.format("%.3f", coeffs[1])}\nK3: ${String.format("%.3f", coeffs[2])}")
                        playChime()
                        android.util.Log.d("VeilRenderer", "✓ Barrel distortion decreased")
                    }
                }

                else -> {
                    android.util.Log.d("VeilRenderer", "Unknown command: $text")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("VeilRenderer", "Error parsing command: $text", e)
        }
    }

    private fun extractLastFloat(words: List<String>): Float? {
        for (word in words.asReversed()) {
            word.toFloatOrNull()?.let { return it }
        }
        return null
    }

    private fun formatLensCoefficient(value: Float): String {
        return String.format(Locale.US, "%.3f", value)
    }

    private fun changeResolution(direction: Int) {
        // Prevent concurrent resolution changes
        if (isChangingResolution) {
            android.util.Log.d("VeilRenderer", "Resolution change already in progress, ignoring")
            return
        }

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
        playChime()
        android.util.Log.d("VeilRenderer", "Changing resolution to: ${newRes.first}x${newRes.second}")

        // Save resolution preference (use commit() to ensure immediate persistence)
        val prefs = getPreferences()
        prefs.edit().putInt("resolutionIndex", currentResolutionIndex).commit()

        // Reopen camera with new resolution
        val ctrlBlock = currentCtrlBlock
        if (ctrlBlock != null) {
            isChangingResolution = true

            // Stop current camera properly
            try {
                uvcCamera?.stopPreview()
            } catch (e: Exception) {
                android.util.Log.w("VeilRenderer", "Error stopping preview", e)
            }

            // Don't release cameraSurface - it's managed by the renderer
            // Just clear our reference
            cameraSurface = null

            try {
                uvcCamera?.destroy()
            } catch (e: Exception) {
                android.util.Log.w("VeilRenderer", "Error destroying camera", e)
            }
            uvcCamera = null

            // Wait longer for camera to fully release before reopening
            Handler(mainLooper).postDelayed({
                try {
                    openCamera(ctrlBlock)
                    isChangingResolution = false
                } catch (e: Exception) {
                    android.util.Log.e("VeilRenderer", "Error reopening camera", e)
                    // Try again after another delay
                    Handler(mainLooper).postDelayed({
                        try {
                            openCamera(ctrlBlock)
                            isChangingResolution = false
                        } catch (e2: Exception) {
                            android.util.Log.e("VeilRenderer", "Failed to reopen camera after retry", e2)
                            isChangingResolution = false
                        }
                    }, 1000)
                }
            }, 500)  // Increased delay to 500ms to ensure camera is fully released
        } else {
            android.util.Log.w("VeilRenderer", "No control block available for resolution change")
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
                // Get fresh surface from renderer
                cameraSurface = renderer?.getDecoderSurface()

                if (cameraSurface != null) {
                    android.util.Log.d("VeilRenderer", "Surface ready, configuring camera")
                    // Small delay to ensure surface is fully ready
                    Handler(mainLooper).postDelayed({
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
                    }, 100)  // Small delay to ensure surface is ready
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
        private var sceneFramebuffer = 0
        private var sceneTexture = 0
        private var sceneDepthBuffer = 0
        private var sceneWidth = 0
        private var sceneHeight = 0
        private var distortionProgram = 0
        private var distPositionLoc = 0
        private var distTexCoordLoc = 0
        private var uSceneTextureLoc = 0
        private var uLensCoeffsLoc = 0
        private var uLensCenterLeftLoc = 0
        private var uLensCenterRightLoc = 0
        private var uEyeAspectLoc = 0

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
        private var leftEyeOffset = 0f  // Pixels to shift left eye horizontally
        private var rightEyeOffset = 0f  // Pixels to shift right eye horizontally
        private var leftEyeOffsetY = 0f  // Pixels to shift left eye vertically
        private var rightEyeOffsetY = 0f  // Pixels to shift right eye vertically

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
        private val lensCoefficients = floatArrayOf(-0.34f, 0.12f, -0.02f)
        private val lensCenterLeft = floatArrayOf(0.5f, 0.5f)
        private val lensCenterRight = floatArrayOf(0.5f, 0.5f)

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

            // Distortion shader program (GL_TEXTURE_2D input)
            try {
                val distVertexShader = loadShader(
                    GLES30.GL_VERTEX_SHADER,
                    loadAssetShader("shaders/distort.vert")
                )
                val distFragmentShader = loadShader(
                    GLES30.GL_FRAGMENT_SHADER,
                    loadAssetShader("shaders/distort.frag")
                )
                distortionProgram = GLES30.glCreateProgram()
                GLES30.glAttachShader(distortionProgram, distVertexShader)
                GLES30.glAttachShader(distortionProgram, distFragmentShader)
                GLES30.glLinkProgram(distortionProgram)
                distPositionLoc = GLES30.glGetAttribLocation(distortionProgram, "aPosition")
                distTexCoordLoc = GLES30.glGetAttribLocation(distortionProgram, "aTexCoord")
                uSceneTextureLoc = GLES30.glGetUniformLocation(distortionProgram, "uSceneTexture")
                uLensCoeffsLoc = GLES30.glGetUniformLocation(distortionProgram, "uLensCoeffs")
                uLensCenterLeftLoc = GLES30.glGetUniformLocation(distortionProgram, "uLensCenterLeft")
                uLensCenterRightLoc = GLES30.glGetUniformLocation(distortionProgram, "uLensCenterRight")
                uEyeAspectLoc = GLES30.glGetUniformLocation(distortionProgram, "uEyeAspect")
            } catch (e: Exception) {
                android.util.Log.e("VeilRenderer", "Failed to build distortion shader", e)
                distortionProgram = 0
            }

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
            setupSceneFramebuffer(width, height)
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

        private fun setupSceneFramebuffer(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            if (sceneWidth == width && sceneHeight == height && sceneFramebuffer != 0) return

            releaseSceneFramebuffer()

            sceneWidth = width
            sceneHeight = height

            val framebuffers = IntArray(1)
            GLES30.glGenFramebuffers(1, framebuffers, 0)
            sceneFramebuffer = framebuffers[0]

            val textures = IntArray(1)
            GLES30.glGenTextures(1, textures, 0)
            sceneTexture = textures[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTexture)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                GLES30.GL_RGBA,
                width,
                height,
                0,
                GLES30.GL_RGBA,
                GLES30.GL_UNSIGNED_BYTE,
                null
            )
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

            val renderbuffers = IntArray(1)
            GLES30.glGenRenderbuffers(1, renderbuffers, 0)
            sceneDepthBuffer = renderbuffers[0]
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, sceneDepthBuffer)
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT16, width, height)

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFramebuffer)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D,
                sceneTexture,
                0
            )
            GLES30.glFramebufferRenderbuffer(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_DEPTH_ATTACHMENT,
                GLES30.GL_RENDERBUFFER,
                sceneDepthBuffer
            )

            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                android.util.Log.e("VeilRenderer", "Scene framebuffer incomplete: 0x${Integer.toHexString(status)}")
            }

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, 0)
        }

        private fun releaseSceneFramebuffer() {
            if (sceneTexture != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(sceneTexture), 0)
                sceneTexture = 0
            }
            if (sceneFramebuffer != 0) {
                GLES30.glDeleteFramebuffers(1, intArrayOf(sceneFramebuffer), 0)
                sceneFramebuffer = 0
            }
            if (sceneDepthBuffer != 0) {
                GLES30.glDeleteRenderbuffers(1, intArrayOf(sceneDepthBuffer), 0)
                sceneDepthBuffer = 0
            }
            sceneWidth = 0
            sceneHeight = 0
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

        fun adjustLeftEyeOffsetY(pixels: Int) {
            leftEyeOffsetY += pixels
            saveConfiguration()
            glView.post { glView.requestRender() }
            android.util.Log.d("VeilRenderer", "Left eye vertical offset adjusted to: $leftEyeOffsetY")
        }

        fun adjustRightEyeOffsetY(pixels: Int) {
            rightEyeOffsetY += pixels
            saveConfiguration()
            glView.post { glView.requestRender() }
            android.util.Log.d("VeilRenderer", "Right eye vertical offset adjusted to: $rightEyeOffsetY")
        }

        fun setLeftEyeOffsetY(pixels: Int) {
            leftEyeOffsetY = pixels.toFloat()
            saveConfiguration()
            glView.post { glView.requestRender() }
            android.util.Log.d("VeilRenderer", "Left eye vertical offset set to: $leftEyeOffsetY")
        }

        fun setRightEyeOffsetY(pixels: Int) {
            rightEyeOffsetY = pixels.toFloat()
            saveConfiguration()
            glView.post { glView.requestRender() }
            android.util.Log.d("VeilRenderer", "Right eye vertical offset set to: $rightEyeOffsetY")
        }

        fun getLeftEyeOffsetY(): Int = leftEyeOffsetY.toInt()
        fun getRightEyeOffsetY(): Int = rightEyeOffsetY.toInt()

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

        fun getSharpness(): Float = sharpness
        fun getContrast(): Float = contrast
        fun getBrightness(): Float = brightness
        fun getSaturation(): Float = saturation

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

        fun setLensCoefficients(k1: Float, k2: Float, k3: Float) {
            lensCoefficients[0] = k1
            lensCoefficients[1] = k2
            lensCoefficients[2] = k3
            saveConfiguration()
            glView.post { glView.requestRender() }
            android.util.Log.d("VeilRenderer", "Lens coefficients set to: k1=$k1, k2=$k2, k3=$k3")
        }

        fun adjustBarrelDistortion(delta: Float) {
            // Adjust k1 primarily (barrel distortion), and k2/k3 proportionally
            // Negative k1 = barrel distortion, positive = pincushion
            // To increase barrel: make k1 more negative
            // To decrease barrel: make k1 less negative (closer to 0)
            val currentK1 = lensCoefficients[0]
            val currentK2 = lensCoefficients[1]
            val currentK3 = lensCoefficients[2]
            
            // Adjust k1 (primary barrel control)
            val newK1 = (currentK1 - delta).coerceIn(-2.0f, 2.0f)
            
            // Adjust k2 and k3 proportionally (maintain their ratio to k1)
            val k2Ratio = if (currentK1 != 0f) currentK2 / currentK1 else 0f
            val k3Ratio = if (currentK1 != 0f) currentK3 / currentK1 else 0f
            
            val newK2 = if (newK1 != 0f) (newK1 * k2Ratio).coerceIn(-2.0f, 2.0f) else currentK2.coerceIn(-2.0f, 2.0f)
            val newK3 = if (newK1 != 0f) (newK1 * k3Ratio).coerceIn(-2.0f, 2.0f) else currentK3.coerceIn(-2.0f, 2.0f)
            
            setLensCoefficients(newK1, newK2, newK3)
            android.util.Log.d("VeilRenderer", "Barrel distortion adjusted: k1=$newK1, k2=$newK2, k3=$newK3")
        }

        fun setLensCenters(leftX: Float, leftY: Float, rightX: Float, rightY: Float) {
            lensCenterLeft[0] = leftX
            lensCenterLeft[1] = leftY
            lensCenterRight[0] = rightX
            lensCenterRight[1] = rightY
            saveConfiguration()
            glView.post { glView.requestRender() }
            android.util.Log.d(
                "VeilRenderer",
                "Lens centers set to: left=($leftX,$leftY) right=($rightX,$rightY)"
            )
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
        fun getLensCoefficients(): FloatArray = lensCoefficients.copyOf()
        
        fun forceSaveConfiguration() {
            // Public method to force save current configuration
            saveConfiguration()
        }

        private fun saveConfiguration() {
            // Save to SharedPreferences via MainActivity
            (glSurfaceView.context as? Activity)?.let { activity ->
                val prefs = activity.getSharedPreferences("VeilRendererConfig", Context.MODE_PRIVATE)
                val editor = prefs.edit()
                editor.putInt("leftEyeOffset", leftEyeOffset.toInt())
                editor.putInt("rightEyeOffset", rightEyeOffset.toInt())
                editor.putInt("leftEyeOffsetY", leftEyeOffsetY.toInt())
                editor.putInt("rightEyeOffsetY", rightEyeOffsetY.toInt())
                editor.putFloat("convergenceFactor", convergenceFactor)
                editor.putFloat("sharpness", sharpness)
                editor.putFloat("contrast", contrast)
                editor.putFloat("brightness", brightness)
                editor.putFloat("saturation", saturation)
                editor.putFloat("verticalScale", verticalScale)
                editor.putFloat("lensK1", lensCoefficients[0])
                editor.putFloat("lensK2", lensCoefficients[1])
                editor.putFloat("lensK3", lensCoefficients[2])
                editor.putFloat("lensCenterLeftX", lensCenterLeft[0])
                editor.putFloat("lensCenterLeftY", lensCenterLeft[1])
                editor.putFloat("lensCenterRightX", lensCenterRight[0])
                editor.putFloat("lensCenterRightY", lensCenterRight[1])
                editor.commit()  // Use commit() to ensure settings are persisted immediately
                android.util.Log.d("VeilRenderer", "Saved config: L=${leftEyeOffset.toInt()}, R=${rightEyeOffset.toInt()}, LY=${leftEyeOffsetY.toInt()}, RY=${rightEyeOffsetY.toInt()}, C=$convergenceFactor, Sharp=$sharpness, Cont=$contrast, Bright=$brightness, Sat=$saturation, VertScale=$verticalScale")
            }
        }

        fun flashRed() {
            redFlashUntil = System.currentTimeMillis() + 500  // Flash for 500ms
        }

        override fun onDrawFrame(gl: GL10?) {
            if (isPaused) return

            if ((sceneWidth != screenWidth || sceneHeight != screenHeight) && screenWidth > 0 && screenHeight > 0) {
                setupSceneFramebuffer(screenWidth, screenHeight)
            }

            val shouldFlashRed = System.currentTimeMillis() < redFlashUntil

            if (frameAvailable) {
                try {
                    surfaceTexture?.updateTexImage()
                    surfaceTexture?.getTransformMatrix(texMatrix)
                    frameAvailable = false
                } catch (e: Exception) {
                    android.util.Log.w("VeilRenderer", "Texture update failed", e)
                }
            }

            if (textureId == 0 || sceneFramebuffer == 0 || sceneTexture == 0) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                if (screenWidth > 0 && screenHeight > 0) {
                    GLES30.glViewport(0, 0, screenWidth, screenHeight)
                }
                GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                return
            }

            if (shouldFlashRed) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glViewport(0, 0, screenWidth, screenHeight)
                GLES30.glClearColor(1.0f, 0.0f, 0.0f, 1.0f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                return
            }

            // Render stereo view into off-screen scene texture
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFramebuffer)
            GLES30.glViewport(0, 0, sceneWidth, sceneHeight)
            GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES30.glUseProgram(shaderProgram)
            GLES30.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)

            if (uSharpnessLoc >= 0) GLES30.glUniform1f(uSharpnessLoc, sharpness)
            if (uContrastLoc >= 0) GLES30.glUniform1f(uContrastLoc, contrast)
            if (uBrightnessLoc >= 0) GLES30.glUniform1f(uBrightnessLoc, brightness)
            if (uSaturationLoc >= 0) GLES30.glUniform1f(uSaturationLoc, saturation)
            if (uTexSizeLoc >= 0 && cameraWidth > 0 && cameraHeight > 0) {
                GLES30.glUniform2f(uTexSizeLoc, cameraWidth.toFloat(), cameraHeight.toFloat())
            }

            val halfWidth = 0.5f * (1.0f - convergenceFactor)
            val centerOffset = convergenceFactor * 0.25f

            val leftOffsetNorm = if (screenWidth > 0) leftEyeOffset / (screenWidth / 2.0f) else 0f
            val rightOffsetNorm = if (screenWidth > 0) rightEyeOffset / (screenWidth / 2.0f) else 0f
            val leftOffsetYNorm = if (screenHeight > 0) leftEyeOffsetY / (screenHeight / 2.0f) else 0f
            val rightOffsetYNorm = if (screenHeight > 0) rightEyeOffsetY / (screenHeight / 2.0f) else 0f

            val leftQuad = floatArrayOf(
                -1.0f + centerOffset + leftOffsetNorm, -1.0f + leftOffsetYNorm, 0.0f, 0.0f, 1.0f,
                -1.0f + centerOffset + leftOffsetNorm + halfWidth * 2.0f, -1.0f + leftOffsetYNorm, 0.0f, 0.5f, 1.0f,
                -1.0f + centerOffset + leftOffsetNorm,  1.0f + leftOffsetYNorm, 0.0f, 0.0f, 0.0f,
                -1.0f + centerOffset + leftOffsetNorm + halfWidth * 2.0f,  1.0f + leftOffsetYNorm, 0.0f, 0.5f, 0.0f
            )

            val rightQuad = floatArrayOf(
                 1.0f - centerOffset - halfWidth * 2.0f + rightOffsetNorm, -1.0f + rightOffsetYNorm, 0.0f, 0.5f, 1.0f,
                 1.0f - centerOffset + rightOffsetNorm, -1.0f + rightOffsetYNorm, 0.0f, 1.0f, 1.0f,
                 1.0f - centerOffset - halfWidth * 2.0f + rightOffsetNorm,  1.0f + rightOffsetYNorm, 0.0f, 0.5f, 0.0f,
                 1.0f - centerOffset + rightOffsetNorm,  1.0f + rightOffsetYNorm, 0.0f, 1.0f, 0.0f
            )

            GLES30.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, mvpMatrix, 0)
            drawQuad(leftQuad, aPositionLoc, aTexCoordLoc)
            drawQuad(rightQuad, aPositionLoc, aTexCoordLoc)

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, screenWidth, screenHeight)
            GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

            renderDistortionPass()
        }

        private fun renderDistortionPass() {
            if (sceneFramebuffer == 0 || sceneTexture == 0) return

            if (distortionProgram == 0) {
                // Fallback: blit without distortion
                GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, sceneFramebuffer)
                GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
                GLES30.glBlitFramebuffer(
                    0,
                    0,
                    sceneWidth,
                    sceneHeight,
                    0,
                    0,
                    screenWidth,
                    screenHeight,
                    GLES30.GL_COLOR_BUFFER_BIT,
                    GLES30.GL_NEAREST
                )
                GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
                GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
                return
            }

            GLES30.glUseProgram(distortionProgram)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTexture)
            if (uSceneTextureLoc >= 0) {
                GLES30.glUniform1i(uSceneTextureLoc, 0)
            }
            if (uLensCoeffsLoc >= 0) {
                GLES30.glUniform3f(
                    uLensCoeffsLoc,
                    lensCoefficients[0],
                    lensCoefficients[1],
                    lensCoefficients[2]
                )
            }
            if (uLensCenterLeftLoc >= 0) {
                GLES30.glUniform2f(uLensCenterLeftLoc, lensCenterLeft[0], lensCenterLeft[1])
            }
            if (uLensCenterRightLoc >= 0) {
                GLES30.glUniform2f(uLensCenterRightLoc, lensCenterRight[0], lensCenterRight[1])
            }
            if (uEyeAspectLoc >= 0) {
                val eyeAspect = if (sceneWidth > 0) {
                    sceneHeight.toFloat() / (sceneWidth.toFloat() * 0.5f)
                } else {
                    1.0f
                }
                GLES30.glUniform1f(uEyeAspectLoc, eyeAspect)
            }

            drawQuad(fullScreenQuad, distPositionLoc, distTexCoordLoc)
        }

        private fun drawQuad(quad: FloatArray, positionLoc: Int, texCoordLoc: Int) {
            val vertexBuffer = ByteBuffer.allocateDirect(quad.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(quad)
                    position(0)
                }

            if (positionLoc < 0 || texCoordLoc < 0) return

            GLES30.glEnableVertexAttribArray(positionLoc)
            GLES30.glEnableVertexAttribArray(texCoordLoc)

            vertexBuffer.position(0)
            GLES30.glVertexAttribPointer(positionLoc, 3, GLES30.GL_FLOAT, false, 20, vertexBuffer)

            vertexBuffer.position(3)
            GLES30.glVertexAttribPointer(texCoordLoc, 2, GLES30.GL_FLOAT, false, 20, vertexBuffer)

            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

            GLES30.glDisableVertexAttribArray(positionLoc)
            GLES30.glDisableVertexAttribArray(texCoordLoc)
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
            releaseSceneFramebuffer()
            if (distortionProgram != 0) {
                GLES30.glDeleteProgram(distortionProgram)
                distortionProgram = 0
            }
            if (shaderProgram != 0) {
                GLES30.glDeleteProgram(shaderProgram)
                shaderProgram = 0
            }
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

        private fun loadAssetShader(assetPath: String): String {
            return glSurfaceView.context.assets.open(assetPath).bufferedReader().use { it.readText() }
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

