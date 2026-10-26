package com.fanqichao.mytv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.fanqichao.mytv.models.TVViewModel

/**
 * 频道卡片：只显示台标原图，不再使用 ImageCardView。
 *
 * ImageCardView 在焦点变化时会重置卡片自身和信息区的半透明背景，
 * 台标下面总会多出一条灰块；这里改用 FrameLayout + ImageView 自绘，
 * 卡片尺寸严格等于台标原图比例（507x338 ≈ 3:2），选中时只加白描边。
 */
class CardPresenter(
    private val context: Context,
) : Presenter() {

    /** 卡片宽度（dp），由面板宽度决定 */
    var cardWidth: Int = CARD_WIDTH

    private val density: Float get() = context.resources.displayMetrics.density

    private val widthPx: Int get() = (cardWidth * density).toInt().coerceAtLeast(1)

    private val heightPx: Int get() = (widthPx / LOGO_ASPECT).toInt().coerceAtLeast(1)

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = FrameLayout(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(Color.TRANSPARENT)
        }
        val image = ImageView(parent.context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.TRANSPARENT)
        }
        card.addView(image, FrameLayout.LayoutParams(widthPx, heightPx))
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val tvViewModel = item as TVViewModel
        val card = viewHolder.view as FrameLayout
        val image = card.getChildAt(0) as? ImageView ?: return

        // 面板宽度变化时同步卡片尺寸（宽高按 dp 换算成 px）
        (image.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            if (lp.width != widthPx || lp.height != heightPx) {
                lp.width = widthPx
                lp.height = heightPx
                image.layoutParams = lp
            }
        }

        card.tag = tvViewModel.videoUrl.value

        // Leanback 给每个网格条目套了 ShadowOverlayContainer，
        // 默认带一层约 60% 的暗色遮罩，会让台标看起来灰蒙蒙的
        clearShadowOverlay(card)

        // 焦点框画在台标自身上，未选中时完全透明
        val border = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke((2 * density).toInt().coerceAtLeast(1), Color.WHITE)
            cornerRadius = 4 * density
        }
        val applyFocus = { focused: Boolean ->
            image.background = if (focused) border else null
            // 焦点变化时 Leanback 可能重新套上遮罩，这里再清一次
            clearShadowOverlay(card)
        }
        card.setOnFocusChangeListener { _, hasFocus -> applyFocus(hasFocus) }
        applyFocus(card.hasFocus())

        // 加载原图，强制 ARGB_8888：台标图带透明通道，
        // 默认解码会丢 alpha，透明底变成不透明色块
        Glide.with(card.context)
            .load(tvViewModel.getTV().logo)
            .format(com.bumptech.glide.load.DecodeFormat.PREFER_ARGB_8888)
            .into(image)
    }

    /**
     * 清掉 Leanback 给网格条目加的 ShadowOverlayContainer 遮罩。
     *
     * 该容器在前景画一层半透明暗色（实测把白色台标压到约 40% 亮度），
     * 表现为「每个台标上都有层灰色蒙版」。用反射调用 setOverlayColor
     * 设成透明，避免不同 leanback 版本 API 差异导致编译失败。
     */
    private fun clearShadowOverlay(card: android.view.View) {
        val parent = card.parent ?: return
        if (parent.javaClass.simpleName != "ShadowOverlayContainer") return
        try {
            parent.javaClass
                .getMethod("setOverlayColor", Integer.TYPE)
                .invoke(parent, Color.TRANSPARENT)
        } catch (t: Throwable) {
            Log.w("CardPresenter", "setOverlayColor unavailable: ${t.message}")
        }
        (parent as? android.view.View)?.let {
            it.setBackgroundColor(Color.TRANSPARENT)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                it.foreground = null
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as? FrameLayout ?: return
        (card.getChildAt(0) as? ImageView)?.setImageDrawable(null)
    }

    companion object {
        private const val CARD_WIDTH = 110

        // 台标原图 507x338，约 3:2
        private const val LOGO_ASPECT = 507f / 338f
    }
}
