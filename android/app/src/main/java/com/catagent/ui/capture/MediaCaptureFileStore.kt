package com.catagent.ui.capture

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.catagent.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MediaCaptureFileStore {
    fun createImageUri(context: Context): Uri {
        val file = createImageFile(context)
        return FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file,
        )
    }

    fun createVideoUri(context: Context): Uri {
        val file = createVideoFile(context)
        return FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file,
        )
    }

    fun createImageFile(context: Context): File {
        return createTempFile(context, prefix = "cat_image_", suffix = ".jpg")
    }

    fun createVideoFile(context: Context): File {
        return createTempFile(context, prefix = "cat_video_", suffix = ".mp4")
    }

    private fun createTempFile(
        context: Context,
        prefix: String,
        suffix: String,
    ): File {
        val targetDir = File(context.cacheDir, "captures").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File.createTempFile(prefix + timestamp + "_", suffix, targetDir)
    }
}
