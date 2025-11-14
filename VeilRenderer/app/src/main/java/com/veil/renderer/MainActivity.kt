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

        // Request camera permission for UVC devices
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

        glSurfaceView = GLSurfaceView(this)
        glSurfaceView.setEGLContextClientVersion(3)
        renderer = StereoRenderer()
        glSurfaceView.setRenderer(renderer)
        // Use continuous rendering to ensure frames are displayed
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        
        setContentView(glSurfaceView)

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
            
            // Create external texture
            val textures = IntArray(1)
            GLES30.glGenTextures(1, textures, 0)
            textureId = textures[0]
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
            GLES30.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            if (isPaused) return
            
            // Update texture from SurfaceTexture if new frame is available
            // Always try to update - UVCCamera writes directly to SurfaceTexture
            try {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(texMatrix)
                frameAvailable = false
            } catch (e: Exception) {
                // Texture not ready yet or no new frame
            }
            
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            
            // Bind the external texture
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            
            GLES30.glUseProgram(shaderProgram)
            
            // Set texture matrix
            GLES30.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
            GLES30.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, mvpMatrix, 0)
            
            // Draw full screen quad
            drawQuad(fullScreenQuad)
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
    }
}

