package com.catagent.data.network

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.catagent.data.model.AnalyzeResponse
import com.catagent.data.model.FollowupRequest
import com.catagent.data.model.HealthResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class CatAgentRepository(
    private val apiService: ApiService = NetworkModule.apiService,
) {
    suspend fun analyze(
        contentResolver: ContentResolver,
        uri: Uri,
        mediaType: String,
        inputText: String,
        sceneHint: String,
        sessionId: String?,
    ): AnalyzeResponse {
        val tempFile = copyUriToTempFile(contentResolver, uri, mediaType)
        return try {
            val requestMediaType = when (mediaType) {
                "image" -> "image/jpeg"
                else -> "video/mp4"
            }.toMediaType()
            val partName = if (mediaType == "image") "image_file" else "video_file"
            val multipart = MultipartBody.Part.createFormData(
                name = partName,
                filename = tempFile.name,
                body = tempFile.asRequestBody(requestMediaType),
            )

            apiService.analyze(
                inputText = inputText.toRequestBody("text/plain".toMediaType()),
                mediaType = mediaType.toRequestBody("text/plain".toMediaType()),
                sceneHint = sceneHint.toRequestBody("text/plain".toMediaType()),
                sessionId = sessionId?.toRequestBody("text/plain".toMediaType()),
                mediaPart = multipart,
            )
        } finally {
            tempFile.delete()
        }
    }

    suspend fun followup(sessionId: String, question: String): AnalyzeResponse {
        return apiService.followup(FollowupRequest(session_id = sessionId, question_text = question))
    }

    suspend fun health(): HealthResponse {
        return apiService.health()
    }

    suspend fun analyzeRealtimeFrame(
        contentResolver: ContentResolver,
        uri: Uri,
        inputText: String,
        sceneHint: String,
        sessionId: String?,
        jpegQuality: Int = 85,
        maxEdge: Int = 1280,
    ): AnalyzeResponse {
        val tempFile = copyUriToTempJpeg(
            contentResolver = contentResolver,
            uri = uri,
            jpegQuality = jpegQuality.coerceIn(40, 95),
            maxEdge = maxEdge.coerceIn(640, 1920),
        ) ?: copyUriToTempFile(contentResolver, uri, "image")
        return try {
            val multipart = MultipartBody.Part.createFormData(
                name = "image_file",
                filename = tempFile.name,
                body = tempFile.asRequestBody("image/jpeg".toMediaType()),
            )
            apiService.analyzeRealtimeFrame(
                inputText = inputText.toRequestBody("text/plain".toMediaType()),
                sceneHint = sceneHint.toRequestBody("text/plain".toMediaType()),
                sessionId = sessionId?.toRequestBody("text/plain".toMediaType()),
                mediaPart = multipart,
            )
        } finally {
            tempFile.delete()
        }
    }

    private fun copyUriToTempJpeg(
        contentResolver: ContentResolver,
        uri: Uri,
        jpegQuality: Int,
        maxEdge: Int,
    ): File? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while ((bounds.outWidth / sampleSize) > maxEdge || (bounds.outHeight / sampleSize) > maxEdge) {
            sampleSize *= 2
        }
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        } ?: return null

        val scaled = scaleBitmap(decoded, maxEdge)
        val target = File.createTempFile("cat-agent-rt-", ".jpg")
        target.outputStream().use { output ->
            scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output)
        }
        if (scaled !== decoded) {
            decoded.recycle()
        }
        scaled.recycle()
        return target
    }

    private fun scaleBitmap(source: Bitmap, maxEdge: Int): Bitmap {
        val width = source.width
        val height = source.height
        val longEdge = maxOf(width, height)
        if (longEdge <= maxEdge) return source
        val scale = maxEdge.toFloat() / longEdge.toFloat()
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }

    private fun copyUriToTempFile(
        contentResolver: ContentResolver,
        uri: Uri,
        mediaType: String,
    ): File {
        val suffix = if (mediaType == "image") ".jpg" else ".mp4"
        val target = File.createTempFile("cat-agent-", suffix)
        contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: error("无法读取媒体文件")
        return target
    }
}
