package com.rummikub.app.account

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/** Preset avatars (emojis) offered next to the photo option. */
object AvatarPresets {
    val emojis = listOf(
        "🦊", "🐼", "🦁", "🐯", "🐸", "🐵", "🦄", "🐧",
        "🐙", "🦉", "🐺", "🐻", "🐱", "🐶", "🦋", "🐢",
        "🦈", "🐲", "🎩", "👑", "🎲", "🃏", "⭐", "🚀"
    )
}

object ImageUtils {
    /**
     * Reads a picked image, crops its centre to a square and returns it as a small JPEG
     * (default 256 px, about 20-40 KB) ready to be uploaded as a profile photo.
     * The photo's orientation is respected. Returns null if the file cannot be decoded.
     */
    fun squareJpeg(context: Context, uri: Uri, size: Int = 256): ByteArray? {
        val source = decode(context, uri, size * 2) ?: return null
        val side = min(source.width, source.height)
        val cropped = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(cropped, size, size, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }

    /** Decodes a picked image for the crop screen (about 1024 px on the short side, orientation applied). */
    fun loadForCrop(context: Context, uri: Uri): Bitmap? = decode(context, uri, 1024)

    /** Crops the square ([left], [top], [side]) of [source] and returns a [size] px JPEG. */
    fun cropToJpeg(source: Bitmap, left: Int, top: Int, side: Int, size: Int = 256): ByteArray {
        val cropped = Bitmap.createBitmap(source, left, top, side, side)
        val scaled = Bitmap.createScaledBitmap(cropped, size, size, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }

    private fun decode(context: Context, uri: Uri, targetShortSide: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder applies the EXIF orientation by itself
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val shortSide = min(info.size.width, info.size.height)
                decoder.setTargetSampleSize(max(1, shortSide / targetShortSide))
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetShortSide) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    } catch (_: Exception) {
        null
    }
}
