package dev.avijit.orbit.server

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR codes for the dashboard (SVG) and the terminal ([TerminalQr]). Error correction M, UTF-8. */
object Qr {
    /** One bit per module, without a quiet zone. */
    fun matrix(text: String): BitMatrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to "UTF-8"),
    )

    /**
     * A self-contained SVG: dark navy modules on white with a [quiet]-module quiet zone, one unit per module (it
     * scales crisply to any CSS size). No inline styles, so it passes a strict `style-src 'self'` CSP.
     */
    fun svg(text: String, quiet: Int = 4, label: String = "QR code"): String {
        val m = matrix(text)
        val size = m.width + quiet * 2
        val d = StringBuilder()
        for (y in 0 until m.height) for (x in 0 until m.width) if (m.get(x, y)) d.append("M${x + quiet} ${y + quiet}h1v1h-1z")
        return """<svg xmlns="http://www.w3.org/2000/svg" class="qr" viewBox="0 0 $size $size" shape-rendering="crispEdges" role="img" aria-label="${label.replace("\"", "")}">""" +
            """<rect width="$size" height="$size" fill="#ffffff"/><path fill="#0A0E24" d="$d"/></svg>"""
    }
}
