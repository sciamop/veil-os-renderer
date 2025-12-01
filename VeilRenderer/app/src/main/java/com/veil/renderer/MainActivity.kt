package com.veil.renderer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.*
// import android.media.ImageReader
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
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
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
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
    private lateinit var faceDetector: FaceDetector
    @Volatile private var isProcessingFace = false
    private var faceRectGL = floatArrayOf(0f, 0f, 0f, 0f)
    private var faceDetected = false
    private var faceLostTimer = 0
    private var faceDetectionFrameCounter = 0
    private val FACE_DETECTION_INTERVAL = 10 // Process every 10 frames (~6fps at 60fps)

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

    // Face Box Texture State
    private var mFaceBoxTextureId = 0
    private var mFaceBoxFBO = 0
    private var mFaceBoxProgram = 0
    private var mFaceBoxOverlayProgram = 0
    private var mFaceBoxPositionHandle = 0
    private var mFaceBoxColorHandle = 0
    private var mFaceBoxMVPHandle = 0
    private var mFaceBoxOverlayPositionHandle = 0
    private var mFaceBoxOverlaySTMatrixHandle = 0
    private var mFaceBoxOverlayMVPHandle = 0
    private var mFaceBoxOverlaySamplerHandle = 0

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

    // Face Thumbnail
    private var mFaceThumbnailTextureId = 0
    private var thumbnailInitialized = false
    @Volatile private var pendingFaceBitmap: Bitmap? = null
    
    // Reuse buffers for face detection to avoid GC and "inefficient" warning
    private var captureBuffer: ByteBuffer? = null
    private var captureBitmap: Bitmap? = null
    private var flippedBitmap: Bitmap? = null
    private var flipMatrix: android.graphics.Matrix? = null

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

        // 3. Face Box Texture (2D) - matches camera frame size
        val faceBoxTextures = IntArray(1)
        GLES30.glGenTextures(1, faceBoxTextures, 0)
        mFaceBoxTextureId = faceBoxTextures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mFaceBoxTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        // Allocate 1920x1080 texture matching camera frame size
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 1920, 1080, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)

        // 4. Face Thumbnail Texture (2D)
        val thumbTextures = IntArray(1)
        GLES30.glGenTextures(1, thumbTextures, 0)
        mFaceThumbnailTextureId = thumbTextures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mFaceThumbnailTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        // Allocate 256x256 texture
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 256, 256, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)

        // Create FBO for face box rendering
        val fbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        mFaceBoxFBO = fbos[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mFaceBoxFBO)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, mFaceBoxTextureId, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture!!.setOnFrameAvailableListener { glSurfaceView.requestRender() }

        mProgram = createProgram(VERTEX_SHADER_CODE, FRAGMENT_DISTORTION_CODE)
        mFeedbackProgram = createProgram(VERTEX_FEEDBACK_CODE, FRAGMENT_FEEDBACK_CODE)
        mSnapshotProgram = createProgram(SNAPSHOT_VERTEX_CODE, SNAPSHOT_FRAGMENT_CODE)
        mFaceBoxProgram = createProgram(VERTEX_FEEDBACK_CODE, FRAGMENT_FEEDBACK_CODE)
        mFaceBoxOverlayProgram = createProgram(FACE_BOX_OVERLAY_VERTEX_CODE, FACE_BOX_OVERLAY_FRAGMENT_CODE)

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
        if (mFaceBoxProgram != 0) {
            mFaceBoxPositionHandle = GLES30.glGetAttribLocation(mFaceBoxProgram, "aPosition")
            mFaceBoxColorHandle = GLES30.glGetUniformLocation(mFaceBoxProgram, "uColor")
            mFaceBoxMVPHandle = GLES30.glGetUniformLocation(mFaceBoxProgram, "uMVPMatrix")
        }
        if (mFaceBoxOverlayProgram != 0) {
            mFaceBoxOverlayPositionHandle = GLES30.glGetAttribLocation(mFaceBoxOverlayProgram, "aPosition")
            mFaceBoxOverlaySTMatrixHandle = GLES30.glGetUniformLocation(mFaceBoxOverlayProgram, "uSTMatrix")
            mFaceBoxOverlayMVPHandle = GLES30.glGetUniformLocation(mFaceBoxOverlayProgram, "uMVPMatrix")
            mFaceBoxOverlaySamplerHandle = GLES30.glGetUniformLocation(mFaceBoxOverlayProgram, "sTexture")
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

        // Check if AI found a face and we need to update the GL texture
        if (pendingFaceBitmap != null) {
            uploadFaceTexture()
        }

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

        // --- Capture Frame for Face Detection (throttled) ---
        faceDetectionFrameCounter++
        if (!isProcessingFace && faceDetectionFrameCounter >= FACE_DETECTION_INTERVAL) {
            faceDetectionFrameCounter = 0
            captureFrameForFaceDetection(w, h)
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

        if (thumbnailInitialized) {
            drawFaceThumbnail(w, h)
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

    private fun captureFrameForFaceDetection(w: Int, h: Int) {
        // Capture at lower resolution for performance (e.g., 640x360)
        // Maintain 16:9 aspect ratio to match display if possible
        val captureWidth = 640
        val captureHeight = 360
        
        // Read pixels from framebuffer (left eye viewport)
        // Left eye viewport is (0, 0, w/2, h)
        
        // Initialize/reuse buffer
        if (captureBuffer == null || captureBuffer?.capacity() != captureWidth * captureHeight * 4) {
            captureBuffer = ByteBuffer.allocateDirect(captureWidth * captureHeight * 4)
            captureBuffer?.order(ByteOrder.nativeOrder())
        }
        val buffer = captureBuffer!!
        buffer.clear()
        
        // Read from center of left eye viewport, scaled down
        val readX = ((w/2 - captureWidth) / 2).coerceAtLeast(0)
        val readY = ((h - captureHeight) / 2).coerceAtLeast(0)
        
        GLES30.glReadPixels(
            readX,
            readY,
            captureWidth,
            captureHeight,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            buffer
        )
        
        // Convert to Bitmap
        buffer.rewind()
        
        if (captureBitmap == null || captureBitmap?.width != captureWidth || captureBitmap?.height != captureHeight) {
            captureBitmap = Bitmap.createBitmap(captureWidth, captureHeight, Bitmap.Config.ARGB_8888)
        }
        val bitmap = captureBitmap!!
        bitmap.copyPixelsFromBuffer(buffer)
        
        // Flip vertically (OpenGL has origin at bottom-left, Bitmap at top-left)
        if (flipMatrix == null) {
            val matrix = android.graphics.Matrix()
            matrix.postScale(1f, -1f)
            flipMatrix = matrix
        }
        
        // Create flipped bitmap for face detection
        val currentFlipped = Bitmap.createBitmap(bitmap, 0, 0, captureWidth, captureHeight, flipMatrix!!, true)
        
        // Convert to InputImage and run face detection
        val inputImage = InputImage.fromBitmap(currentFlipped, 0)
        isProcessingFace = true
        
        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val face = faces[0]
                    val b = face.boundingBox
                    val imgW = captureWidth.toFloat()
                    val imgH = captureHeight.toFloat()
                    
                    // Get nose landmark for centering (or fall back to bounding box center)
                    val noseLandmark = face.getLandmark(FaceLandmark.NOSE_BASE)
                    val centerX: Float
                    val centerY: Float
                    
                    if (noseLandmark != null) {
                        // Use nose position as center
                        centerX = noseLandmark.position.x / imgW
                        centerY = 1.0f - (noseLandmark.position.y / imgH) // Flip Y
                    } else {
                        // Fall back to bounding box center
                        centerX = (b.left + b.right) / (2f * imgW)
                        centerY = 1.0f - ((b.top + b.bottom) / (2f * imgH)) // Flip Y
                    }
                    
                    // Calculate face size from bounding box
                    val faceWidth = b.width() / imgW
                    val faceHeight = b.height() / imgH
                    
                    // Create centered box around nose (or face center)
                    val halfW = faceWidth / 2f
                    val halfH = faceHeight / 2f
                    
                    // These coordinates are relative to the CAPTURED CROP (640x360 center)
                    // We need to map them back to the full viewport (0-1)
                    
                    // Fraction of viewport covered by capture
                    val viewportW = (w/2).toFloat()
                    val viewportH = h.toFloat()
                    val cropFracW = captureWidth / viewportW
                    val cropFracH = captureHeight / viewportH
                    
                    // Offset of capture in viewport (normalized)
                    val offsetX = ((viewportW - captureWidth) / 2f) / viewportW
                    val offsetY = ((viewportH - captureHeight) / 2f) / viewportH
                    
                    // Map local crop coordinates to full viewport coordinates
                    val viewCenterX = offsetX + (centerX * cropFracW)
                    val viewCenterY = offsetY + (centerY * cropFracH)
                    val viewHalfW = halfW * cropFracW
                    val viewHalfH = halfH * cropFracH
                    
                    faceRectGL[0] = max(0f, viewCenterX - viewHalfW)
                    faceRectGL[1] = max(0f, viewCenterY - viewHalfH)
                    faceRectGL[2] = min(1f, viewCenterX + viewHalfW)
                    faceRectGL[3] = min(1f, viewCenterY + viewHalfH)
                    
                    if (!faceDetected) {
                        faceDetected = true
                        requestSnapshot = true
                    }
                    
                    // Capture face thumbnail
                    try {
                        val left = b.left.coerceIn(0, currentFlipped.width - 1)
                        val top = b.top.coerceIn(0, currentFlipped.height - 1)
                        val width = b.width().coerceAtMost(currentFlipped.width - left)
                        val height = b.height().coerceAtMost(currentFlipped.height - top)
                        
                        if (width > 0 && height > 0) {
                            val faceCrop = Bitmap.createBitmap(currentFlipped, left, top, width, height)
                            val scaledCrop = Bitmap.createScaledBitmap(faceCrop, 256, 256, true)
                            pendingFaceBitmap = scaledCrop
                            if (faceCrop != scaledCrop && !faceCrop.isRecycled) faceCrop.recycle()
                        }
                    } catch (e: Exception) {
                        Log.e("VeilRenderer", "Error creating face thumbnail", e)
                    }
                } else {
                    faceDetected = false
                }
                isProcessingFace = false
                currentFlipped.recycle()
            }
            .addOnFailureListener {
                isProcessingFace = false
                currentFlipped.recycle()
            }
    }

    private fun renderFaceBoxToTexture() {
        // Bind FBO and render face box to texture
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mFaceBoxFBO)
        GLES30.glViewport(0, 0, 1920, 1080)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        
        // Enable blending for transparency
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        
        // Use face box program to draw rectangle
        GLES30.glUseProgram(mFaceBoxProgram)
        GLES30.glEnableVertexAttribArray(mFaceBoxPositionHandle)
        GLES30.glVertexAttribPointer(mFaceBoxPositionHandle, 3, GLES30.GL_FLOAT, false, 12, faceBoxVertexBuffer)
        
        // faceRectGL: [left, bottom, right, top] in normalized 0-1 space from ImageReader (480x640)
        // Map to texture coordinates (1920x1080)
        // Note: ImageReader is 640x480 but treated as 480x640 in face detection
        val faceLeft = faceRectGL[0]
        val faceBottom = faceRectGL[1]
        val faceRight = faceRectGL[2]
        val faceTop = faceRectGL[3]
        
        // Convert to texture space coordinates (0-1 for 1920x1080 texture)
        // The ImageReader aspect ratio vs camera texture aspect ratio needs consideration
        // For now, map directly assuming same coordinate space
        val texX = faceLeft
        val texY = faceBottom
        val texW = faceRight - faceLeft
        val texH = faceTop - faceBottom
        
        // Create MVP matrix to position face box in texture
        Matrix.setIdentityM(mFaceMVP, 0)
        val glX = (texX * 2) - 1
        val glY = (texY * 2) - 1
        Matrix.translateM(mFaceMVP, 0, glX, glY, 0f)
        Matrix.scaleM(mFaceMVP, 0, texW * 2, texH * 2, 1f)
        
        GLES30.glUniformMatrix4fv(mFaceBoxMVPHandle, 1, false, mFaceMVP, 0)
        GLES30.glUniform4f(mFaceBoxColorHandle, 1.0f, 0.0f, 0.0f, 0.5f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        
        GLES30.glDisableVertexAttribArray(mFaceBoxPositionHandle)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun drawFaceBox(w: Int, h: Int) {
        // First, render face box to texture
        renderFaceBoxToTexture()
        
        // Now render the texture using the same transformations as camera feed
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        
        // Use face box overlay shader (GL_TEXTURE_2D compatible)
        GLES30.glUseProgram(mFaceBoxOverlayProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mFaceBoxTextureId)
        GLES30.glUniform1i(mFaceBoxOverlaySamplerHandle, 0)
        
        // Use Identity matrix for texture coordinates (don't rotate/flip again)
        GLES30.glUniformMatrix4fv(mFaceBoxOverlaySTMatrixHandle, 1, false, mIdentity, 0)
        GLES30.glEnableVertexAttribArray(mFaceBoxOverlayPositionHandle)
        GLES30.glVertexAttribPointer(mFaceBoxOverlayPositionHandle, 3, GLES30.GL_FLOAT, false, 12, vertexBuffer)
        
        // Left eye: Use Identity MVP matrix (don't re-apply view/distortion transforms)
        // The face coordinates are already relative to the rendered view
        GLES30.glViewport(0, 0, w/2, h)
        GLES30.glUniformMatrix4fv(mFaceBoxOverlayMVPHandle, 1, false, mIdentity, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        
        // Right eye: disabled for face box
        // System.arraycopy(mViewMatrix, 0, mMVPMatrixRight, 0, 16)
        // Matrix.scaleM(mMVPMatrixRight, 0, WARP_OVERFILL_SCALE, WARP_OVERFILL_SCALE, 1.0f)
        // Matrix.translateM(mMVPMatrixRight, 0, cal.rightEyeX, cal.rightEyeY, 0f)
        // GLES30.glViewport(w/2, 0, w/2, h)
        // GLES30.glUniformMatrix4fv(mFaceBoxOverlayMVPHandle, 1, false, mMVPMatrixRight, 0)
        // GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        
        GLES30.glDisableVertexAttribArray(mFaceBoxOverlayPositionHandle)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun uploadFaceTexture() {
        val bitmap = pendingFaceBitmap ?: return
        
        // Bind the thumbnail texture
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mFaceThumbnailTextureId)
        
        // Upload the bitmap to the GPU
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        
        // Clean up
        pendingFaceBitmap = null
        // Do not recycle if you are using it elsewhere, but generally good practice in GL:
        // bitmap.recycle() 
        
        thumbnailInitialized = true
    }

    private fun drawFaceThumbnail(w: Int, h: Int) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        GLES30.glUseProgram(mFaceBoxOverlayProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mFaceThumbnailTextureId)
        GLES30.glUniform1i(mFaceBoxOverlaySamplerHandle, 0)

        // Flip Y for Bitmap texture
        Matrix.setIdentityM(mTempMatrix, 0)
        Matrix.translateM(mTempMatrix, 0, 0f, 1f, 0f)
        Matrix.scaleM(mTempMatrix, 0, 1f, -1f, 1f)
        GLES30.glUniformMatrix4fv(mFaceBoxOverlaySTMatrixHandle, 1, false, mTempMatrix, 0)

        GLES30.glEnableVertexAttribArray(mFaceBoxOverlayPositionHandle)
        GLES30.glVertexAttribPointer(mFaceBoxOverlayPositionHandle, 3, GLES30.GL_FLOAT, false, 12, vertexBuffer)

        // --- Configuration ---
        val thumbSize = 256 // Size in pixels
        val yOffset = 100   // Move it up/down relative to center
        
        // --- Left Eye Render ---
        // Calculate center of Left Viewport (0 to w/2)
        val leftCenterX = (w / 4) 
        val leftCenterY = (h / 2) + yOffset
        
        GLES30.glViewport(leftCenterX - (thumbSize / 2), leftCenterY - (thumbSize / 2), thumbSize, thumbSize)
        GLES30.glUniformMatrix4fv(mFaceBoxOverlayMVPHandle, 1, false, mIdentity, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        // --- Right Eye Render ---
        // Calculate center of Right Viewport (w/2 to w)
        val rightCenterX = (w / 2) + (w / 4)
        val rightCenterY = (h / 2) + yOffset

        GLES30.glViewport(rightCenterX - (thumbSize / 2), rightCenterY - (thumbSize / 2), thumbSize, thumbSize)
        GLES30.glUniformMatrix4fv(mFaceBoxOverlayMVPHandle, 1, false, mIdentity, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(mFaceBoxOverlayPositionHandle)
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
            Log.d("VeilRenderer", "Opening camera ID: $cameraId")
            
            // Log all available cameras
            val cameraIds = cameraManager.cameraIdList
            Log.d("VeilRenderer", "Available cameras: ${cameraIds.joinToString()}")
            
            // Log camera characteristics
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            val availableStreamConfigs = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            
            Log.d("VeilRenderer", "Camera $cameraId characteristics:")
            Log.d("VeilRenderer", "  Lens facing: $lensFacing")
            Log.d("VeilRenderer", "  Sensor array size: $sensorArraySize")
            Log.d("VeilRenderer", "  Sensor pixel array size: $sensorSize")
            
            if (sensorArraySize != null) {
                Log.d("VeilRenderer", "  Active array: ${sensorArraySize.width()}x${sensorArraySize.height()}")
            }
            if (sensorSize != null) {
                Log.d("VeilRenderer", "  Pixel array: ${sensorSize.width}x${sensorSize.height}")
            }
            
            // Log available stream configurations
            availableStreamConfigs?.let { configs ->
                val outputSizes = configs.getOutputSizes(SurfaceTexture::class.java)
                Log.d("VeilRenderer", "  Available SurfaceTexture sizes: ${outputSizes.joinToString { "${it.width}x${it.height}" }}")
            }
            
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { 
                    Log.d("VeilRenderer", "Camera opened successfully")
                    cameraDevice = camera
                    startPreview()
                }
                override fun onDisconnected(camera: CameraDevice) { 
                    Log.d("VeilRenderer", "Camera disconnected")
                    cameraDevice?.close()
                    cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) { 
                    Log.e("VeilRenderer", "Camera error: $error")
                    cameraDevice?.close()
                    cameraDevice = null
                }
            }, null)
        } catch (e: Exception) {
            Log.e("VeilRenderer", "Error opening camera", e)
        }
    }

    private fun startPreview() {
        try {
            surfaceTexture?.setDefaultBufferSize(1920, 1080)
            val surface = Surface(surfaceTexture)
            // ImageReader no longer used for face detection (now using framebuffer capture)
            // imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 3)
            // imageReader?.setOnImageAvailableListener(...)

            val targets = listOf(surface)
            cameraDevice?.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    try {
                        val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        builder?.addTarget(surface)
                        // imageReader target removed
                        builder?.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(60, 60))
                        builder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        
                        // Set crop region to zoom out (use full active array size)
                        // Get active array size from camera characteristics
                        val cameraId = cameraManager.cameraIdList[0]
                        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                        val sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                        val maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                        // Note: SCALER_DEFAULT_CROP_REGION may not be available on all devices
                        val defaultCropRegion: Rect? = try {
                            characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let {
                                Rect(0, 0, it.width(), it.height())
                            }
                        } catch (e: Exception) {
                            null
                        }
                        
                        Log.d("VeilRenderer", "Camera capture session configuration:")
                        Log.d("VeilRenderer", "  Sensor array size: $sensorArraySize")
                        Log.d("VeilRenderer", "  Max digital zoom: $maxZoom")
                        Log.d("VeilRenderer", "  Default crop region: $defaultCropRegion")
                        
                        if (sensorArraySize != null) {
                            // Use the full active array size to maximize FOV (zoom out)
                            // This gives the widest possible field of view
                            val cropRegion = Rect(0, 0, sensorArraySize.width(), sensorArraySize.height())
                            Log.d("VeilRenderer", "  Setting crop region: $cropRegion")
                            Log.d("VeilRenderer", "  Crop region size: ${cropRegion.width()}x${cropRegion.height()}")
                            builder?.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
                        } else {
                            Log.w("VeilRenderer", "  Sensor array size is null, cannot set crop region")
                        }
                        
                        // Log the actual request being built
                        val request = builder?.build()
                        val actualCropRegion = request?.get(CaptureRequest.SCALER_CROP_REGION)
                        Log.d("VeilRenderer", "  Actual crop region in request: $actualCropRegion")
                        
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
            // imageReader?.close() - removed
            // Clean up face box FBO and texture
            if (mFaceBoxFBO != 0) {
                GLES30.glDeleteFramebuffers(1, intArrayOf(mFaceBoxFBO), 0)
                mFaceBoxFBO = 0
            }
            if (mFaceBoxTextureId != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(mFaceBoxTextureId), 0)
                mFaceBoxTextureId = 0
            }
        } catch (e: Exception) {
            // Ignore close errors
        }
        captureSession = null
        cameraDevice = null
        // imageReader = null - removed
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
        private const val FACE_BOX_OVERLAY_VERTEX_CODE = """#version 300 es
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
        private const val FACE_BOX_OVERLAY_FRAGMENT_CODE = """#version 300 es
precision mediump float;
in vec2 vTexCoord;
uniform sampler2D sTexture;
out vec4 FragColor;
void main() {
    if (vTexCoord.x < 0.0 || vTexCoord.x > 1.0 || vTexCoord.y < 0.0 || vTexCoord.y > 1.0) {
        FragColor = vec4(0.0, 0.0, 0.0, 0.0);
    } else {
        FragColor = texture(sTexture, vTexCoord);
    }
}
"""
    }
}