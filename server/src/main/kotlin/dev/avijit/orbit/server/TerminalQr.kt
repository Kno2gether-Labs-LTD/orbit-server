package dev.avijit.orbit.server

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR codes for the terminal. Each character is one module wide and two tall (Unicode half blocks), drawn black on
 * an explicit white background (ANSI colours) so it scans on both light and dark terminal themes.
 */
object TerminalQr {
    private const val ON = "\u001B[30;107m"
    private const val OFF = "\u001B[0m"

    fun render(text: String, margin: Int = 2): String {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to margin,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        // Width/height 0 = one pixel per module.
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val sb = StringBuilder()
        var y = 0
        while (y < m.height) {
            sb.append(ON)
            for (x in 0 until m.width) {
                val top = m.get(x, y)
                val bottom = y + 1 < m.height && m.get(x, y + 1)
                sb.append(
                    when {
                        top && bottom -> '█'
                        top -> '▀'
                        bottom -> '▄'
                        else -> ' '
                    },
                )
            }
            sb.append(OFF).append('\n')
            y += 2
        }
        return sb.toString()
    }
}
