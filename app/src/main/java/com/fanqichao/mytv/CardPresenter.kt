package com.fanqichao.mytv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.fanqichao.mytv.models.TVViewModel

/**
 * 频道卡片：只显示台标，卡片本体、信息区、台标底色全部透明，
 * 直接叠在右侧视频画面上；焦点态用白色描边 + Leanback 自带放大来区分。
 */
class CardPresenter(
    private val context: Context,
) : Presenter() {

    // 卡片宽度（dp），由面板宽度决定，宽度变化时高度按原比例缩放
    var cardWidth: Int = CARD_WIDTH

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(
            ContextThemeWrapper(parent.context, R.style.CustomImageCardTheme)
        )

        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true
        // 白色卡片底 + 灰色信息区底都去掉，否则透明背景上会露出色块
        cardView.setBackgroundColor(Color.TRANSPARENT)
        cardView.setInfoAreaBackgroundColor(Color.TRANSPARENT)
        // 只留台标：整块信息区（标题/节目名）直接隐藏，不再占高度
        cardView.findViewById<View>(androidx.leanback.R.id.info_field)?.visibility = View.GONE

        // 选中项描边：2dp 白框，暗色画面下也能看清焦点在哪
        val density = parent.resources.displayMetrics.density
        val focusBorder = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke((2 * density).toInt().coerceAtLeast(1), Color.WHITE)
            cornerRadius = 6 * density
        }
        cardView.setOnFocusChangeListener { view, hasFocus ->
            view.background = if (hasFocus) focusBorder else null
        }

        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val tvViewModel = item as TVViewModel
        val cardView = viewHolder.view as ImageCardView

        // 去掉信息区后卡片可以压扁，一屏能多显示几个台
        val cardHeight = (cardWidth * CARD_HEIGHT / CARD_WIDTH).coerceAtLeast(32)
        cardView.setMainImageDimensions(cardWidth, cardHeight)
        cardView.tag = tvViewModel.videoUrl.value
        cardView.setMainImageScaleType(ImageView.ScaleType.CENTER_INSIDE)

        cardView.mainImageView?.let { imageView ->
            // 部分主题会给图片区一层默认底色，这里一并透明
            imageView.setBackgroundColor(Color.TRANSPARENT)
            Glide.with(viewHolder.view.context)
                .load(tvViewModel.getTV().logo)
                .centerInside()
                // 台标自带白/灰渐变底，加载时抠掉，避免在画面上留灰块
                // 注意顺序：centerInside() 会覆盖之前的 transformation，抠图必须放最后
                .transform(TransparentLogoTransform())
                .into(imageView)
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.mainImage = null
    }

    companion object {
        private const val CARD_WIDTH = 300
        private const val CARD_HEIGHT = 68
    }
}
