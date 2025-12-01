package com.veil.renderer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.media.ImageReader
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity(), GLSurfaceView.Renderer, SensorEventListener {

    // --- Constants & State ---
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var textureId: Int = 0

    // AI / Face Detection State
    private var imageReader: ImageReader? = null
    private lateinit var faceDetector: FaceDetector
    @Volatile private var isProcessingFace = false
    private var faceRectGL = floatArrayOf(0f, 0f, 0f, 0f)
    private var faceDetected = false
    private var faceLostTimer = 0

    // Snapshot State
    private var mSnapshotTextureId = 0
    private var mSnapshotProgram = 0
    private var mSnapshotPositionHandle = 0
    private var mSnapshotTexCoordHandle = 0
    private var mSnapshotMVPHandle = 0
    private var mSnapshotAlphaHandle = 0
    private var mSnapshotSamplerHandle = 0

    private var snapshotTimer = 0
    private val SNAPSHOT_HOLD_FRAMES = 30 // 0.5s hold
    private val SNAPSHOT_FADE_FRAMES = 20 // 0.3s fade
    private var snapshotMVP = FloatArray(16)
    private var requestSnapshot = false

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

    // Temp matrices for Face Box rendering
    private val mFaceMVP = FloatArray(16)
    private val mFaceMVPLeft = FloatArray(16)
    private val mFaceMVPRight = FloatArray(16)

    // GL Handles
    private var mProgram = 0
    private var maPositionHandle = 0
    private var maTextureHandle = 0
    private var muMVPMatrixHandle = 0
    private var muSTMatrixHandle = 0
    private var muLensCenterHandle = 0
    private var muDistortionKHandle = 0
    private var muCalibrationScaleHandle = 0

    // Feedback (Green Circle)
    private var mFeedbackProgram = 0
    private var mFeedbackPositionHandle = 0
    private var mFeedbackColorHandle = 0
    private var mFeedbackMVPHandle = 0
    private var feedbackTimer = 0
    private val FEEDBACK_DURATION = 60

    // Buffers
    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var feedbackVertexBuffer: FloatBuffer
    private lateinit var faceBoxVertexBuffer: FloatBuffer

    // Geometry
    private val squareCoords = floatArrayOf(
        -1.0f, -1.0f, 0.0f,
        1.0f, -1.0f, 0.0f,
        -1.0f,  1.0f, 0.0f,
        1.0f,  1.0f, 0.0f
    )
    private val feedbackCoords = floatArrayOf(
        -0.05f, -0.05f, 0.0f,
        0.05f, -0.05f, 0.0f,
        -0.05f,  0.05f, 0.0f,
        0.05f,  0.05f, 0.0f
    )
    private val faceBoxCoords = floatArrayOf(
        0.0f, 0.0f, 0.0f,  // BL
        1.0f, 0.0f, 0.0f,  // BR
        0.0f, 1.0f, 0.0f,  // TL
        1.0f, 1.0f, 0.0f   // TR
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
        var zoomPercent: Float = 0f,
        var barrelK: Float = 0f,
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

        prefs = getSharedPreferences("VRCalibration", Context.MODE_PRIVATE)
        loadCalibration()

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .build()
        faceDetector = FaceDetection.getClient(options)

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

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.CAMERA)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.RECORD_AUDIO)
        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        else initSpeechRecognition()
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
        rotationVectorSensor?.also { sensor -> sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, sensorHandler) }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && surfaceTexture != null) openCamera()
        if (::speechRecognizer.isInitialized) startListening()
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
        sensorManager.unregisterListener(this)
        closeCamera()
        if (::speechRecognizer.isInitialized) speechRecognizer.stopListening()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && surfaceTexture != null) openCamera()
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) initSpeechRecognition()
        }
    }

    private fun initSpeechRecognition() {
        mainHandler.post {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString())
            }
            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) { restartListening() }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) processCommand(matches[0].uppercase())
                    restartListening()
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening()
        }
    }

    private fun startListening() { try { speechRecognizer.startListening(recognizerIntent) } catch (e: Exception) {} }
    private fun restartListening() { mainHandler.postDelayed({ startListening() }, 100) }

    private fun processCommand(cmd: String) {
        var commandRecognized = false
        if (cmd.contains("CALIBRATE")) { cal.isCalibrating = true; commandRecognized = true }
        else if (cmd.contains("SAVE")) { cal.isCalibrating = false; saveCalibration(); commandRecognized = true }

        if (cal.isCalibrating) {
            val words = cmd.split(" ")
            val value = words.lastOrNull()?.filter { it.isDigit() }?.toFloatOrNull() ?: 0f
            val multiplier = value / 500f

            if (cmd.contains("RESET")) {
                if (cmd.contains("TIMEWARP")) cal.latencyBiasNs = 20_000_000L
                else if (cmd.contains("ZOOM")) cal.zoomPercent = 0f
                else if (cmd.contains("BARREL")) cal.barrelK = 0f
                else if (cmd.contains("LEFT")) { cal.leftEyeX = 0f; cal.leftEyeY = 0f }
                else if (cmd.contains("RIGHT")) { cal.rightEyeX = 0f; cal.rightEyeY = 0f }
                commandRecognized = true
            } else {
                if (cmd.contains("TIMEWARP")) {
                    cal.latencyBiasNs += if (cmd.contains("FASTER")) -2_000_000L else 2_000_000L
                    commandRecognized = true
                } else if (cmd.contains("ZOOM")) {
                    cal.zoomPercent += if (cmd.contains("OUT")) -value else value
                    commandRecognized = true
                } else if (cmd.contains("BARREL")) {
                    cal.barrelK += (if (cmd.contains("DOWN")) -value else value) * 0.001f
                    commandRecognized = true
                } else if (cmd.contains("LEFT") || cmd.contains("RIGHT")) {
                    val isLeft = cmd.contains("LEFT")
                    var dx = 0f; var dy = 0f
                    if (cmd.contains("IN")) dx = if (isLeft) multiplier else -multiplier
                    else if (cmd.contains("OUT")) dx = if (isLeft) -multiplier else multiplier
                    if (cmd.contains("UP")) dy = multiplier else if (cmd.contains("DOWN")) dy = -multiplier
                    if (isLeft) { cal.leftEyeX += dx; cal.leftEyeY += dy }
                    else { cal.rightEyeX += dx; cal.rightEyeY += dy }
                    commandRecognized = true
                }
            }
        }
        if (commandRecognized) feedbackTimer = FEEDBACK_DURATION
    }

    private fun saveCalibration() {
        prefs.edit().apply {
            putFloat("lx", cal.leftEyeX); putFloat("ly", cal.leftEyeY)
            putFloat("rx", cal.rightEyeX); putFloat("ry", cal.rightEyeY)
            putFloat("zoom", cal.zoomPercent); putFloat("barrel", cal.barrelK)
            putLong("bias", cal.latencyBiasNs); apply()
        }
    }

    private fun loadCalibration() {
        cal.leftEyeX = prefs.getFloat("lx", 0f); cal.leftEyeY = prefs.getFloat("ly", 0f)
        cal.rightEyeX = prefs.getFloat("rx", 0f); cal.rightEyeY = prefs.getFloat("ry", 0f)
        cal.zoomPercent = prefs.getFloat("zoom", 0f); cal.barrelK = prefs.getFloat("barrel", 0f)
        cal.latencyBiasNs = prefs.getLong("bias", 20_000_000L)
    }

    private fun initBuffers() {
        val bb = ByteBuffer.allocateDirect(squareCoords.size * 4).order(ByteOrder.nativeOrder())
        vertexBuffer = bb.asFloatBuffer().put(squareCoords); vertexBuffer.position(0)
        val fbb = ByteBuffer.allocateDirect(feedbackCoords.size * 4).order(ByteOrder.nativeOrder())
        feedbackVertexBuffer = fbb.asFloatBuffer().put(feedbackCoords); feedbackVertexBuffer.position(0)
        val fbBb = ByteBuffer.allocateDirect(faceBoxCoords.size * 4).order(ByteOrder.nativeOrder())
        faceBoxVertexBuffer = fbBb.asFloatBuffer().put(faceBoxCoords); faceBoxVertexBuffer.position(0)
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
        val textures = IntArray(2)
        GLES30.glGenTextures(2, textures, 0)

        // 1. Camera Texture (OES)
        textureId = textures[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)

        // 2. Snapshot Texture (2D)
        mSnapshotTextureId = textures[1]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mSnapshotTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        // Allocate empty 512x512 texture for snapshots
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 512, 512, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)

        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture!!.setOnFrameAvailableListener { glSurfaceView.requestRender() }

        mProgram = createProgram(VERTEX_SHADER_CODE, FRAGMENT_DISTORTION_CODE)
        mFeedbackProgram = createProgram(VERTEX_FEEDBACK_CODE, FRAGMENT_FEEDBACK_CODE)
        mSnapshotProgram = createProgram(SNAPSHOT_VERTEX_CODE, SNAPSHOT_FRAGMENT_CODE)

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
        if (mSnapshotProgram != 0) {
            mSnapshotPositionHandle = GLES30.glGetAttribLocation(mSnapshotProgram, "aPosition")
            mSnapshotTexCoordHandle = GLES30.glGetAttribLocation(mSnapshotProgram, "aTexCoord")
            mSnapshotMVPHandle = GLES30.glGetUniformLocation(mSnapshotProgram, "uMVPMatrix")
            mSnapshotSamplerHandle = GLES30.glGetUniformLocation(mSnapshotProgram, "sTexture")
            mSnapshotAlphaHandle = GLES30.glGetUniformLocation(mSnapshotProgram, "uAlpha")
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

        // Matrix Setup
        Matrix.translateM(mSTMatrix, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(mSTMatrix, 0, 270f, 0f, 0f, 1f)
        val camRatio = 9.0f / 16.0f; val viewRatio = (w/2f) / h.toFloat()
        val sX = if (viewRatio > camRatio) cameraAspectToView(camRatio, viewRatio) else 1.0f
        val sY = if (viewRatio > camRatio) 1.0f else viewRatio / camRatio
        Matrix.scaleM(mSTMatrix, 0, sX, sY, 1.0f)
        Matrix.translateM(mSTMatrix, 0, -0.5f, -0.5f, 0f)

        val now = System.nanoTime()
        if (lastSensorTimestamp == 0L || abs(now - lastSensorTimestamp) > INERTIA_KILL_THRESHOLD_NS) {
            Matrix.setIdentityM(mViewMatrix, 0)
        } else {
            val target = (surfaceTexture?.timestamp ?: 0L) + cal.latencyBiasNs
            synchronized(rotationHistory) {
                val mThen = findClosestMatrix(target); val mNow = rotationHistory[historyHead]
                val nowInv = FloatArray(16)
                if (Matrix.invertM(nowInv, 0, mNow, 0)) {
                    Matrix.multiplyMM(mDeltaMatrix, 0, nowInv, 0, mThen, 0)
                    System.arraycopy(mDeltaMatrix, 0, mViewMatrix, 0, 16)
                } else Matrix.setIdentityM(mViewMatrix, 0)
            }
        }
        Matrix.scaleM(mViewMatrix, 0, WARP_OVERFILL_SCALE, WARP_OVERFILL_SCALE, 1.0f)
        val calScale = 1.0f + (cal.zoomPercent / 100.0f)

        // --- Render Eyes ---
        GLES30.glUseProgram(mProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glUniform1i(maTextureHandle, 0)
        GLES30.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0)
        GLES30.glUniform1f(muCalibrationScaleHandle, calScale)
        GLES30.glUniform1f(muDistortionKHandle, cal.barrelK)
        GLES30.glEnableVertexAttribArray(maPositionHandle)
        GLES30.glVertexAttribPointer(maPositionHandle, 3, GLES30.GL_FLOAT, false, 12, vertexBuffer)

        // Left
        System.arraycopy(mViewMatrix, 0, mMVPMatrixLeft, 0, 16)
        Matrix.translateM(mMVPMatrixLeft, 0, cal.leftEyeX, cal.leftEyeY, 0f)
        GLES30.glViewport(0, 0, w/2, h)
        GLES30.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrixLeft, 0)
        GLES30.glUniform2f(muLensCenterHandle, 0.5f, 0.5f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        // Right
        System.arraycopy(mViewMatrix, 0, mMVPMatrixRight, 0, 16)
        Matrix.translateM(mMVPMatrixRight, 0, cal.rightEyeX, cal.rightEyeY, 0f)
        GLES30.glViewport(w/2, 0, w/2, h)
        GLES30.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrixRight, 0)
        GLES30.glUniform2f(muLensCenterHandle, 0.5f, 0.5f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        // --- Snapshot Capture Logic ---
        if (requestSnapshot) {
            requestSnapshot = false
            snapshotTimer = SNAPSHOT_HOLD_FRAMES + SNAPSHOT_FADE_FRAMES

            Matrix.setIdentityM(snapshotMVP, 0)
            val glX = (faceRectGL[0] * 2) - 1
            val glY = (faceRectGL[1] * 2) - 1
            val fW = faceRectGL[2] - faceRectGL[0]
            val fH = faceRectGL[3] - faceRectGL[1]
            Matrix.translateM(snapshotMVP, 0, glX, glY, 0f)
            Matrix.scaleM(snapshotMVP, 0, fW * 2, fH * 2, 1f)

            // Calculate pixel coords for copy. Note: GL starts 0 at bottom.
            // faceRectGL[1] is top-based from AI? No, we flipped it: 1.0 - (top/h).
            // So faceRectGL[1] is bottom.
            val copyX = (faceRectGL[0] * (w/2)).toInt()
            val copyY = (faceRectGL[1] * h).toInt()
            val copyW = ((faceRectGL[2] - faceRectGL[0]) * (w/2)).toInt()
            val copyH = ((faceRectGL[3] - faceRectGL[1]) * h).toInt()

            if (copyW > 0 && copyH > 0) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mSnapshotTextureId)
                // Use copy from framebuffer
                GLES30.glCopyTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, copyX, copyY, copyW, copyH, 0)
            }
        }

        // --- Render Snapshot Overlay ---
        if (snapshotTimer > 0) {
            val alpha = if (snapshotTimer > SNAPSHOT_FADE_FRAMES) 1.0f else snapshotTimer.toFloat() / SNAPSHOT_FADE_FRAMES
            drawSnapshot(w, h, alpha)
            snapshotTimer--
        }

        // --- Render Face Box & Indicator ---
        if (faceDetected) {
            faceLostTimer = 15
        } else if (faceLostTimer > 0) {
            faceLostTimer--
        }

        if (faceLostTimer > 0) {
            drawFaceBox(w, h)
            drawFaceIndicator(w, h)
        }

        // --- Render Visual Feedback ---
        if (feedbackTimer > 0) {
            feedbackTimer--
            drawFeedback(w, h)
        }

        GLES30.glDisableVertexAttribArray(maPositionHandle)
    }

    private fun cameraAspectToView(cam: Float, view: Float): Float = cam / view

    private fun drawSnapshot(w: Int, h: Int, alpha: Float) {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(mSnapshotProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mSnapshotTextureId)
        GLES30.glUniform1i(mSnapshotSamplerHandle, 0)
        GLES30.glUniform1f(mSnapshotAlphaHandle, alpha)
        GLES30.glEnableVertexAttribArray(mSnapshotPositionHandle)
        GLES30.glVertexAttribPointer(mSnapshotPositionHandle, 3, GLES30.GL_FLOAT, false, 12, faceBoxVertexBuffer)
        GLES30.glVertexAttribPointer(mSnapshotTexCoordHandle, 3, GLES30.GL_FLOAT, false, 12, faceBoxVertexBuffer)
        GLES30.glEnableVertexAttribArray(mSnapshotTexCoordHandle)
        GLES30.glViewport(0, 0, w/2, h)
        GLES30.glUniformMatrix4fv(mSnapshotMVPHandle, 1, false, snapshotMVP, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glViewport(w/2, 0, w/2, h)
        GLES30.glUniformMatrix4fv(mSnapshotMVPHandle, 1, false, snapshotMVP, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(mSnapshotPositionHandle)
        GLES30.glDisableVertexAttribArray(mSnapshotTexCoordHandle)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun drawFaceBox(w: Int, h: Int) {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(mFeedbackProgram)
        GLES30.glEnableVertexAttribArray(mFeedbackPositionHandle)
        GLES30.glVertexAttribPointer(mFeedbackPositionHandle, 3, GLES30.GL_FLOAT, false, 12, faceBoxVertexBuffer)
        
        // Calculate the same camera frame transformations as applied in onDrawFrame()
        val camRatio = 9.0f / 16.0f
        val viewRatio = (w/2f) / h.toFloat()
        val sX = if (viewRatio > camRatio) cameraAspectToView(camRatio, viewRatio) else 1.0f
        val sY = if (viewRatio > camRatio) 1.0f else viewRatio / camRatio
        
        // Transform faceRectGL coordinates through the same rotation/scaling pipeline as camera feed
        // faceRectGL: [left, bottom, right, top] in normalized 0-1 space from ImageReader
        // The camera feed applies: translate to center, rotate 270°, scale by sX/sY, translate back
        // For 270° rotation: (x, y) -> (1-y, x) after translating to center
        val faceLeft = faceRectGL[0]
        val faceBottom = faceRectGL[1]
        val faceRight = faceRectGL[2]
        val faceTop = faceRectGL[3]
        
        // Transform each corner: translate to center, rotate 270°, scale, translate back
        fun transformPoint(x: Float, y: Float): Pair<Float, Float> {
            // Translate to center
            var tx = x - 0.5f
            var ty = y - 0.5f
            // Rotate 270°: (x, y) -> (y, -x)
            val rx = ty
            val ry = -tx
            // Scale
            val sx = rx * sX
            val sy = ry * sY
            // Translate back
            return Pair(sx + 0.5f, sy + 0.5f)
        }
        
        val (blX, blY) = transformPoint(faceLeft, faceBottom)
        val (brX, brY) = transformPoint(faceRight, faceBottom)
        val (tlX, tlY) = transformPoint(faceLeft, faceTop)
        val (trX, trY) = transformPoint(faceRight, faceTop)
        
        // Find bounding box of transformed corners
        val minX = minOf(blX, brX, tlX, trX)
        val maxX = maxOf(blX, brX, tlX, trX)
        val minY = minOf(blY, brY, tlY, trY)
        val maxY = maxOf(blY, brY, tlY, trY)
        
        // Convert to GL coordinates (-1 to 1) and calculate center/size
        val centerX = (minX + maxX) / 2f
        val centerY = (minY + maxY) / 2f
        val fW = maxX - minX
        val fH = maxY - minY
        
        // Create base MVP matrix for face box (in GL coordinate space)
        Matrix.setIdentityM(mFaceMVP, 0)
        Matrix.translateM(mFaceMVP, 0, centerX * 2f - 1f, centerY * 2f - 1f, 0f)
        Matrix.scaleM(mFaceMVP, 0, fW * 2f, fH * 2f, 1f)
        
        // Left eye: apply leftEyeX/Y offsets independently
        System.arraycopy(mFaceMVP, 0, mFaceMVPLeft, 0, 16)
        Matrix.translateM(mFaceMVPLeft, 0, cal.leftEyeX, cal.leftEyeY, 0f)
        
        // Right eye: apply rightEyeX/Y offsets independently
        System.arraycopy(mFaceMVP, 0, mFaceMVPRight, 0, 16)
        Matrix.translateM(mFaceMVPRight, 0, cal.rightEyeX, cal.rightEyeY, 0f)
        
        // Draw left eye face box
        GLES30.glViewport(0, 0, w / 2, h)
        GLES30.glUniformMatrix4fv(mFeedbackMVPHandle, 1, false, mFaceMVPLeft, 0)
        GLES30.glUniform4f(mFeedbackColorHandle, 1.0f, 0.0f, 0.0f, 0.5f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        
        // Draw right eye face box
        GLES30.glViewport(w / 2, 0, w / 2, h)
        GLES30.glUniformMatrix4fv(mFeedbackMVPHandle, 1, false, mFaceMVPRight, 0)
        GLES30.glUniform4f(mFeedbackColorHandle, 1.0f, 0.0f, 0.0f, 0.5f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        
        GLES30.glDisableVertexAttribArray(mFeedbackPositionHandle)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun drawFaceIndicator(w: Int, h: Int) {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(mFeedbackProgram)
        GLES30.glViewport(w / 2, 0, w / 2, h)
        Matrix.setIdentityM(mFaceMVP, 0)
        Matrix.translateM(mFaceMVP, 0, 0.7f, 0.7f, 0f)
        GLES30.glUniformMatrix4fv(mFeedbackMVPHandle, 1, false, mFaceMVP, 0)
        GLES30.glUniform4f(mFeedbackColorHandle, 0.6f, 0.0f, 1.0f, 0.9f)
        GLES30.glEnableVertexAttribArray(mFeedbackPositionHandle)
        GLES30.glVertexAttribPointer(mFeedbackPositionHandle, 3, GLES30.GL_FLOAT, false, 12, feedbackVertexBuffer)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(mFeedbackPositionHandle)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun drawFeedback(w: Int, h: Int) {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(mFeedbackProgram)
        GLES30.glViewport(0, 0, w / 2, h)
        GLES30.glUniformMatrix4fv(mFeedbackMVPHandle, 1, false, mIdentity, 0)
        val alpha = feedbackTimer.toFloat() / FEEDBACK_DURATION.toFloat()
        GLES30.glUniform4f(mFeedbackColorHandle, 0.0f, 1.0f, 0.0f, alpha)
        GLES30.glEnableVertexAttribArray(mFeedbackPositionHandle)
        GLES30.glVertexAttribPointer(mFeedbackPositionHandle, 3, GLES30.GL_FLOAT, false, 12, feedbackVertexBuffer)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(mFeedbackPositionHandle)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun findClosestMatrix(targetNs: Long): FloatArray {
        var bestIdx = historyHead
        var minDiff = Long.MAX_VALUE
        for (i in 0 until HISTORY_SIZE) {
            val idx = (historyHead - i + HISTORY_SIZE) % HISTORY_SIZE
            val ts = timestampHistory[idx]
            if (ts == 0L) break
            val diff = abs(ts - targetNs)
            if (diff < minDiff) { minDiff = diff; bestIdx = idx } else if (i > 5) break
        }
        val result = rotationHistory[bestIdx]
        return if (isValidMatrix(result)) result else mIdentity
    }

    private fun isValidMatrix(matrix: FloatArray): Boolean {
        var sum = 0f; for (i in 0 until 16) sum += abs(matrix[i]); return sum > 0.1f
    }

    private fun openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (surfaceTexture == null || cameraDevice != null) return
        try {
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { cameraDevice = camera; startPreview() }
                override fun onDisconnected(camera: CameraDevice) { cameraDevice?.close(); cameraDevice = null }
                override fun onError(camera: CameraDevice, error: Int) { cameraDevice?.close(); cameraDevice = null }
            }, null)
        } catch (e: Exception) { }
    }

    private fun startPreview() {
        try {
            surfaceTexture?.setDefaultBufferSize(1920, 1080)
            val surface = Surface(surfaceTexture)
            // FIX: Max images 3, volatile processing flag, try-catch acquire
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 3)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = try {
                    reader.acquireLatestImage()
                } catch (e: Exception) {
                    null
                }
                if (image != null) {
                    if (!isProcessingFace && snapshotTimer == 0) {
                        isProcessingFace = true
                        // FIX: Rotation 0
                        val inputImage = InputImage.fromMediaImage(image, 0)
                        faceDetector.process(inputImage)
                            .addOnSuccessListener { faces ->
                                if (faces.isNotEmpty()) {
                                    val b = faces[0].boundingBox
                                    val w = 480f; val h = 640f

                                    val cx = b.centerX(); val cy = b.centerY()
                                    val halfW = (b.width() / 2f) * 2.0f
                                    val halfH = (b.height() / 2f) * 2.0f

                                    faceRectGL[0] = max(0f, (cx - halfW) / w)
                                    faceRectGL[1] = max(0f, 1.0f - ((cy + halfH) / h))
                                    faceRectGL[2] = min(1f, (cx + halfW) / w)
                                    faceRectGL[3] = min(1f, 1.0f - ((cy - halfH) / h))

                                    if (!faceDetected) {
                                        faceDetected = true
                                        requestSnapshot = true
                                    }
                                } else {
                                    faceDetected = false
                                }
                                isProcessingFace = false
                                image.close()
                            }
                            .addOnFailureListener { isProcessingFace = false; image.close() }
                    } else { image.close() }
                }
            }, sensorHandler)

            val targets = listOf(surface, imageReader!!.surface)
            cameraDevice?.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    try {
                        val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        builder?.addTarget(surface)
                        builder?.addTarget(imageReader!!.surface)
                        builder?.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(60, 60))
                        builder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        session.setRepeatingRequest(builder!!.build(), null, null)
                    } catch (e: Exception) {}
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, null)
        } catch (e: Exception) { }
    }

    private fun closeCamera() {
        try {
            captureSession?.close()
            cameraDevice?.close()
            imageReader?.close()
        } catch (e: Exception) {
            // Ignore close errors
        }
        captureSession = null
        cameraDevice = null
        imageReader = null
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
        if (compileStatus[0] == 0) { GLES30.glDeleteShader(shader); return 0 }
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
    vec2 rVec = vTexCoord - uLensCenter;
    rVec = rVec / uCalibrationScale;
    float r2 = dot(rVec, rVec);
    // Brown-Conrady Model (K1 + K2)
    float f = 1.0 + (uDistortionK * r2) + ((uDistortionK * 0.25) * (r2 * r2));
    vec2 distCoord = uLensCenter + (rVec * f);
    if (distCoord.x < 0.0 || distCoord.x > 1.0 || distCoord.y < 0.0 || distCoord.y > 1.0) {
        FragColor = vec4(0.0, 0.0, 0.0, 1.0);
    } else {
        FragColor = texture(sTexture, distCoord);
    }
}
"""
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
        private const val SNAPSHOT_VERTEX_CODE = """#version 300 es
uniform mat4 uMVPMatrix;
in vec4 aPosition;
in vec2 aTexCoord;
out vec2 vTexCoord;
void main() {
    gl_Position = uMVPMatrix * aPosition;
    vTexCoord = vec2(aPosition.x, aPosition.y); 
}
"""
        private const val SNAPSHOT_FRAGMENT_CODE = """#version 300 es
precision mediump float;
uniform sampler2D sTexture;
uniform float uAlpha;
in vec2 vTexCoord;
out vec4 FragColor;
void main() {
    vec4 color = texture(sTexture, vTexCoord);
    FragColor = vec4(color.rgb, color.a * uAlpha);
}
"""
    }
}