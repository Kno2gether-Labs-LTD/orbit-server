package dev.avijit.orbit.server

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Turns the half-block QR printed by `orbit-server pair` back into pixels and decodes it with ZXing.
 * Each character cell is one module wide and two modules tall: '█' both dark, '▀' top dark, '▄' bottom dark.
 */
fun decodeTerminalQr(output: String): String {
    val ansi = Regex("\u001B\\[[0-9;]*m")
    val rows = output.lines().map { ansi.replace(it, "") }
        .filter { l -> l.isNotEmpty() && l.all { it in " █▀▄" } && l.any { it != ' ' } }
    require(rows.isNotEmpty()) { "no QR block found" }
    val width = rows.maxOf { it.length }
    val modules = ArrayList<BooleanArray>()
    for (r in rows) {
        val top = BooleanArray(width) { x -> r.getOrNull(x).let { it == '█' || it == '▀' } }
        val bottom = BooleanArray(width) { x -> r.getOrNull(x).let { it == '█' || it == '▄' } }
        modules += top
        modules += bottom
    }
    return decodeQrMatrix(modules.toTypedArray())
}

/** Decodes a QR given as rows of dark (true) / light modules. */
fun decodeQrMatrix(modules: Array<BooleanArray>): String {
    val width = modules.maxOf { it.size }
    // Scale up with a white quiet zone so ZXing's detector is happy.
    val scale = 4
    val pad = 8
    val w = (width + pad * 2) * scale
    val h = (modules.size + pad * 2) * scale
    val pixels = IntArray(w * h) { 0xFFFFFF }
    for (y in modules.indices) for (x in 0 until width) if (modules[y].getOrElse(x) { false }) {
        for (dy in 0 until scale) for (dx in 0 until scale) {
            pixels[((y + pad) * scale + dy) * w + (x + pad) * scale + dx] = 0x000000
        }
    }
    val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, pixels)))
    return QRCodeReader().decode(bitmap, mapOf(DecodeHintType.TRY_HARDER to true)).text
}
