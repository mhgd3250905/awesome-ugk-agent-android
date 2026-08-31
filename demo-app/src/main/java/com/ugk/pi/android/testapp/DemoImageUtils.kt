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
import kotlin.math.roundToInt

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

const val MAX_PENDING_IMAGES = 4

/**
 * 针对剩余配额解析待添加图片 URI 的结果。
 */
data class ImageSelectionQuotaResult<T>(
    val accepted: List<T>,
    val ignoredCount: Int,
    val isOverQuota: Boolean
)

/**
 * 纯函数：根据当前已有数量与上限，计算接纳列表和忽略数量。
 */
fun <T> resolveImageSelectionQuota(
    currentCount: Int,
    incoming: List<T>,
    maxLimit: Int = MAX_PENDING_IMAGES
): ImageSelectionQuotaResult<T> {
    val remaining = (maxLimit - currentCount).coerceAtLeast(0)
    if (remaining <= 0 || incoming.isEmpty()) {
        return ImageSelectionQuotaResult(
            accepted = emptyList(),
            ignoredCount = incoming.size,
            isOverQuota = incoming.isNotEmpty()
        )
    }
    val accepted = incoming.take(remaining)
    val ignoredCount = incoming.size - accepted.size
    return ImageSelectionQuotaResult(
        accepted = accepted,
        ignoredCount = ignoredCount,
        isOverQuota = ignoredCount > 0
    )
}

/**
 * 纯函数：无用户输入文字时根据图片张数生成默认提问。
 */
fun resolveDefaultImagePromptText(imageCount: Int): String = when {
    imageCount <= 1 -> "请分析并识别这张图片"
    else -> "请分析并识别这些图片"
}

/** 缩略图最终等比尺寸，宽高均以像素计。 */
internal data class BitmapTargetSize(
    val width: Int,
    val height: Int
)

/**
 * 计算缩略图解码使用的 2 的幂采样率。
 *
 * 采样依据最长边，确保宽图/高图也不会因另一条短边阻止采样而被整张
 * 解码。最终缩放会把采样结果压到硬像素上限内。
 */
internal fun calculateInSampleSize(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidthPx: Int,
    targetHeightPx: Int
): Int {
    if (targetWidthPx <= 0 || targetHeightPx <= 0) return 1
    return calculateInSampleSize(
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        targetMaxSidePx = max(targetWidthPx, targetHeightPx)
    )
}

internal fun calculateInSampleSize(
    sourceWidth: Int,
    sourceHeight: Int,
    targetMaxSidePx: Int
): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0 || targetMaxSidePx <= 0) {
        return 1
    }
    val sourceMaxSide = max(sourceWidth, sourceHeight).toLong()
    val targetMaxSide = targetMaxSidePx.toLong()
    var sampleSize = 1L
    while (sourceMaxSide / (sampleSize * 2L) >= targetMaxSide) {
        sampleSize *= 2L
    }
    return sampleSize.toInt()
}

/**
 * 计算不放大且不超过最长边上限的等比目标尺寸。
 */
internal fun calculateBitmapTargetSize(
    sourceWidth: Int,
    sourceHeight: Int,
    maxSidePx: Int
): BitmapTargetSize {
    if (sourceWidth <= 0 || sourceHeight <= 0 || maxSidePx <= 0) {
        return BitmapTargetSize(0, 0)
    }
    val sourceMaxSide = max(sourceWidth, sourceHeight)
    if (sourceMaxSide <= maxSidePx) {
        return BitmapTargetSize(sourceWidth, sourceHeight)
    }

    val scale = maxSidePx.toDouble() / sourceMaxSide.toDouble()
    return BitmapTargetSize(
        width = (sourceWidth * scale).roundToInt().coerceIn(1, maxSidePx),
        height = (sourceHeight * scale).roundToInt().coerceIn(1, maxSidePx)
    )
}

/**
 * 先读取图片 bounds，再按目标像素有界采样解码缩略图。
 *
 * 全屏预览需要保留原图清晰度时不应调用此方法，应使用其专用的全尺寸解码路径。
 */
internal fun decodeSampledBitmap(
    file: File,
    targetMaxSidePx: Int
): Bitmap? {
    if (!file.isFile || targetMaxSidePx <= 0) return null
    return runCatching {
        val path = file.absolutePath
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, boundsOptions)
        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return@runCatching null

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                sourceWidth = boundsOptions.outWidth,
                sourceHeight = boundsOptions.outHeight,
                targetMaxSidePx = targetMaxSidePx
            )
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val sampledBitmap = BitmapFactory.decodeFile(path, decodeOptions)
            ?: return@runCatching null
        val targetSize = calculateBitmapTargetSize(
            sourceWidth = sampledBitmap.width,
            sourceHeight = sampledBitmap.height,
            maxSidePx = targetMaxSidePx
        )
        if (targetSize.width <= 0 || targetSize.height <= 0) {
            sampledBitmap.recycle()
            return@runCatching null
        }
        if (targetSize.width == sampledBitmap.width && targetSize.height == sampledBitmap.height) {
            return@runCatching sampledBitmap
        }

        val scaledBitmap = try {
            Bitmap.createScaledBitmap(
                sampledBitmap,
                targetSize.width,
                targetSize.height,
                true
            )
        } catch (_: Throwable) {
            null
        }
        if (scaledBitmap == null) {
            sampledBitmap.recycle()
            null
        } else {
            if (scaledBitmap !== sampledBitmap) {
                sampledBitmap.recycle()
            }
            scaledBitmap
        }
    }.getOrNull()
}

/** 保留上一阶段按宽高调用的本地 helper 兼容入口，实际使用最长边上限。 */
internal fun decodeSampledBitmap(
    file: File,
    targetWidthPx: Int,
    targetHeightPx: Int
): Bitmap? {
    if (targetWidthPx <= 0 || targetHeightPx <= 0) return null
    return decodeSampledBitmap(file, max(targetWidthPx, targetHeightPx))
}

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
        val targetFile = File.createTempFile("img_", ".jpg", photosDir)
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
