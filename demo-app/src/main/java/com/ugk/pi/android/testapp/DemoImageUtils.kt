package com.ugk.pi.android.testapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * 经过采样纠偏和压缩后的多模态图片数据。
 */
data class ProcessedImage(
    val file: File,
    val base64Data: String,
    val mimeType: String = "image/jpeg",
    val width: Int,
    val height: Int
)

object DemoImageUtils {

    /**
     * 创建相机拍照输出文件。
     */
    fun createCameraPhotoFile(context: Context): File {
        val photosDir = File(context.cacheDir, "photos").apply {
            if (!exists()) mkdirs()
        }
        return File(photosDir, "camera_${System.currentTimeMillis()}.jpg")
    }

    /**
     * 获取指定文件的 FileProvider Uri。
     */
    fun getFileProviderUri(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    /**
     * 高效低内存读取图片、纠正 EXIF 角度、采样并压缩为多模态传输格式与本地缓存文件。
     *
     * @param context 上下文
     * @param sourceUri 图片来源 Uri（相册或拍照输出 Uri）
     * @param maxDimension 最大边长，默认 1280px，保证大模型精确识别同时保持极低传输延迟
     * @param quality JPEG 压缩质量（0~100）
     */
    fun processImageUri(
        context: Context,
        sourceUri: Uri,
        maxDimension: Int = 1280,
        quality: Int = 85
    ): ProcessedImage? = runCatching {
        val contentResolver = context.contentResolver

        // 1. 读取 EXIF 旋转角度
        var orientation = ExifInterface.ORIENTATION_NORMAL
        contentResolver.openInputStream(sourceUri)?.use { input ->
            orientation = runCatching {
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        }

        // 2. 只读取尺寸，计算采样率 inSampleSize
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        contentResolver.openInputStream(sourceUri)?.use { input ->
            BitmapFactory.decodeStream(input, null, boundsOptions)
        }
        val origWidth = boundsOptions.outWidth
        val origHeight = boundsOptions.outHeight
        if (origWidth <= 0 || origHeight <= 0) return null

        var sampleSize = 1
        val maxSide = max(origWidth, origHeight)
        while ((maxSide / sampleSize) > (maxDimension * 1.5f)) {
            sampleSize *= 2
        }

        // 3. 采样解码
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val sampledBitmap = contentResolver.openInputStream(sourceUri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        } ?: return null

        // 4. EXIF 角度回正与精细尺寸缩放
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        }

        val rotatedWidth = if (orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270) {
            sampledBitmap.height
        } else {
            sampledBitmap.width
        }
        val rotatedHeight = if (orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270) {
            sampledBitmap.width
        } else {
            sampledBitmap.height
        }

        val finalMaxSide = max(rotatedWidth, rotatedHeight)
        if (finalMaxSide > maxDimension) {
            val scale = maxDimension.toFloat() / finalMaxSide
            matrix.postScale(scale, scale)
        }

        val finalBitmap = if (!matrix.isIdentity) {
            Bitmap.createBitmap(
                sampledBitmap,
                0,
                0,
                sampledBitmap.width,
                sampledBitmap.height,
                matrix,
                true
            ).also {
                if (it !== sampledBitmap) {
                    sampledBitmap.recycle()
                }
            }
        } else {
            sampledBitmap
        }

        // 5. 压缩为 JPEG 并输出到本地缓存文件与 Base64
        val photosDir = File(context.cacheDir, "photos").apply {
            if (!exists()) mkdirs()
        }
        val targetFile = File(photosDir, "img_${System.currentTimeMillis()}.jpg")
        val byteStream = ByteArrayOutputStream()

        FileOutputStream(targetFile).use { fileOut ->
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, fileOut)
        }
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, byteStream)
        val base64Data = Base64.encodeToString(byteStream.toByteArray(), Base64.NO_WRAP)

        val outWidth = finalBitmap.width
        val outHeight = finalBitmap.height
        finalBitmap.recycle()

        ProcessedImage(
            file = targetFile,
            base64Data = base64Data,
            mimeType = "image/jpeg",
            width = outWidth,
            height = outHeight
        )
    }.getOrNull()
}
