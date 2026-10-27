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
 * - 选中态 = 黑色描边，由 Fragment 选中回调驱动，不依赖 Leanback 焦点事件（快速滚动不丢效）
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
        // 选中/取消选中都走这里：不依赖 Leanback 的焦点事件
        //（快速滚动时新条目先拿到焦点后绑定，焦点事件会丢，Leanback 的高亮就不生效了）
        boundViews.forEach { (tv, view) -> applySelectionState(view, tv === item) }
        // 兜底：某些 leanback 版本会给未选中项重新压暗（灰色蒙版），
        // 这里在选中变化后再清一次，保证只有黑框高亮、没有变灰
        boundViews.values.forEach { card -> card.post { clearShadowOverlay(card) } }
    }

    /** 选中态：白色描边，未选中无任何描边 */
    private fun applySelectionState(card: View, selected: Boolean) {
        // 必须用 foreground：background 会被铺满的台标图盖住，看不到边框
        card.foreground = if (selected) selectedBorder else null
    }

    /** 白色描边：透明底 + 白框，加粗，圆角小弧度 */
    private val selectedBorder: android.graphics.drawable.GradientDrawable by lazy {
        android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke((4f * density).toInt().coerceAtLeast(4), Color.WHITE)
            cornerRadius = 4f * density
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
        }
        card.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            // 条目会被 Leanback 水平居中、网格自身也带内边距：
            // 布局后量出条目在窗口里的真实左边，整体平移网格，让台标贴住屏幕左边缘
            stickToLeftEdge(v)
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
        // 复用的卡片按当前选中项直接落位，保证滚动后状态正确
        applySelectionState(card, tvViewModel === selectedItem)

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

    /** 把整列台标平移到屏幕最左侧 */
    private fun stickToLeftEdge(card: View) {
        var parent: android.view.ViewParent? = card.parent
        var grid: View? = null
        while (parent != null) {
            if (parent is androidx.leanback.widget.BaseGridView) {
                grid = parent
                break
            }
            parent = parent.parent
        }
        val gridView = grid ?: return
        val loc = IntArray(2)
        card.getLocationInWindow(loc)
        // 目标位置：离屏幕左边留一点距离，不要完全贴边
        val targetX = (LEFT_OFFSET_DP * density).toInt()
        // 自校正：按当前实际位置微调，误差超过 2px 才动，避免来回抖动
        val delta = loc[0] - targetX
        if (kotlin.math.abs(delta) >= 2) {
            gridView.translationX -= delta.toFloat()
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

        /** 整列台标离屏幕左边的距离（dp）：完全贴边会显得局促，往右挪一点 */
        private const val LEFT_OFFSET_DP = 4f
    }
}
