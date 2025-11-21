package com.veil.renderer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.View
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

class MainActivity : Activity(), GLSurfaceView.Renderer, SensorEventListener {

    // --- Constants & State ---
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var textureId: Int = 0

    // Sensor & ATW State
    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    private lateinit var sensorThread: HandlerThread
    private lateinit var sensorHandler: Handler

    // ATW Ring Buffer
    private val HISTORY_SIZE = 150
    private val rotationHistory = Array(HISTORY_SIZE) {
        FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    }
    private val timestampHistory = LongArray(HISTORY_SIZE)
    private var historyHead = 0
    private var lastSensorTimestamp: Long = 0

    // Matrices
    private val mSTMatrix = FloatArray(16)
    private val mMVPMatrixLeft = FloatArray(16)
    private val mMVPMatrixRight = FloatArray(16)
    private val mDeltaMatrix = FloatArray(16)
    private val mIdentity = FloatArray(16)
    private val mViewMatrix = FloatArray(16)
    private val mTempMatrix = FloatArray(16)

    // GL Handles
    private var mProgram = 0
    private var maPositionHandle = 0
    private var maTextureHandle = 0
    private var muMVPMatrixHandle = 0
    private var muSTMatrixHandle = 0

    // Buffers
    private lateinit var vertexBuffer: FloatBuffer

    // Geometry (Full Screen Quad)
    private val squareCoords = floatArrayOf(
        -1.0f, -1.0f, 0.0f, // Bottom Left
        1.0f, -1.0f, 0.0f,  // Bottom Right
        -1.0f,  1.0f, 0.0f, // Top Left
        1.0f,  1.0f, 0.0f   // Top Right
    )

    private val LATENCY_BIAS_NS = 20_000_000L
    private val INERTIA_KILL_THRESHOLD_NS = 500_000_000L
    private val PERMISSION_REQUEST_CODE = 101
    private val WARP_OVERFILL_SCALE = 1.1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        glSurfaceView = GLSurfaceView(this)
        glSurfaceView.setEGLContextClientVersion(3)
        glSurfaceView.setRenderer(this)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        setContentView(glSurfaceView)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

        Matrix.setIdentityM(mIdentity, 0)
        Matrix.setIdentityM(mViewMatrix, 0)
        initBuffers()

