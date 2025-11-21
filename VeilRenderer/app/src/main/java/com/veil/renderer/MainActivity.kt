package com.veil.renderer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Surface
import android.view.View
import android.view.WindowManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
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
    // Distortion Uniforms
    private var muLensCenterHandle = 0
    private var muDistortionKHandle = 0
    private var muCalibrationScaleHandle = 0

    // Feedback (Green Circle)
    private var mFeedbackProgram = 0
    private var mFeedbackPositionHandle = 0
    private var mFeedbackColorHandle = 0
    private var mFeedbackMVPHandle = 0
    private var feedbackTimer = 0
    private val FEEDBACK_DURATION = 60 // Frames (approx 1 sec)

    // Buffers
    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var feedbackVertexBuffer: FloatBuffer

    // Geometry (Full Screen Quad)
    private val squareCoords = floatArrayOf(
        -1.0f, -1.0f, 0.0f,
        1.0f, -1.0f, 0.0f,
        -1.0f,  1.0f, 0.0f,
        1.0f,  1.0f, 0.0f
    )

    // Feedback Geometry (Small center quad)
    private val feedbackCoords = floatArrayOf(
        -0.05f, -0.05f, 0.0f,
        0.05f, -0.05f, 0.0f,
        -0.05f,  0.05f, 0.0f,
        0.05f,  0.05f, 0.0f
    )

    private val PERMISSION_REQUEST_CODE = 101
    private val WARP_OVERFILL_SCALE = 1.1f
    private val INERTIA_KILL_THRESHOLD_NS = 500_000_000L

    // --- Calibration State ---
    data class CalibrationData(
        var isCalibrating: Boolean = false,
        var leftEyeX: Float = 0f,
        var leftEyeY: Float = 0f,
        var rightEyeX: Float = 0f,
        var rightEyeY: Float = 0f,
        var zoomPercent: Float = 0f, // 0 = 100% scale
        var barrelK: Float = 0f,     // Distortion coefficient
        var latencyBiasNs: Long = 20_000_000L
    )
    private var cal = CalibrationData()
    private lateinit var prefs: SharedPreferences

    // --- Speech Recognition ---
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recognizerIntent: Intent
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        // Preferences
        prefs = getSharedPreferences("VRCalibration", Context.MODE_PRIVATE)
        loadCalibration()

        // GL Setup
        glSurfaceView = GLSurfaceView(this)
        glSurfaceView.setEGLContextClientVersion(3)
        glSurfaceView.setRenderer(this)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        setContentView(glSurfaceView)

        // Hardware Managers
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

        Matrix.setIdentityM(mIdentity, 0)
        Matrix.setIdentityM(mViewMatrix, 0)
        initBuffers()

        sensorThread = HandlerThread("SensorThread")
        sensorThread.start()
        sensorHandler = Handler(sensorThread.looper)

        // Permissions & Speech Init
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            initSpeechRecognition()
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

        // Restart listening if we were listening
        if (::speechRecognizer.isInitialized) {
            startListening()
        }
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
        sensorManager.unregisterListener(this)
        closeCamera()
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.stopListening()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            // Re-check individually
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                if (surfaceTexture != null) openCamera()
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                initSpeechRecognition()
            }
        }
    }

    // --- Speech Recognition Logic ---

    private fun initSpeechRecognition() {
        mainHandler.post {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString())
            }
            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    // Silently restart on error
                    restartListening()
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        processCommand(matches[0].uppercase())
                    }
                    restartListening()
                }
                override fun onPartialResults(partialResults: Bundle?) {
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening()
        }
    }

    private fun startListening() {
        try {
            speechRecognizer.startListening(recognizerIntent)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun restartListening() {
        mainHandler.postDelayed({ startListening() }, 100)
    }

    private fun processCommand(cmd: String) {
        var commandRecognized = false

        if (cmd.contains("CALIBRATE")) {
            cal.isCalibrating = true
            commandRecognized = true
        } else if (cmd.contains("SAVE")) {
            cal.isCalibrating = false
            saveCalibration()
            commandRecognized = true
        }

        if (cal.isCalibrating) {
            // Parsing logic
            val words = cmd.split(" ")
            val valIdx = words.size - 1
            val valueStr = words[valIdx].filter { it.isDigit() }
            val value = valueStr.toFloatOrNull() ?: 0f

            // FIXED: Multiplier was too small.
            // 100 pixels on ~1200px eye width is approx 0.15 NDC.
            // value / 500f makes "100" -> 0.2 (visible shift).
            val multiplier = value / 500f

            if (cmd.contains("RESET")) {
                if (cmd.contains("WARP")) cal.latencyBiasNs = 20_000_000L
                else if (cmd.contains("ZOOM")) cal.zoomPercent = 0f
                else if (cmd.contains("BARREL")) cal.barrelK = 0f
                else if (cmd.contains("LEFT")) { cal.leftEyeX = 0f; cal.leftEyeY = 0f }
                else if (cmd.contains("RIGHT")) { cal.rightEyeX = 0f; cal.rightEyeY = 0f }
                commandRecognized = true
            } else {
                if (cmd.contains("WARP")) {
                    // 10% change = 2ms
                    val change = if (cmd.contains("FASTER")) -2_000_000L else 2_000_000L
                    cal.latencyBiasNs += change
                    commandRecognized = true
                } else if (cmd.contains("ZOOM")) {
                    val change = if (cmd.contains("OUT")) -value else value
                    cal.zoomPercent += change
                    commandRecognized = true
                } else if (cmd.contains("BARREL")) {
                    // 0.01 step per 10 value
                    val change = (if (cmd.contains("DOWN")) -value else value) * 0.001f
                    cal.barrelK += change
                    commandRecognized = true
                } else if (cmd.contains("LEFT") || cmd.contains("RIGHT")) {
                    val isLeft = cmd.contains("LEFT")
                    // In/Out logic (IPD)
                    var dx = 0f
                    var dy = 0f

                    if (cmd.contains("IN")) dx = if (isLeft) multiplier else -multiplier
                    else if (cmd.contains("OUT")) dx = if (isLeft) -multiplier else multiplier

                    if (cmd.contains("UP")) dy = multiplier
                    else if (cmd.contains("DOWN")) dy = -multiplier

                    if (isLeft) {
                        cal.leftEyeX += dx
                        cal.leftEyeY += dy
                    } else {
                        cal.rightEyeX += dx
                        cal.rightEyeY += dy
                    }
                    commandRecognized = true
                }
            }
        }

        if (commandRecognized) {
            feedbackTimer = FEEDBACK_DURATION
        }
    }

    private fun saveCalibration() {
        prefs.edit().apply {
            putFloat("lx", cal.leftEyeX)
            putFloat("ly", cal.leftEyeY)
            putFloat("rx", cal.rightEyeX)
            putFloat("ry", cal.rightEyeY)
            putFloat("zoom", cal.zoomPercent)
            putFloat("barrel", cal.barrelK)
            putLong("bias", cal.latencyBiasNs)
            apply()
        }
    }

    private fun loadCalibration() {
        cal.leftEyeX = prefs.getFloat("lx", 0f)
        cal.leftEyeY = prefs.getFloat("ly", 0f)
        cal.rightEyeX = prefs.getFloat("rx", 0f)
        cal.rightEyeY = prefs.getFloat("ry", 0f)
        cal.zoomPercent = prefs.getFloat("zoom", 0f)
        cal.barrelK = prefs.getFloat("barrel", 0f)
        cal.latencyBiasNs = prefs.getLong("bias", 20_000_000L)
    }

    // --- GL Renderer ---

    private fun initBuffers() {
        val bb = ByteBuffer.allocateDirect(squareCoords.size * 4)
        bb.order(ByteOrder.nativeOrder())
        vertexBuffer = bb.asFloatBuffer()
        vertexBuffer.put(squareCoords)
        vertexBuffer.position(0)

        val fbb = ByteBuffer.allocateDirect(feedbackCoords.size * 4)
        fbb.order(ByteOrder.nativeOrder())
        feedbackVertexBuffer = fbb.asFloatBuffer()
        feedbackVertexBuffer.put(feedbackCoords)
        feedbackVertexBuffer.position(0)
    }

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

        mProgram = createProgram(VERTEX_SHADER_CODE, FRAGMENT_DISTORTION_CODE)
        mFeedbackProgram = createProgram(VERTEX_FEEDBACK_CODE, FRAGMENT_FEEDBACK_CODE)

        if (mProgram != 0) {
            maPositionHandle = GLES30.glGetAttribLocation(mProgram, "aPosition")
            muMVPMatrixHandle = GLES30.glGetUniformLocation(mProgram, "uMVPMatrix")
            muSTMatrixHandle = GLES30.glGetUniformLocation(mProgram, "uSTMatrix")
            maTextureHandle = GLES30.glGetUniformLocation(mProgram, "sTexture")
            muLensCenterHandle = GLES30.glGetUniformLocation(mProgram, "uLensCenter")
            muDistortionKHandle = GLES30.glGetUniformLocation(mProgram, "uDistortionK")
            muCalibrationScaleHandle = GLES30.glGetUniformLocation(mProgram, "uCalibrationScale")
        }

        if (mFeedbackProgram != 0) {
            mFeedbackPositionHandle = GLES30.glGetAttribLocation(mFeedbackProgram, "aPosition")
            mFeedbackColorHandle = GLES30.glGetUniformLocation(mFeedbackProgram, "uColor")
            mFeedbackMVPHandle = GLES30.glGetUniformLocation(mFeedbackProgram, "uMVPMatrix")
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

        // --- 1. Texture Transform ---
        Matrix.translateM(mSTMatrix, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(mSTMatrix, 0, 270f, 0f, 0f, 1f)

        val cameraAspect = 9.0f / 16.0f
        val perEyeWidth = w / 2.0f
        val viewportAspect = perEyeWidth / h.toFloat()
        var scaleX = 1.0f
        var scaleY = 1.0f

        if (viewportAspect > cameraAspect) {
            scaleY = 1.0f
            scaleX = cameraAspect / viewportAspect
        } else {
            scaleX = 1.0f
            scaleY = viewportAspect / cameraAspect
        }

        Matrix.scaleM(mSTMatrix, 0, scaleX, scaleY, 1.0f)
        Matrix.translateM(mSTMatrix, 0, -0.5f, -0.5f, 0f)

        // --- 2. ATW ---
        val now = System.nanoTime()
        val frameTime = surfaceTexture?.timestamp ?: 0L

        if (lastSensorTimestamp == 0L || abs(now - lastSensorTimestamp) > INERTIA_KILL_THRESHOLD_NS) {
            Matrix.setIdentityM(mViewMatrix, 0)
        } else {
            // Use calibrated latency bias
            val targetTime = frameTime + cal.latencyBiasNs
            synchronized(rotationHistory) {
                val matrixThen = findClosestMatrix(targetTime)
                val matrixNow = rotationHistory[historyHead]
                val nowInv = FloatArray(16)
                if (Matrix.invertM(nowInv, 0, matrixNow, 0)) {
                    Matrix.multiplyMM(mDeltaMatrix, 0, nowInv, 0, matrixThen, 0)
                    System.arraycopy(mDeltaMatrix, 0, mViewMatrix, 0, 16)
                } else {
                    Matrix.setIdentityM(mViewMatrix, 0)
                }
            }
        }

        Matrix.scaleM(mViewMatrix, 0, WARP_OVERFILL_SCALE, WARP_OVERFILL_SCALE, 1.0f)

        // --- 3. Render Eyes with Calibration ---

        GLES30.glUseProgram(mProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glUniform1i(maTextureHandle, 0)
        GLES30.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0)

        // Calibration Scale (Global Zoom)
        // cal.zoomPercent: 10 = 110% size.
        val calScale = 1.0f + (cal.zoomPercent / 100.0f)
        GLES30.glUniform1f(muCalibrationScaleHandle, calScale)
        // Distortion K
        GLES30.glUniform1f(muDistortionKHandle, cal.barrelK)

        GLES30.glEnableVertexAttribArray(maPositionHandle)
        GLES30.glVertexAttribPointer(maPositionHandle, 3, GLES30.GL_FLOAT, false, 12, vertexBuffer)

        // LEFT EYE
        System.arraycopy(mViewMatrix, 0, mMVPMatrixLeft, 0, 16)
        Matrix.translateM(mMVPMatrixLeft, 0, cal.leftEyeX, cal.leftEyeY, 0f)

        GLES30.glViewport(0, 0, w / 2, h)
        GLES30.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrixLeft, 0)
        // Pass lens center relative to this viewport (usually 0.5, 0.5)
        GLES30.glUniform2f(muLensCenterHandle, 0.5f, 0.5f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        // RIGHT EYE
        System.arraycopy(mViewMatrix, 0, mMVPMatrixRight, 0, 16)
        Matrix.translateM(mMVPMatrixRight, 0, cal.rightEyeX, cal.rightEyeY, 0f)

        GLES30.glViewport(w / 2, 0, w / 2, h)
        GLES30.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrixRight, 0)
        GLES30.glUniform2f(muLensCenterHandle, 0.5f, 0.5f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(maPositionHandle)

        // --- 4. Render Visual Feedback ---
        if (feedbackTimer > 0) {
            feedbackTimer--
            drawFeedback(w, h)
        }
    }

    private fun drawFeedback(w: Int, h: Int) {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(mFeedbackProgram)

        // FIXED: Draw specifically in center of Left Eye
        GLES30.glViewport(0, 0, w / 2, h)
        GLES30.glUniformMatrix4fv(mFeedbackMVPHandle, 1, false, mIdentity, 0)

        // Fade out alpha
        val alpha = feedbackTimer.toFloat() / FEEDBACK_DURATION.toFloat()
        GLES30.glUniform4f(mFeedbackColorHandle, 0.0f, 1.0f, 0.0f, alpha)

        GLES30.glEnableVertexAttribArray(mFeedbackPositionHandle)
        GLES30.glVertexAttribPointer(mFeedbackPositionHandle, 3, GLES30.GL_FLOAT, false, 12, feedbackVertexBuffer)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(mFeedbackPositionHandle)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    // --- Helper Methods ---

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
            } else if (i > 5) break
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
            // CHANGE: Back to 1080p for maximum sharpness
            surfaceTexture?.setDefaultBufferSize(1280, 720)

            val surface = Surface(surfaceTexture)
            cameraDevice?.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    try {
                        val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        builder?.addTarget(surface)
                        // Still targeting 60fps for smoothness
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
        // Barrel Distortion Shader
        // K > 0: Barrel (Corrects Pincushion)
        // K < 0: Pincushion (Corrects Barrel)
        private const val FRAGMENT_DISTORTION_CODE = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
in vec2 vTexCoord;
uniform samplerExternalOES sTexture;
uniform vec2 uLensCenter;
uniform float uDistortionK;
uniform float uCalibrationScale;
out vec4 FragColor;

void main() {
    // 1. Center coordinates
    vec2 rVec = vTexCoord - uLensCenter;
    
    // 2. Apply Calibration Zoom (Scale inverse)
    // If scale = 1.1, we divide vector by 1.1 to sample closer to center (zoom in)
    rVec = rVec / uCalibrationScale;
    
    // 3. Apply Distortion
    float r2 = dot(rVec, rVec);
    // Simple K1 + K2 approximation (using just K1 for control simplicity)
    float f = 1.0 + (uDistortionK * r2);
    
    vec2 distCoord = uLensCenter + (rVec * f);

    // 4. Check bounds
    if (distCoord.x < 0.0 || distCoord.x > 1.0 || distCoord.y < 0.0 || distCoord.y > 1.0) {
        FragColor = vec4(0.0, 0.0, 0.0, 1.0);
    } else {
        FragColor = texture(sTexture, distCoord);
    }
}
"""
        // Feedback Visuals
        private const val VERTEX_FEEDBACK_CODE = """#version 300 es
uniform mat4 uMVPMatrix;
in vec4 aPosition;
void main() { gl_Position = uMVPMatrix * aPosition; }
"""
        private const val FRAGMENT_FEEDBACK_CODE = """#version 300 es
precision mediump float;
uniform vec4 uColor;
out vec4 FragColor;
void main() { FragColor = uColor; }
"""
    }
}