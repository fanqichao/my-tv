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
 * 直接叠在右侧视频画面上。
 *
 * 台标按原图比例（507x338 ≈ 3:2）显示，白色焦点框画在台标自身上，
 * 不再是「一整列宽的大方格」。
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
        val cardView = ImageCardView(
            ContextThemeWrapper(parent.context, R.style.CustomImageCardTheme)
        )

        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true
        // 白色卡片底 + 灰色信息区底都去掉，否则透明背景上会露出色块
        cardView.setBackgroundColor(Color.TRANSPARENT)
        cardView.setInfoAreaBackgroundColor(Color.TRANSPARENT)
        // 去掉卡片默认内边距，否则台标四周会多出一圈空白，看着像大方格
        cardView.setPadding(0, 0, 0, 0)
        // 只留台标：整块信息区（标题/节目名）直接隐藏，不再占高度
        cardView.findViewById<View>(androidx.leanback.R.id.info_field)?.visibility = View.GONE

        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val tvViewModel = item as TVViewModel
        val cardView = viewHolder.view as ImageCardView

        // 注意：setMainImageDimensions 收的是 px，cardWidth 是 dp，必须换算，
        // 否则高密度屏上台标只按 dp 数值这么大，四周会空出一大圈
        cardView.setMainImageDimensions(widthPx, heightPx)
        cardView.tag = tvViewModel.videoUrl.value
        cardView.setMainImageScaleType(ImageView.ScaleType.CENTER_INSIDE)

        cardView.mainImageView?.let { imageView ->
            // 部分主题会给图片区一层默认底色，这里一并透明
            imageView.setBackgroundColor(Color.TRANSPARENT)

            // 焦点框画在台标上，尺寸正好等于台标，不会出现比图大一圈的方格
            val border = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke((2 * density).toInt().coerceAtLeast(1), Color.WHITE)
                cornerRadius = 4 * density
            }
            val applyFocus = { focused: Boolean ->
                imageView.background = if (focused) border else null
            }
            cardView.setOnFocusChangeListener { _, hasFocus -> applyFocus(hasFocus) }
            applyFocus(cardView.hasFocus())

            // 直接用原图，不做任何处理
            Glide.with(viewHolder.view.context)
                .load(tvViewModel.getTV().logo)
                .centerInside()
                .into(imageView)
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.mainImage = null
    }

    companion object {
        private const val CARD_WIDTH = 110

        // 台标原图 507x338，约 3:2
        private const val LOGO_ASPECT = 507f / 338f
    }
}
