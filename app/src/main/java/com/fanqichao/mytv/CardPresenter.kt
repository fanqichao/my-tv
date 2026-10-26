package com.fanqichao.mytv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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

        // 焦点框画在台标自身上，未选中时完全透明
        val border = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke((2 * density).toInt().coerceAtLeast(1), Color.WHITE)
            cornerRadius = 4 * density
        }
        val applyFocus = { focused: Boolean ->
            image.background = if (focused) border else null
        }
        card.setOnFocusChangeListener { _, hasFocus -> applyFocus(hasFocus) }
        applyFocus(card.hasFocus())

        // 台标原图自带白→灰渐变底，加载时清除与边缘连通的浅色像素，
        // 否则画面上就是一层灰蒙蒙；台标内部的白字不受影响
        Glide.with(card.context)
            .load(tvViewModel.getTV().logo)
            .transform(TransparentLogoTransform())
            .into(image)
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
