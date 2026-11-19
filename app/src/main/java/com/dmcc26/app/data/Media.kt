package com.dmcc26.app.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

const val MAX_FILE_BYTES = 900_000

object Media {
    private fun displayName(ctx: Context, uri: Uri): String {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
        return uri.lastPathSegment ?: "file"
    }

    /** Reads a picked file. Photos are shrunk to fit; other files must be under ~900 KB. */
    suspend fun read(ctx: Context, uri: Uri, maxSide: Int = 1600): BlobData = withContext(Dispatchers.IO) {
        val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
        val name = displayName(ctx, uri)
        val raw = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't open the file")
        if (mime.startsWith("image/") && !mime.contains("gif")) {
            val bmp = decodeScaled(raw, maxSide) ?: error("Couldn't read the image")
            var q = 85
            var out = ByteArray(0)
            var b = bmp
            while (true) {
                val bos = ByteArrayOutputStream()
                b.compress(Bitmap.CompressFormat.JPEG, q, bos)
                out = bos.toByteArray()
                if (out.size <= MAX_FILE_BYTES) break
                if (q > 50) q -= 10 else b = Bitmap.createScaledBitmap(b, (b.width * 0.8).toInt(), (b.height * 0.8).toInt(), true)
            }
            BlobData(out, "image/jpeg", name.substringBeforeLast('.') + ".jpg")
        } else {
            if (raw.size > MAX_FILE_BYTES) error("File is too big (${raw.size / 1024} KB). Maximum is ${MAX_FILE_BYTES / 1024} KB. For documents, take a photo instead.")
            BlobData(raw, mime, name)
        }
    }

    private fun decodeScaled(bytes: ByteArray, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1
        while (o.outWidth / (s * 2) >= maxSide || o.outHeight / (s * 2) >= maxSide) s *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val big = maxOf(bmp.width, bmp.height)
        if (big <= maxSide) return bmp
        val f = maxSide.toFloat() / big
        return Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true)
    }

    /** Opens a stored file in another app (PDF viewer, gallery…). */
    suspend fun open(ctx: Context, data: BlobData) {
        val f = withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "blobs").apply { mkdirs() }
            val safe = data.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "file" }
            File(dir, safe).apply { writeBytes(data.bytes) }
        }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, data.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(Intent.createChooser(i, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
