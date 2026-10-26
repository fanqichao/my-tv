package com.fanqichao.mytv

import android.content.Context
import android.graphics.Color
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
 * - 不加任何选中边框；选中态由 Fragment 选中回调驱动：
 *   自绘放大（1.3 倍）+ 抬高层级，不依赖 Leanback 焦点事件（快速滚动不丢效）
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
        // 放大/取消放大都走这里：不依赖 Leanback 的焦点事件
        //（快速滚动时新条目先拿到焦点后绑定，焦点事件会丢，Leanback 的放大就不生效了）
        boundViews.forEach { (tv, view) -> applySelectionState(view, tv === item, animate = true) }
        // 兜底：某些 leanback 版本会给未选中项重新压暗（灰色蒙版），
        // 这里在选中变化后再清一次，保证只有放大、没有变灰
        boundViews.values.forEach { card -> card.post { clearShadowOverlay(card) } }
    }

    /** 按选中状态设置缩放与层级，bind 时直接赋值、选中变化时带动画 */
    private fun applySelectionState(card: View, selected: Boolean, animate: Boolean) {
        val targetScale = if (selected) SELECTED_SCALE else 1f
        val targetZ = if (selected) selectedElevationPx else 0f
        if (animate) {
            card.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .translationZ(targetZ)
                .setDuration(180)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        } else {
            card.animate().cancel()
            card.scaleX = targetScale
            card.scaleY = targetScale
            card.translationZ = targetZ
        }
    }

    private val density: Float get() = context.resources.displayMetrics.density

    private val widthPx: Int get() = (cardWidth * density).toInt().coerceAtLeast(1)

    private val heightPx: Int get() = (widthPx / LOGO_ASPECT).toInt().coerceAtLeast(1)

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = FrameLayout(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            background = null
            clipChildren = false
            // 放大时以台标左边缘为枢轴（贴左布局），避免放大后左侧被切
            pivotX = 0f
            pivotY = heightPx / 2f
        }
        card.addOnLayoutChangeListener { v, l, _, _, _, _, _, _, _ ->
            // 条目会被 Leanback 水平居中：每次布局后把自身的左偏移抵消掉，让整列台标贴住屏幕左边缘
            val target = -l.toFloat()
            if (v.translationX != target) v.translationX = target
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
        card.pivotX = 0f
        card.pivotY = heightPx / 2f
        boundViews[tvViewModel] = card
        // 复用的卡片按当前选中项直接落位（不带动画），保证滚动后状态正确
        applySelectionState(card, tvViewModel === selectedItem, animate = false)

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
            it.alpha = 1f   // 部分版本用 alpha 做压暗，一并恢复到不透明
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                it.foreground = null
            }
        }
        card.alpha = 1f
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

        /** 选中放大倍数：Leanback 自带的只有约 1.17，观感不明显，这里加大 */
        private const val SELECTED_SCALE = 1.3f

        /** 选中项抬高层级，盖过相邻未被选中的条目 */
        private const val SELECTED_ELEVATION_DP = 8f
    }

    private val selectedElevationPx: Float get() = SELECTED_ELEVATION_DP * density
}
