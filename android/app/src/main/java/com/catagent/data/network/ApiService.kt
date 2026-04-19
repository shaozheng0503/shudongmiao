package com.catagent.data.network

import com.catagent.data.model.AnalyzeResponse
import com.catagent.data.model.FollowupRequest
import com.catagent.data.model.HealthResponse
import com.catagent.data.model.SessionEnvelope
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

interface ApiService {
    @Multipart
    @POST("api/v1/analyze")
    suspend fun analyze(
        @Part("input_text") inputText: RequestBody,
        @Part("media_type") mediaType: RequestBody,
        @Part("scene_hint") sceneHint: RequestBody,
        @Part("session_id") sessionId: RequestBody?,
        @Part mediaPart: MultipartBody.Part,
    ): AnalyzeResponse

    @Multipart
    @POST("api/v1/realtime/frame")
    suspend fun analyzeRealtimeFrame(
        @Part("input_text") inputText: RequestBody,
        @Part("scene_hint") sceneHint: RequestBody,
        @Part("session_id") sessionId: RequestBody?,
        @Part mediaPart: MultipartBody.Part,
    ): AnalyzeResponse

    @POST("api/v1/chat/followup")
    suspend fun followup(
        @Body request: FollowupRequest,
    ): AnalyzeResponse

    @GET("api/v1/session/{sessionId}")
    suspend fun getSession(
        @Path("sessionId") sessionId: String,
    ): SessionEnvelope

    @GET("api/v1/health")
    suspend fun health(): HealthResponse
}
