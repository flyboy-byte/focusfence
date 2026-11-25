package app.focusfence.server

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** A QR code as inline SVG (one path, dark modules only), so the admin page needs no image or script to show it. */
object Qr {
    fun svg(text: String, label: String): String {
        val hints = mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val d = StringBuilder()
        for (y in 0 until m.height) for (x in 0 until m.width) if (m.get(x, y)) d.append("M$x ${y}h1v1h-1z")
        return "<svg class='qr' role='img' aria-label='${Web.esc(label)}' viewBox='0 0 ${m.width} ${m.height}' shape-rendering='crispEdges'>" +
            "<rect width='100%' height='100%' fill='#fff'/><path fill='#000' d='$d'/></svg>"
    }
}
