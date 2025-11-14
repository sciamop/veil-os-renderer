package com.veil.renderer

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.widget.SeekBar
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.Color
import android.view.Gravity
import android.media.MediaCodec
import android.media.MediaFormat
// UVCAndroid includes serenegiant classes - use them directly
import com.serenegiant.usb.UVCCamera
import com.serenegiant.usb.UVCParam
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.USBMonitor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : Activity() {
    companion object {
        private const val CAMERA_WIDTH = 3840
        private const val CAMERA_HEIGHT = 1080
        private const val CAMERA_FPS = 60
        private const val REQUEST_CAMERA_PERMISSION = 1001
    }

    private lateinit var usbManager: UsbManager
    private var usbMonitor: USBMonitor? = null
    private var uvcCamera: UVCCamera? = null
    private lateinit var glSurfaceView: GLSurfaceView
    private var renderer: StereoRenderer? = null
    private var cameraSurface: Surface? = null
    private var interpDelaySeekBar: SeekBar? = null
    private var interpDelayLabel: TextView? = null

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
        // Use continuous rendering at 120fps - GLSurfaceView will render as fast as display allows
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
        
        // Create interpolation delay slider
        val sliderContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                setPadding(32, 32, 32, 100)
            }
            setBackgroundColor(Color.argb(180, 0, 0, 0))  // Semi-transparent black
        }
        
        interpDelayLabel = TextView(this).apply {
            text = "Interp Delay: 10.0ms"
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
        }
        
        interpDelaySeekBar = SeekBar(this).apply {
            max = 100  // 0-100 = 0-10ms delay
            progress = 100  // Start at 10.0ms (optimal for flicker-free interpolation)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                topMargin = 50
            }
            
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val delay = progress / 10.0f
                    renderer?.setInterpolationDelay(delay)
                    interpDelayLabel?.text = "Interp Delay: ${String.format("%.1f", delay)}ms"
                }
                
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        
        sliderContainer.addView(interpDelayLabel)
        sliderContainer.addView(interpDelaySeekBar)
        container.addView(sliderContainer)
        
        setContentView(container)

        // USBMonitor will handle device attachment automatically via onAttach callback
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
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
        renderer?.pause()
        uvcCamera?.stopPreview()
        uvcCamera?.destroy()
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer?.release()
        uvcCamera?.destroy()
        uvcCamera = null
        usbMonitor?.unregister()
        usbMonitor?.destroy()
        usbMonitor = null
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
                        
                        // Find MJPEG format size (type 7) for 3840x1080
                        var selectedSize: com.serenegiant.usb.Size? = null
                        if (supportedSizes != null) {
                            // Try to find 3840x1080 MJPEG first
                            selectedSize = supportedSizes.firstOrNull { 
                                it.width == 3840 && it.height == 1080 && it.type == 7 
                            }
                            if (selectedSize == null) {
                                // Try any 3840x1080
                                selectedSize = supportedSizes.firstOrNull { 
                                    it.width == 3840 && it.height == 1080 
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
        private var textureId = 0  // Current frame texture
        private var prevTextureId = 0  // Previous frame texture for interpolation
        private var surfaceTexture: SurfaceTexture? = null
        private var decoderSurface: Surface? = null
        private var mediaCodec: MediaCodec? = null
        private var shaderProgram = 0
        private var interpolateShaderProgram = 0  // Shader for frame interpolation
        private var uMVPMatrixLoc = 0
        private var uTexMatrixLoc = 0
        private var aPositionLoc = 0
        private var aTexCoordLoc = 0
        
        // Interpolation shader uniforms
        private var uPrevTexLoc = 0
        private var uCurrTexLoc = 0
        private var uInterpFactorLoc = 0
        private var uInterpMVPMatrixLoc = 0
        
        private val decoderThread = HandlerThread("DecoderThread").apply { start() }
        private val decoderHandler = Handler(decoderThread.looper)
        
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
        private var frameCount = 0L  // Track frame count for interpolation timing
        
        // Frame interpolation state
        private var hasPreviousFrame = false
        private var lastFrameTime = 0L
        private var lastRealFrameTime = 0L
        private var interpolationFactor = 0.5f  // 0.5 = halfway between frames
        private var frameCopied = false
        private var justRenderedRealFrame = false
        private var minInterpDelay = 10.0f  // Minimum ms before interpolating after real frame (10ms eliminates flicker)
        
        // Camera dimensions for aspect ratio calculation
        private var cameraWidth = 3840
        private var cameraHeight = 1080
        private var screenWidth = 0
        private var screenHeight = 0
        
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
            
            // Create external textures for current and previous frames
            val textures = IntArray(2)
            GLES30.glGenTextures(2, textures, 0)
            textureId = textures[0]
            prevTextureId = textures[1]
            
            // Setup current frame texture
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
            
            // Setup previous frame texture (regular 2D texture for copying)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, prevTextureId)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            
            // Load interpolation shader
            val interpolateVertexShader = loadShader(GLES30.GL_VERTEX_SHADER, getVertexShaderSource())
            val interpolateFragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, getInterpolateFragmentShaderSource())
            
            interpolateShaderProgram = GLES30.glCreateProgram()
            GLES30.glAttachShader(interpolateShaderProgram, interpolateVertexShader)
            GLES30.glAttachShader(interpolateShaderProgram, interpolateFragmentShader)
            GLES30.glLinkProgram(interpolateShaderProgram)
            
            // Get interpolation shader uniform locations
            uPrevTexLoc = GLES30.glGetUniformLocation(interpolateShaderProgram, "uPrevTex")
            uCurrTexLoc = GLES30.glGetUniformLocation(interpolateShaderProgram, "uCurrTex")
            uInterpFactorLoc = GLES30.glGetUniformLocation(interpolateShaderProgram, "uInterpFactor")
            uInterpMVPMatrixLoc = GLES30.glGetUniformLocation(interpolateShaderProgram, "uMVPMatrix")
            
            // Create SurfaceTexture and Surface - UVCCamera will write decoded frames here
            surfaceTexture = SurfaceTexture(textureId)
            surfaceTexture?.setOnFrameAvailableListener {
                frameAvailable = true
                lastRealFrameTime = System.nanoTime()
                frameCount++
                frameCopied = false
                // Request render when new frame is available
                glSurfaceView.requestRender()
                android.util.Log.d("VeilRenderer", "New frame available from SurfaceTexture")
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
            
            if (cameraAspect > screenAspect) {
                // Camera is wider than screen - letterbox (black bars top/bottom)
                // Scale to fit screen width, leaving bars on top/bottom
                val scale = screenAspect / cameraAspect
                android.opengl.Matrix.scaleM(mvpMatrix, 0, 1.0f, scale, 1.0f)
            } else {
                // Camera is taller than screen - pillarbox (black bars left/right)
                // Scale to fit screen height, leaving bars on left/right
                val scale = cameraAspect / screenAspect
                android.opengl.Matrix.scaleM(mvpMatrix, 0, scale, 1.0f, 1.0f)
            }
        }
        
        fun setCameraSize(width: Int, height: Int) {
            cameraWidth = width
            cameraHeight = height
            updateMVPMatrix()
        }
        
        fun setInterpolationDelay(delayMs: Float) {
            minInterpDelay = delayMs
        }

        override fun onDrawFrame(gl: GL10?) {
            if (isPaused) return
            
            val currentTime = System.nanoTime()
            val timeSinceLastRealFrame = if (lastRealFrameTime > 0) (currentTime - lastRealFrameTime) / 1_000_000.0f else 0f
            val frameInterval = 1000.0f / 60.0f  // 60fps = ~16.67ms per frame
            
            // Check if we have a new frame available
            val hasNewFrame = frameAvailable
            
            // Always render real frame if available, otherwise interpolate
            if (hasNewFrame) {
                // Copy CURRENT frame to previous BEFORE updating (if we have a previous frame)
                if (hasPreviousFrame && screenWidth > 0 && screenHeight > 0 && !frameCopied) {
                    copyFrameToPrevious()
                    frameCopied = true
                }
                
                // Update to the new frame
                try {
                    surfaceTexture?.updateTexImage()
                    surfaceTexture?.getTransformMatrix(texMatrix)
                    hasPreviousFrame = true
                    frameAvailable = false
                    frameCopied = false  // Reset for next frame
                    justRenderedRealFrame = true
                } catch (e: Exception) {
                    // Texture not ready yet
                }
            }
            
            // Determine if we should render interpolated frame
            // Interpolate if we have both frames and we're between real frames
            // The key is to always render SOMETHING to maintain 120fps
            val shouldRenderInterpolated = hasPreviousFrame && 
                                          !hasNewFrame && 
                                          !justRenderedRealFrame &&
                                          timeSinceLastRealFrame > minInterpDelay && 
                                          timeSinceLastRealFrame < frameInterval
            
            if (shouldRenderInterpolated) {
                // Render interpolated frame between previous and current
                // Interpolation factor: 0.0 = previous frame, 1.0 = current frame
                // Map the time window to 0-1 for smooth interpolation
                val interpWindow = frameInterval - minInterpDelay
                val interpFactor = if (interpWindow > 0) {
                    ((timeSinceLastRealFrame - minInterpDelay) / interpWindow).coerceIn(0f, 1f)
                } else {
                    0.5f  // Default to halfway if window is invalid
                }
                
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                GLES30.glUseProgram(interpolateShaderProgram)
                
                // Bind previous frame (2D texture - the one we copied)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, prevTextureId)
                GLES30.glUniform1i(uPrevTexLoc, 0)
                
                // Bind current frame (external texture - the latest real frame)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
                GLES30.glUniform1i(uCurrTexLoc, 1)
                
                // Set interpolation factor and MVP matrix
                GLES30.glUniform1f(uInterpFactorLoc, interpFactor)
                GLES30.glUniformMatrix4fv(uInterpMVPMatrixLoc, 1, false, mvpMatrix, 0)
                
                drawQuad(fullScreenQuad)
            } else {
                // Render real frame
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                
                if (hasPreviousFrame) {
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                    GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
                    GLES30.glUseProgram(shaderProgram)
                    GLES30.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
                    GLES30.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, mvpMatrix, 0)
                    drawQuad(fullScreenQuad)
                }
            }
            
            // Clear the justRenderedRealFrame flag after a short delay
            if (justRenderedRealFrame && timeSinceLastRealFrame > minInterpDelay) {
                justRenderedRealFrame = false
            }
        }
        
        private fun copyFrameToPrevious() {
            // Save current state
            val prevViewport = IntArray(4)
            GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, prevViewport, 0)
            val prevFBO = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFBO, 0)
            
            // Create FBO to copy current external texture to previous 2D texture
            val fbo = IntArray(1)
            GLES30.glGenFramebuffers(1, fbo, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
            
            // Allocate texture storage if needed (only first time)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, prevTextureId)
            // Check if texture is already allocated by trying to get its size
            // For now, just allocate it - it's cheap if already allocated
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
                cameraWidth, cameraHeight, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
            )
            
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, prevTextureId, 0
            )
            
            // Check FBO completeness
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            if (status == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                // Render current frame to FBO
                GLES30.glViewport(0, 0, cameraWidth, cameraHeight)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
                GLES30.glUseProgram(shaderProgram)
                GLES30.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
                val identityMatrix = FloatArray(16)
                android.opengl.Matrix.setIdentityM(identityMatrix, 0)
                GLES30.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, identityMatrix, 0)
                drawQuad(fullScreenQuad)
            }
            
            // Restore previous state
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFBO[0])
            GLES30.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
            GLES30.glDeleteFramebuffers(1, fbo, 0)
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
                varying vec2 vTextureCoord;
                
                void main() {
                    gl_FragColor = texture2D(sTexture, vTextureCoord);
                }
            """.trimIndent()
        }
        
        private fun getInterpolateFragmentShaderSource(): String {
            return """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                uniform sampler2D uPrevTex;
                uniform samplerExternalOES uCurrTex;
                uniform float uInterpFactor;
                varying vec2 vTextureCoord;
                
                void main() {
                    // Simple blend-based interpolation (can be upgraded to ML-based later)
                    vec4 prevColor = texture2D(uPrevTex, vTextureCoord);
                    vec4 currColor = texture2D(uCurrTex, vTextureCoord);
                    
                    // Blend between previous and current frame
                    gl_FragColor = mix(prevColor, currColor, uInterpFactor);
                }
            """.trimIndent()
        }
    }
}

