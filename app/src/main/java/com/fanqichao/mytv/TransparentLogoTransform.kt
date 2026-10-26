package com.fanqichao.mytv

import android.graphics.Bitmap
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest

/**
 * 台标图普遍带白色→浅灰的渐变底，直接贴在视频画面上会露出一块灰。
 *
 * 这里从图片四条边做泛洪填充：只有「与边缘连通」且「接近底色」的浅色像素才被抠成透明，
 * 台标内部被黑色/深色块包住的白色元素（例如 CCTV 的白色数字）不与边缘连通，不会被误伤。
 */
class TransparentLogoTransform : BitmapTransformation() {

    override fun transform(
        pool: BitmapPool,
        source: Bitmap,
        outWidth: Int,
        outHeight: Int
    ): Bitmap = removeBackground(source)

    override fun equals(other: Any?): Boolean = other is TransparentLogoTransform

    override fun hashCode(): Int = ID.hashCode()

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(ID.toByteArray(Charsets.UTF_8))
    }

    private fun removeBackground(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return source

        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true) ?: return source
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // 底色参考值取四角里最不透明、最亮的一个，避免个别角是透明像素时判错
        val ref = brightestCorner(pixels, w, h) ?: return bitmap

        val visited = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0

        fun seed(x: Int, y: Int) {
            val index = y * w + x
            if (visited[index]) return
            if (!isBackground(pixels[index], ref)) return
            visited[index] = true
            queue[tail++] = index
        }

        for (x in 0 until w) {
            seed(x, 0)
            seed(x, h - 1)
        }
        for (y in 0 until h) {
            seed(0, y)
            seed(w - 1, y)
        }

        while (head < tail) {
            val index = queue[head++]
            // 保留 RGB、把 alpha 置 0，边缘像素由系统按插值过渡，不会出现硬锯齿
            pixels[index] = pixels[index] and 0x00FFFFFF

            val x = index % w
            val y = index / w
            if (x > 0) seed(x - 1, y)
            if (x < w - 1) seed(x + 1, y)
            if (y > 0) seed(x, y - 1)
            if (y < h - 1) seed(x, y + 1)
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    private fun brightestCorner(pixels: IntArray, w: Int, h: Int): Int? {
        val corners = intArrayOf(
            pixels[0],
            pixels[w - 1],
            pixels[(h - 1) * w],
            pixels[h * w - 1]
        )
        var best: Int? = null
        var bestLuma = -1
        for (color in corners) {
            if (((color ushr 24) and 0xFF) < 128) continue
            val luma = luma(color)
            if (luma > bestLuma) {
                bestLuma = luma
                best = color
            }
        }
        return best
    }

    private fun luma(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    private fun isBackground(color: Int, ref: Int): Boolean {
        // 只抠浅色，深色台标主体永远保留
        if (luma(color) < MIN_LUMA) return false
        val dr = ((color shr 16) and 0xFF) - ((ref shr 16) and 0xFF)
        val dg = ((color shr 8) and 0xFF) - ((ref shr 8) and 0xFF)
        val db = (color and 0xFF) - (ref and 0xFF)
        return dr * dr + dg * dg + db * db <= TOLERANCE * TOLERANCE
    }

    companion object {
        private const val ID = "com.fanqichao.mytv.TransparentLogoTransform.v1"

        // 与底色的 RGB 距离容差（平方比较），覆盖白→浅灰的渐变
        private const val TOLERANCE = 62

        // 亮度低于该值视为台标本体，不参与抠图
        private const val MIN_LUMA = 150
    }
}
