package com.fanqichao.mytv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.fanqichao.mytv.models.TVViewModel

/**
 * 频道卡片：只显示台标原图。
 *
 * - 不使用 ImageCardView：它会在焦点变化时重置自己的半透明背景
 * - 卡片尺寸严格按台标原图比例（507x338 ≈ 3:2）
 * - 选中态用 StateListDrawable 按焦点状态自动换边框（黑圈 + 金圈），
 *   不依赖 OnFocusChangeListener——Leanback 的聚焦动画会把它覆盖掉
 */
class CardPresenter(
    private val context: Context,
) : Presenter() {

    /** 卡片宽度（dp），由面板宽度决定 */
    var cardWidth: Int = CARD_WIDTH

    /** 当前选中项：Leanback 的焦点其实落在 ShadowOverlayContainer 上，
     *  卡片自己拿不到 focus 状态，所以由 Fragment 的选中回调来驱动高亮 */
    private var selectedItem: TVViewModel? = null

    private val boundViews = HashMap<TVViewModel, android.view.View>()

    fun setSelected(item: TVViewModel?) {
        if (selectedItem === item) return
        selectedItem = item
        boundViews.forEach { (tv, view) -> view.isActivated = tv === item }
    }

    private val density: Float get() = context.resources.displayMetrics.density

    private val widthPx: Int get() = (cardWidth * density).toInt().coerceAtLeast(1)

    private val heightPx: Int get() = (widthPx / LOGO_ASPECT).toInt().coerceAtLeast(1)

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = FrameLayout(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            background = focusBorderDrawable()
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
        boundViews[tvViewModel] = card
        card.isActivated = tvViewModel === selectedItem

        // Leanback 给每个网格条目套了 ShadowOverlayContainer，
        // 默认带一层约 60% 的暗色遮罩，会让台标看起来灰蒙蒙的
        clearShadowOverlay(card)

        // 加载原图，强制 ARGB_8888：台标图带透明通道，
        // 默认解码会丢 alpha，透明底变成不透明色块
        Glide.with(card.context)
            .load(tvViewModel.getTV().logo)
            .format(com.bumptech.glide.load.DecodeFormat.PREFER_ARGB_8888)
            .into(image)
    }

    /**
     * 选中态边框：外圈黑 + 内圈金。
     * 台标自带浅色底，纯白描边会糊在一起，金色在亮/暗画面上都醒目。
     * 用 StateListDrawable 跟随卡片的 focus 状态，避免监听被 Leanback 覆盖。
     */
    private fun focusBorderDrawable(): StateListDrawable {
        val goldRing = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke((3 * density).toInt().coerceAtLeast(1), 0xFFFFC107.toInt())
            cornerRadius = 5 * density
        }
        val blackRing = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke((5 * density).toInt().coerceAtLeast(1), Color.BLACK)
            cornerRadius = 5 * density
        }
        val border = LayerDrawable(arrayOf(blackRing, goldRing)).apply {
            val inset = (2 * density).toInt()
            setLayerInset(1, inset, inset, inset, inset)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_activated), border)
            addState(intArrayOf(android.R.attr.state_focused), border)
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
    }

    /**
     * 清掉 Leanback 给网格条目加的 ShadowOverlayContainer 遮罩。
     *
     * 该容器在前景画一层半透明暗色（实测把白色台标压到约 40% 亮度），
     * 表现为「每个台标上都有层灰色蒙版」。用反射调用 setOverlayColor
     * 设成透明，避免不同 leanback 版本 API 差异导致编译失败。
     */
    private fun clearShadowOverlay(card: View) {
        val parent = card.parent ?: return
        if (parent.javaClass.simpleName != "ShadowOverlayContainer") return
        try {
            parent.javaClass
                .getMethod("setOverlayColor", Integer.TYPE)
                .invoke(parent, Color.TRANSPARENT)
        } catch (t: Throwable) {
            Log.w("CardPresenter", "setOverlayColor unavailable: ${t.message}")
        }
        (parent as? View)?.let {
            it.setBackgroundColor(Color.TRANSPARENT)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                it.foreground = null
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as? FrameLayout ?: return
        boundViews.entries.removeAll { it.value === card }
        (card.getChildAt(0) as? ImageView)?.setImageDrawable(null)
    }

    companion object {
        private const val CARD_WIDTH = 84

        // 台标原图 507x338，约 3:2
        private const val LOGO_ASPECT = 507f / 338f
    }
}
