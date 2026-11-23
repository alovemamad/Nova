package app.novavpn.data.store

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import app.novavpn.domain.model.VpnProfile
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

object ProfileShareCodec {
    private const val URI_PREFIX = "novavpn://profile?data="
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    fun encodeToUri(profile: VpnProfile): String {
        val raw = json.encodeToString(profile)
        val b64 = Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "$URI_PREFIX$b64"
    }

    fun decodeFromText(input: String): Result<VpnProfile> = runCatching {
        val trimmed = input.trim()
        val jsonStr = when {
            trimmed.startsWith(URI_PREFIX) -> {
                val b64 = trimmed.removePrefix(URI_PREFIX).substringBefore('&')
                String(Base64.decode(b64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
            }
            trimmed.startsWith("{") -> trimmed
            else -> error("Unrecognized Nova VPN profile format")
        }
        val decoded = json.decodeFromString<VpnProfile>(jsonStr)
        decoded.copy(
            id = UUID.randomUUID().toString(),
            name = decoded.name.ifBlank { "Imported Profile" },
            updatedAtEpochMs = System.currentTimeMillis()
        )
    }

    fun generateQrBitmap(content: String, sizePx: Int = 640): Bitmap? = runCatching {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        bmp
    }.getOrNull()

    fun decodeQrFromBitmap(bitmap: Bitmap): Result<String> = runCatching {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        MultiFormatReader().decode(binaryBitmap).text
    }
}