        sensorThread = HandlerThread("SensorThread")
        sensorThread.start()
        sensorHandler = Handler(sensorThread.looper)

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
        rotationVectorSensor?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, sensorHandler)
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (surfaceTexture != null) openCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
        sensorManager.unregisterListener(this)
        closeCamera()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (surfaceTexture != null) openCamera()
        }
    }

    private fun initBuffers() {
        val bb = ByteBuffer.allocateDirect(squareCoords.size * 4)
        bb.order(ByteOrder.nativeOrder())
        vertexBuffer = bb.asFloatBuffer()
        vertexBuffer.put(squareCoords)
        vertexBuffer.position(0)
    }

    // --- Sensor Logic ---

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            synchronized(rotationHistory) {
                lastSensorTimestamp = System.nanoTime()
                historyHead = (historyHead + 1) % HISTORY_SIZE
                timestampHistory[historyHead] = lastSensorTimestamp

                SensorManager.getRotationMatrixFromVector(mTempMatrix, event.values)
                SensorManager.remapCoordinateSystem(mTempMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, rotationHistory[historyHead])
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // --- OpenGL Renderer ---

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)

        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture!!.setOnFrameAvailableListener { glSurfaceView.requestRender() }

        mProgram = createProgram(VERTEX_SHADER_CODE, FRAGMENT_SHADER_CODE)

        if (mProgram != 0) {
            maPositionHandle = GLES30.glGetAttribLocation(mProgram, "aPosition")
            muMVPMatrixHandle = GLES30.glGetUniformLocation(mProgram, "uMVPMatrix")
            muSTMatrixHandle = GLES30.glGetUniformLocation(mProgram, "uSTMatrix")
            maTextureHandle = GLES30.glGetUniformLocation(mProgram, "sTexture")
        }

        runOnUiThread { openCamera() }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {}

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        if (surfaceTexture == null || textureId == 0) return

        try {
            surfaceTexture?.updateTexImage()
            surfaceTexture?.getTransformMatrix(mSTMatrix)
        } catch (e: Exception) { return }

        val w = glSurfaceView.width
        val h = glSurfaceView.height

        // --- 1. Texture Transform (Corrected Rotation/Crop) ---

        Matrix.translateM(mSTMatrix, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(mSTMatrix, 0, 270f, 0f, 0f, 1f)

        // Camera is 16:9 rotated to 9:16
        val cameraAspect = 9.0f / 16.0f
        val perEyeWidth = w / 2.0f
        val viewportAspect = perEyeWidth / h.toFloat()

        val scaleX: Float
        val scaleY: Float

        // Logic: Calculate how much to ZOOM IN (Scale < 1.0) to fill the screen.
        // Note: Because of 270 rotation, Matrix Axis X = Image Height, Matrix Axis Y = Image Width.

        if (viewportAspect > cameraAspect) {
            // Viewport is Wider than Image (limited by Width).
            // We must fit the Width (Matrix Y) exactly -> ScaleY = 1.0.
            // We must crop the Height (Matrix X) -> ScaleX < 1.0.

            scaleY = 1.0f
            // Calculate ratio: How much of height do we keep?
            // (9/16) / (0.88) = ~0.64
            scaleX = cameraAspect / viewportAspect
        } else {
            // Viewport is Taller than Image (limited by Height).
            // We must fit the Height (Matrix X) exactly -> ScaleX = 1.0.
            // We must crop the Width (Matrix Y) -> ScaleY < 1.0.

            scaleX = 1.0f
            scaleY = viewportAspect / cameraAspect
        }

        // SWAPPED SCALES APPLIED HERE due to 270 rotation
        Matrix.scaleM(mSTMatrix, 0, scaleX, scaleY, 1.0f)
        Matrix.translateM(mSTMatrix, 0, -0.5f, -0.5f, 0f)

        // --- 2. ATW Logic ---
        val now = System.nanoTime()
        val frameTime = surfaceTexture?.timestamp ?: 0L

        if (lastSensorTimestamp == 0L || abs(now - lastSensorTimestamp) > INERTIA_KILL_THRESHOLD_NS) {
            Matrix.setIdentityM(mViewMatrix, 0)
        } else {
            val targetTime = frameTime + LATENCY_BIAS_NS
            synchronized(rotationHistory) {
                val matrixThen = findClosestMatrix(targetTime)
                val matrixNow = rotationHistory[historyHead]

                if (isValidMatrix(matrixThen) && isValidMatrix(matrixNow)) {
                    val nowInv = FloatArray(16)
                    if (Matrix.invertM(nowInv, 0, matrixNow, 0)) {
                        Matrix.multiplyMM(mDeltaMatrix, 0, nowInv, 0, matrixThen, 0)
                        System.arraycopy(mDeltaMatrix, 0, mViewMatrix, 0, 16)
                    } else {
                        Matrix.setIdentityM(mViewMatrix, 0)
                    }
                }
            }
        }

        Matrix.scaleM(mViewMatrix, 0, WARP_OVERFILL_SCALE, WARP_OVERFILL_SCALE, 1.0f)

        System.arraycopy(mViewMatrix, 0, mMVPMatrixLeft, 0, 16)
        System.arraycopy(mViewMatrix, 0, mMVPMatrixRight, 0, 16)

        // --- 3. Render ---
        if (mProgram == 0) return

        GLES30.glUseProgram(mProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glUniform1i(maTextureHandle, 0)
        GLES30.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0)

        GLES30.glEnableVertexAttribArray(maPositionHandle)
        GLES30.glVertexAttribPointer(maPositionHandle, 3, GLES30.GL_FLOAT, false, 12, vertexBuffer)

        GLES30.glViewport(0, 0, w / 2, h)
        GLES30.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrixLeft, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glViewport(w / 2, 0, w / 2, h)
        GLES30.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrixRight, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(maPositionHandle)
    }

    private fun findClosestMatrix(targetNs: Long): FloatArray {
        var bestIdx = historyHead
        var minDiff = Long.MAX_VALUE
        for (i in 0 until HISTORY_SIZE) {
            val idx = (historyHead - i + HISTORY_SIZE) % HISTORY_SIZE
            val ts = timestampHistory[idx]
            if (ts == 0L) break
            val diff = abs(ts - targetNs)
            if (diff < minDiff) {
                minDiff = diff
                bestIdx = idx
            } else {
                if (i > 5) break
            }
        }
        val result = rotationHistory[bestIdx]
        return if (isValidMatrix(result)) result else mIdentity
    }

    private fun isValidMatrix(matrix: FloatArray): Boolean {
        var sum = 0f
        for (i in 0 until 16) sum += abs(matrix[i])
        return sum > 0.1f
    }

    private fun openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (surfaceTexture == null || cameraDevice != null) return
        try {
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreview()
                }
                override fun onDisconnected(camera: CameraDevice) { cameraDevice?.close(); cameraDevice = null }
                override fun onError(camera: CameraDevice, error: Int) { cameraDevice?.close(); cameraDevice = null }
            }, null)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun startPreview() {
        try {
            surfaceTexture?.setDefaultBufferSize(1920, 1080)
            val surface = Surface(surfaceTexture)
            cameraDevice?.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    try {
                        val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        builder?.addTarget(surface)
                        builder?.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(60, 60))
                        builder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        session.setRepeatingRequest(builder!!.build(), null, null)
                    } catch (e: Exception) {}
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, null)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun closeCamera() {
        try { captureSession?.close(); cameraDevice?.close() } catch (e: Exception) { }
        captureSession = null; cameraDevice = null
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        if (vertexShader == 0 || fragmentShader == 0) return 0
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)
        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, shaderCode)
        GLES30.glCompileShader(shader)
        val compileStatus = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        private const val VERTEX_SHADER_CODE = """#version 300 es
uniform mat4 uMVPMatrix;
uniform mat4 uSTMatrix;
in vec4 aPosition;
out vec2 vTexCoord;
void main() {
    gl_Position = uMVPMatrix * aPosition;
    vec4 texPos = vec4((aPosition.x + 1.0) * 0.5, (aPosition.y + 1.0) * 0.5, 0.0, 1.0);
    vTexCoord = (uSTMatrix * texPos).xy;
}
"""

        private const val FRAGMENT_SHADER_CODE = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
in vec2 vTexCoord;
uniform samplerExternalOES sTexture;
out vec4 FragColor;
void main() {
    if (vTexCoord.x < 0.0 || vTexCoord.x > 1.0 || vTexCoord.y < 0.0 || vTexCoord.y > 1.0) {
        FragColor = vec4(0.0, 0.0, 0.0, 1.0);
    } else {
        FragColor = texture(sTexture, vTexCoord);
    }
}
"""
    }
}