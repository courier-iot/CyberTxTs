package com.cybertxts.app

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A node invite: node code + access key + (optionally) the host's permanent ID.
 * The host ID is the host's fingerprint: whoever joins with it will only ever accept that phone as host,
 * so nobody else in the node can claim to be host and start removing people.
 */
data class Invite(val code: String, val key: String, val host: String?)

object InviteLink {
    private val HEX32 = Regex("^[0-9a-f]{32}$")
    private val LINK = Regex("cybertxts://link\\?[^\\s]+")

    fun build(i: Invite): String {
        val b = Uri.Builder().scheme("cybertxts").authority("link")
            .appendQueryParameter("c", i.code).appendQueryParameter("k", i.key)
        if (i.host != null && HEX32.matches(i.host)) b.appendQueryParameter("h", i.host)
        return b.build().toString()
    }

    /** Accepts a bare link, or any shared text that contains one. */
    fun parse(text: String?): Invite? {
        if (text == null) return null
        val raw = LINK.find(text)?.value ?: return null
        return try {
            val u = Uri.parse(raw)
            val c = u.getQueryParameter("c")?.trim()?.uppercase() ?: return null
            val k = u.getQueryParameter("k") ?: return null
            val h = u.getQueryParameter("h")?.lowercase()?.takeIf { HEX32.matches(it) }
            if (c.isEmpty() || c.length > 40 || k.isEmpty() || k.length > 200) null else Invite(c, k, h)
        } catch (e: Exception) { null }
    }

    fun shareText(i: Invite, hostTag: String?): String = buildString {
        append("Link to my CyberTxTs node.\n")
        append("Node code: ${i.code}\nAccess key: ${i.key}\n")
        if (hostTag != null) append("Host: $hostTag\n")
        append("\nTap to link (or scan the QR in the app):\n")
        append(build(i))
    }

    /**
     * QR code as a bitmap: deep-space modules on a cyan field, with a quiet zone.
     * Dark-on-light is the standard way round, so any scanner (not just CyberTxTs) can read it.
     */
    fun qr(text: String, size: Int = 720): Bitmap {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val px = IntArray(size * size)
        val on = Color.rgb(0x07, 0x06, 0x0F)
        val off = Color.rgb(0x00, 0xE5, 0xFF)
        for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m[x, y]) on else off
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}
