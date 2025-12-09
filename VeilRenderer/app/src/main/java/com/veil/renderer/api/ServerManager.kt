package com.veil.renderer.api

import android.graphics.Bitmap
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

object ServerManager {
    private const val TAG = "ServerManager"
    // TODO: Update this IP to your machine's local IP address
    private const val BASE_URL = "http://192.168.86.64:8000" // 10.0.2.2 is localhost for Android Emulator

    private var apiService: VeilApiService? = null
    var currentState: String = "IDLE"
        private set

    fun init(baseUrlOverride: String? = null) {
        val url = baseUrlOverride ?: BASE_URL
        
        val logging = HttpLoggingInterceptor()
        logging.setLevel(HttpLoggingInterceptor.Level.BODY)

        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        apiService = retrofit.create(VeilApiService::class.java)
        Log.d(TAG, "ServerManager initialized with URL: $url")
    }

    fun checkStatus(onStatusChanged: (String) -> Unit) {
        apiService?.getStatus()?.enqueue(object : Callback<ServerStatus> {
            override fun onResponse(call: Call<ServerStatus>, response: Response<ServerStatus>) {
                if (response.isSuccessful) {
                    val newState = response.body()?.state ?: "UNKNOWN"
                    if (newState != currentState) {
                        currentState = newState
                        onStatusChanged(newState)
                        Log.d(TAG, "Server State Updated: $currentState")
                    }
                }
            }

            override fun onFailure(call: Call<ServerStatus>, t: Throwable) {
                Log.e(TAG, "Failed to check status", t)
            }
        })
    }

    fun uploadFace(bitmap: Bitmap, onSuccess: () -> Unit, onError: (String) -> Unit) {
        if (currentState == "PROCESSING") {
            onError("Server is busy")
            return
        }

        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val byteArray = stream.toByteArray()

        val requestFile = byteArray.toRequestBody("image/png".toMediaTypeOrNull())
        val body = MultipartBody.Part.createFormData("file", "face.png", requestFile)

        Log.d(TAG, "Uploading face (${byteArray.size} bytes)...")
        
        // Optimistically set state to prevent double uploads while waiting for response
        currentState = "PROCESSING" 

        apiService?.uploadImage(body)?.enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                if (response.isSuccessful) {
                    Log.d(TAG, "Upload successful")
                    onSuccess()
                } else {
                    Log.e(TAG, "Upload failed: ${response.code()}")
                    currentState = "IDLE" // Revert on failure
                    onError("Upload failed: ${response.code()}")
                }
            }

            override fun onFailure(call: Call<Void>, t: Throwable) {
                Log.e(TAG, "Upload error", t)
                currentState = "IDLE" // Revert on failure
                onError(t.message ?: "Unknown error")
            }
        })
    }
}

