package com.veil.renderer.api

import okhttp3.MultipartBody
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

data class ServerStatus(
    val state: String // "IDLE", "PROCESSING", "READY"
)

interface VeilApiService {
    @GET("/status")
    fun getStatus(): Call<ServerStatus>

    @Multipart
    @POST("/upload")
    fun uploadImage(@Part image: MultipartBody.Part): Call<Void>
}

