package com.fanqichao.mytv

import android.graphics.Bitmap
import com.bumptech.glide.load.Key
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest

/**
 * 台标底色清除：把「与图片边缘连通的浅色背景」抠成透明。
 *
 * cmvideo 的台标图自带白色→灰色渐变底，在视频画面上就是一层灰蒙蒙。
 * 灰底是渐变的，单个参考色覆盖不到，所以沿四条边各采样 16 个点收集
 * 参考色；再从边缘做 BFS 泛洪，只清除与边缘连通且颜色接近参考色的
 * 像素——台标内部被深色包住的白字（如 CCTV 数字）不会被误伤。
 */
class TransparentLogoTransform : BitmapTransformation() {

    override fun transform(
        context: android.content.Context,
        pool: BitmapPool,
        source: Bitmap,
        outWidth: Int,
        outHeight: Int,
    ): Bitmap {
        val w = source.width
        val h = source.height
        if (w == 0 || h == 0) return source

        val bmp = if (source.isMutable && source.config == Bitmap.Config.ARGB_8888) {
            source
        } else {
            source.copy(Bitmap.Config.ARGB_8888, true) ?: return source
        }

        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        // 1. 沿四条边采样参考色，覆盖整条渐变
        val refs = ArrayList<Int>()
        fun addRef(x: Int, y: Int) {
            val c = pixels[y * w + x]
            if ((c ushr 24) and 0xFF < 128) return
            if (c !in refs) refs.add(c)
        }
        val samples = 16
        for (i in 0 until samples) {
            val x = i * (w - 1) / (samples - 1)
            val y = i * (h - 1) / (samples - 1)
            addRef(x, 0)
            addRef(x, h - 1)
            addRef(0, y)
            addRef(w - 1, y)
        }
        if (refs.isEmpty()) return bmp

        fun isBg(c: Int): Boolean {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (r * 299 + g * 587 + b * 114 < MIN_LUMA * 1000) return false
            return refs.any { ref ->
                val dr = r - ((ref shr 16) and 0xFF)
                val dg = g - ((ref shr 8) and 0xFF)
                val db = b - (ref and 0xFF)
                dr * dr + dg * dg + db * db <= TOLERANCE * TOLERANCE
            }
        }

        // 2. 从边缘种子开始 BFS 泛洪
        val visited = BooleanArray(w * h)
        val queue = ArrayDeque<Int>()
        fun seed(i: Int) {
            if (visited[i]) return
            if (!isBg(pixels[i])) return
            visited[i] = true
            queue.add(i)
        }
        for (x in 0 until w) {
            seed(x)
            seed((h - 1) * w + x)
        }
        for (y in 0 until h) {
            seed(y * w)
            seed(y * w + w - 1)
        }
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            pixels[i] = pixels[i] and 0x00FFFFFF
            val x = i % w
            val y = i / w
            if (x > 0) seed(i - 1)
            if (x < w - 1) seed(i + 1)
            if (y > 0) seed(i - w)
            if (y < h - 1) seed(i + w)
        }

        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    override fun equals(other: Any?): Boolean = other is TransparentLogoTransform

    override fun hashCode(): Int = ID.hashCode()

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(ID_BYTES)
    }

    companion object {
        private const val TOLERANCE = 62
        private const val MIN_LUMA = 150
        private const val ID = "com.fanqichao.mytv.TransparentLogoTransform"
        private val ID_BYTES = ID.toByteArray(Key.CHARSET)
    }
}
