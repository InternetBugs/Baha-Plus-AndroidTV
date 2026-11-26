package app.bahaplus.tv.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Generates a minimal 1-pixel-per-module QR bitmap (~45x45, ~4 KB) and renders it
 * with nearest-neighbor scaling (isFilterBitmap = false) so it stays razor sharp
 * without allocating a 1 MB pixel buffer on the main thread.
 */
internal object QrCodeBitmap {
    fun encode(content: String): Bitmap {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(EncodeHintType.MARGIN to 4)
        )
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height) { index ->
            if (matrix[index % width, index / width]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.RGB_565)
    }

    fun drawable(resources: Resources, bitmap: Bitmap): BitmapDrawable =
        BitmapDrawable(resources, bitmap).apply { isFilterBitmap = false }
}
