package com.pointshome.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max

internal fun newHomeworkCameraUri(context: Context): Pair<Uri, File> {
    val folder = File(context.cacheDir, "homework_camera").apply { mkdirs() }
    val file = File(folder, "${UUID.randomUUID()}.jpg")
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    return uri to file
}

internal suspend fun homeworkPreview(context: Context, uri: Uri): Bitmap? =
    withContext(Dispatchers.IO) { decodeHomeworkImage(context, uri, 360) }

internal suspend fun compressHomeworkImage(context: Context, uri: Uri): ByteArray =
    withContext(Dispatchers.IO) {
        var bitmap = decodeHomeworkImage(context, uri, 1800)
            ?: throw ApiException("照片无法读取，请重新选择 JPEG 或 PNG 图片")
        try {
            var quality = 78
            repeat(3) { attempt ->
                val output = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                val bytes = output.toByteArray()
                if (bytes.size <= 2_500_000) return@withContext bytes
                if (attempt == 1) {
                    val scale = 1400f / max(bitmap.width, bitmap.height)
                    val smaller = Bitmap.createScaledBitmap(bitmap,
                        (bitmap.width * scale).toInt().coerceAtLeast(1),
                        (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                    if (smaller !== bitmap) bitmap.recycle()
                    bitmap = smaller
                }
                quality = 60
            }
            throw ApiException("照片压缩后仍过大，请换一张照片")
        } finally {
            bitmap.recycle()
        }
    }

private fun decodeHomeworkImage(context: Context, uri: Uri, maxSide: Int): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth < 1 || bounds.outHeight < 1) return null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide * 1.4f) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    var bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: return null
    val exif = runCatching { resolver.openInputStream(uri)?.use { ExifInterface(it) } }.getOrNull()
    val rotation = exif?.rotationDegrees ?: 0
    val flipped = exif?.isFlipped ?: false
    if (rotation != 0 || flipped) {
        val matrix = Matrix()
        if (flipped) matrix.postScale(-1f, 1f)
        if (rotation != 0) matrix.postRotate(rotation.toFloat())
        val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (transformed !== bitmap) bitmap.recycle()
        bitmap = transformed
    }
    val longest = max(bitmap.width, bitmap.height)
    if (longest > maxSide) {
        val scale = maxSide.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        if (scaled !== bitmap) bitmap.recycle()
        bitmap = scaled
    }
    return bitmap
}
