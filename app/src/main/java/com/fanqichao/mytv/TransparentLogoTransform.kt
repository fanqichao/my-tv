package com.fanqichao.mytv

import android.graphics.Bitmap
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max

/**
 * 台标图普遍带白色→浅灰的渐变底，直接贴在视频画面上会露出一块灰。
 *
 * 做法：沿图片四条边多点采样得到一组参考色，再从四边向内做泛洪填充，
 * 只把「与边缘连通」且「接近某个参考色」的浅色像素抠成透明。
 * 台标内部被深色块包住的元素（例如 CCTV 的白色数字）不与边缘连通，不会被误伤。
 *
 * 采样点沿整条边分布而不是只取四角，是为了覆盖上下渐变的深浅两端，
 * 否则图片底部较深的那一段会残留一条灰边。
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

        val refs = borderReferences(pixels, w, h)
        if (refs.isEmpty()) return bitmap

        val visited = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0

        fun seed(x: Int, y: Int) {
            val index = y * w + x
            if (visited[index]) return
            if (!isBackground(pixels[index], refs)) return
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
            // 保留 RGB、把 alpha 置 0，边缘由系统插值过渡，不会出现硬锯齿
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

    /** 沿四条边均匀取点，收集若干「浅色」参考色，覆盖整条渐变 */
    private fun borderReferences(pixels: IntArray, w: Int, h: Int): List<IntArray> {
        val refs = ArrayList<IntArray>(MAX_REFS)

        fun add(x: Int, y: Int) {
            val color = pixels[y * w + x]
            if (((color ushr 24) and 0xFF) < 128) return
            if (luma(color) < MIN_LUMA) return
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val duplicate = refs.any {
                abs(it[0] - r) < 8 && abs(it[1] - g) < 8 && abs(it[2] - b) < 8
            }
            if (!duplicate) refs.add(intArrayOf(r, g, b))
        }

        val stepX = max(1, w / SAMPLE_STEPS)
        val stepY = max(1, h / SAMPLE_STEPS)
        var x = 0
        while (x < w) {
            add(x, 0)
            add(x, h - 1)
            x += stepX
        }
        var y = 0
        while (y < h) {
            add(0, y)
            add(w - 1, y)
            y += stepY
        }
        return if (refs.size > MAX_REFS) refs.subList(0, MAX_REFS) else refs
    }

    private fun luma(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    private fun isBackground(color: Int, refs: List<IntArray>): Boolean {
        // 只抠浅色，深色台标主体永远保留
        if (luma(color) < MIN_LUMA) return false
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        for (ref in refs) {
            val dr = r - ref[0]
            val dg = g - ref[1]
            val db = b - ref[2]
            if (dr * dr + dg * dg + db * db <= TOLERANCE * TOLERANCE) return true
        }
        return false
    }

    companion object {
        private const val ID = "com.fanqichao.mytv.TransparentLogoTransform.v2"

        // 与参考色的 RGB 距离容差（平方比较）
        private const val TOLERANCE = 70

        // 亮度低于该值视为台标本体，不参与抠图
        private const val MIN_LUMA = 150

        // 每条边的采样段数
        private const val SAMPLE_STEPS = 16

        private const val MAX_REFS = 16
    }
}
