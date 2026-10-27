package com.fanqichao.mytv

import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Toast
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.leanback.widget.VerticalGridPresenter
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fanqichao.mytv.api.YSP
import com.fanqichao.mytv.models.ProgramType
import com.fanqichao.mytv.models.TVListViewModel
import com.fanqichao.mytv.models.TVViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 频道面板：竖向单列网格，上下键在频道间垂直移动，确认键切台。
 */
class MainFragment : VerticalGridSupportFragment() {

    private var itemPosition = 0

    private var rowsAdapter: ArrayObjectAdapter? = null

    private var cardPresenter: CardPresenter? = null

    var tvListViewModel = TVListViewModel()

    private var lastVideoUrl = ""

    private var lastConfirmTime = 0L

    /** 触屏滚动中：此时抬手不能被当成点击（否则会误切台、面板被收起），
     *  切台回弹的 setSelectedPosition 也不能执行（会把列表拽回原位置） */
    private var touchScrolling = false

    private val touchSlop: Int by lazy { ViewConfiguration.get(requireContext()).scaledTouchSlop }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "onCreate")
        super.onCreate(savedInstanceState)

        // 竖向网格：单列，上下键即垂直换频道
        // 第二个参数 false = 关闭 focus dimmer（未选中项的灰色蒙版）；
        // ZOOM_FACTOR_NONE = 关闭 Leanback 自带的放大——它倍数小且依赖焦点事件，
        // 快速滚动时新条目先拿到焦点后绑定，事件会丢，导致后面的频道不放大。
        // 放大改由 CardPresenter 在选中回调里自己驱动，稳定且倍数可调
        val gridPresenter = VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_NONE, false)
        gridPresenter.numberOfColumns = 1
        gridPresenter.shadowEnabled = false
        setGridPresenter(gridPresenter)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        // 频道面板背景改为完全透明，右侧视频画面可以透出
        view?.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        activity?.let { YSP.init(it) }

        loadRows()

        view?.setOnTouchListener { _, event ->
            Log.d(TAG, "root touch action=${event.actionMasked} x=${event.x} y=${event.y}")
            false
        }

        // 收紧频道卡片的上下间距，默认间距太大，一屏显示不了几个台；
        // 左右内边距清零，让卡片贴靠面板左侧
        view?.post {
            val grid = findGridView(view) ?: return@post
            // 上下间距稍微放宽一点，列表看起来更透气
            grid.setItemSpacing((8 * resources.displayMetrics.density).toInt())
            // 上下留 12dp 余量：选中项放大 1.5 倍时上下各长 ~15px，不留余量会被屏幕上下边裁掉
            val margin = (12 * resources.displayMetrics.density).toInt()
            grid.setPadding(0, maxOf(grid.paddingTop, margin), 0, maxOf(grid.paddingBottom, margin))
            // 放大的条目允许画到网格边界外，避免上下两端被裁掉
            grid.clipChildren = false
            grid.clipToPadding = false
            grid.isScrollEnabled = true
            // Leanback 自带的触摸处理在"聚焦式网格"上问题很多：
            // 抬手常被误判成点击（误切台、面板被收起），滚动结束时又会按焦点把列表拽回原位置
            //（表现为"滑几屏焦点又回来了"）。这里直接拦下全部触摸事件，自己实现拖拽滚动与轻点切台。
            val velocityTracker = VelocityTracker.obtain()
            var downY = 0f
            var lastY = 0f
            var moved = false
            grid.setOnTouchInterceptListener { event ->
                Log.d(TAG, "grid intercept action=${event.actionMasked} y=${event.y}")
                (activity as? MainActivity)?.mainActive()
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downY = event.y
                        lastY = event.y
                        moved = false
                        touchScrolling = false
                        velocityTracker.clear()
                        velocityTracker.addMovement(event)
                        grid.stopScroll()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        velocityTracker.addMovement(event)
                        if (!moved && kotlin.math.abs(event.y - downY) > touchSlop) {
                            moved = true
                            touchScrolling = true
                        }
                        if (moved) {
                            val dy = (lastY - event.y).toInt()
                            val before = (grid.layoutManager as? LinearLayoutManager)
                                ?.findFirstVisibleItemPosition() ?: -1
                            grid.scrollBy(0, dy)
                            val after = (grid.layoutManager as? LinearLayoutManager)
                                ?.findFirstVisibleItemPosition() ?: -1
                            Log.d(TAG, "scrollBy $dy first $before -> $after enabled=${grid.isScrollEnabled} canScrollDown=${grid.canScrollVertically(1)} canScrollUp=${grid.canScrollVertically(-1)} scrollY=${grid.scrollY}")
                        }
                        lastY = event.y
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        velocityTracker.addMovement(event)
                        if (moved) {
                            // 惯性滑动，松手后还能再滑一段，手感更接近原生列表
                            velocityTracker.computeCurrentVelocity(1000, 8000f)
                            val velocityY = velocityTracker.yVelocity
                            if (kotlin.math.abs(velocityY) > 300f) {
                                grid.fling(0, (-velocityY).toInt())
                            }
                            // 滚动结束后把"选中/焦点"对齐到当前可见的第一个条目，
                            // 否则列表一重新布局就会按老焦点跳回去
                            grid.postDelayed({ syncSelectionWhenIdle() }, 200)
                            grid.postDelayed({ touchScrolling = false }, 450)
                        } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                            confirmItemAt(event.x, event.y)
                        }
                    }
                }
                // 返回 true = 事件被吞掉，不再交给 Leanback 的网格处理
                true
            }
        }

        setupEventListeners()

        tvListViewModel.tvListViewModel.value?.forEach { tvViewModel ->
            tvViewModel.errInfo.observe(viewLifecycleOwner) { _ ->
                if (tvViewModel.errInfo.value != null
                    && tvViewModel.getRowPosition() == itemPosition
                ) {
                    Toast.makeText(context, tvViewModel.errInfo.value, Toast.LENGTH_SHORT).show()
                }
            }
            tvViewModel.ready.observe(viewLifecycleOwner) { _ ->

                // not first time && channel not change
                if (tvViewModel.ready.value != null
                    && tvViewModel.getRowPosition() == itemPosition
                    && check(tvViewModel)
                ) {
                    Log.i(TAG, "ready ${tvViewModel.getTV().title}")
                    (activity as? MainActivity)?.play(tvViewModel)
                }
            }
            tvViewModel.change.observe(viewLifecycleOwner) { _ ->
                if (tvViewModel.change.value != null) {
                    val title = tvViewModel.getTV().title
                    Log.i(TAG, "switch $title")
                    if (tvViewModel.getTV().pid != "") {
                        Log.i(TAG, "request $title")
                        lifecycleScope.launch(Dispatchers.IO) {
                            tvViewModel.let { Request.fetchData(it) }
                        }
                        (activity as? MainActivity)?.showInfoFragment(tvViewModel)
                        // 触屏滚动时不要回弹：否则会把手滑了几屏的列表拽回当前频道
                        if (!touchScrolling) {
                            setSelectedPosition(tvViewModel.getRowPosition())
                        }
                    } else {
                        if (check(tvViewModel)) {
                            (activity as? MainActivity)?.play(tvViewModel)
                            (activity as? MainActivity)?.showInfoFragment(tvViewModel)
                            if (!touchScrolling) {
                                setSelectedPosition(tvViewModel.getRowPosition())
                            }
                        }
                    }
                }
            }
        }

        (activity as MainActivity).fragmentReady("MainFragment")
    }

    fun toLastPosition() {
        setSelectedPosition(tvListViewModel.size() - 1)
    }

    fun toFirstPosition() {
        setSelectedPosition(0)
    }

    private fun loadRows() {
        // 面板只占屏幕左侧一条，卡片宽度按面板宽度自适应，避免超出被裁切
        val dm = resources.displayMetrics
        val panelWidthDp = (dm.widthPixels * MainActivity.PANEL_WIDTH_RATIO / dm.density).toInt()
        val presenter = CardPresenter(context!!).apply {
            // 台标不需要占满面板：68dp 再放大 10% ≈ 75dp
            cardWidth = (panelWidthDp - 12).coerceIn(44, 75)
        }
        cardPresenter = presenter

        rowsAdapter = ArrayObjectAdapter(presenter)

        // 展平所有分组：分组顺序保留，频道按全局序号竖排
        var idx = 0
        for ((_, v) in TVList.list) {
            for (v1 in v) {
                // 内置频道表的 id 默认都是 0，这里按全局序号回填，
                // 否则选台时按 id 定位会永远落在第 0 个频道
                v1.id = idx
                val tvViewModel = TVViewModel(v1)
                tvViewModel.setRowPosition(idx)   // 全局序号，即网格条目位置
                tvViewModel.setItemPosition(0)
                tvListViewModel.addTVViewModel(tvViewModel)
                rowsAdapter!!.add(tvViewModel)
                idx++
            }
        }
        tvListViewModel.maxNum.add(idx)

        adapter = rowsAdapter

        itemPosition = SP.itemPosition
        if (itemPosition >= tvListViewModel.size()) {
            itemPosition = 0
        }
        tvListViewModel.setItemPosition(itemPosition)
        setSelectedPosition(itemPosition)
    }

    fun prevSource() {
        view?.post {
            val tvViewModel = tvListViewModel.getTVViewModel(itemPosition)
            if (tvViewModel != null) {
                if (tvViewModel.videoUrl.value!!.size > 1) {
                    val videoIndex = tvViewModel.videoIndex.value?.minus(1)
                    if (videoIndex == -1) {
                        tvViewModel.setVideoIndex(tvViewModel.videoUrl.value!!.size - 1)
                    }
                    tvViewModel.changed()
                }
            }
        }
    }

    fun nextSource() {
        view?.post {
            val tvViewModel = tvListViewModel.getTVViewModel(itemPosition)
            if (tvViewModel != null) {
                if (tvViewModel.videoUrl.value!!.size > 1) {
                    val videoIndex = tvViewModel.videoIndex.value?.plus(1)
                    if (videoIndex == tvViewModel.videoUrl.value!!.size) {
                        tvViewModel.setVideoIndex(0)
                    }
                    tvViewModel.changed()
                }
            }
        }
    }

    private fun setupEventListeners() {
        setOnItemViewClickedListener(ItemViewClickedListener())
        setOnItemViewSelectedListener(ItemViewSelectedListener())
    }

    private inner class ItemViewClickedListener : OnItemViewClickedListener {
        // VerticalGridPresenter 走网格点击桥接时 rowViewHolder 恒为 null，
        // 这里必须是可空类型，否则 Kotlin 的入参非空校验会直接抛 NPE 闪退
        override fun onItemClicked(
            itemViewHolder: Presenter.ViewHolder,
            item: Any,
            rowViewHolder: RowPresenter.ViewHolder?,
            row: Row?
        ) {
            if (item is TVViewModel) {
                // 滚动过程中的抬手会被 Leanback 误派发成点击：直接忽略，避免误切台/收面板
                if (touchScrolling) {
                    Log.i(TAG, "ignore click while scrolling")
                    return
                }
                tvListViewModel.setItemPositionCurrent(item.getRowPosition())
                confirmSelect()
            }
        }
    }

    /** 等惯性滑动停下来再对齐，滑动过程中对齐会被后续滚动覆盖 */
    private fun syncSelectionWhenIdle(attempt: Int = 0) {
        val grid = findGridView(view) ?: return
        if (grid.scrollState != RecyclerView.SCROLL_STATE_IDLE && attempt < 10) {
            grid.postDelayed({ syncSelectionWhenIdle(attempt + 1) }, 200)
            return
        }
        syncSelectionToVisible()
    }

    /**
     * 把选中/焦点对齐到当前可见的第一个条目。
     * 触屏滚动时 Leanback 的选中位置不会跟着动，列表一重新布局就会按老焦点滚回去
     *（用户看到的现象就是"滑了几屏焦点又回来了"），滚动结束后调用这里把它对齐。
     */
    private fun syncSelectionToVisible() {
        val grid = findGridView(view) ?: return
        val lm = grid.layoutManager as? LinearLayoutManager ?: return
        // 优先取第一个完整可见的条目，避免选中一个被上边缘切掉一半的卡片
        val first = lm.findFirstCompletelyVisibleItemPosition()
            .takeIf { it >= 0 } ?: lm.findFirstVisibleItemPosition()
        if (first >= 0 && first != grid.selectedPosition) {
            Log.i(TAG, "sync selection to $first")
            grid.setSelectedPosition(first)
        }
    }

    /** 轻点某个条目 = 切到该频道并收起面板 */
    private fun confirmItemAt(x: Float, y: Float) {
        val grid = findGridView(view) as? RecyclerView ?: return
        val child = grid.findChildViewUnder(x, y) ?: return
        val position = grid.getChildAdapterPosition(child)
        if (position < 0) return
        tvListViewModel.setItemPositionCurrent(position)
        confirmSelect()
    }

    /**
     * 确认键切台：切到当前焦点所在频道并收起频道面板。
     * 遥控器确认键可能从 Activity.onKeyDown 和卡片点击两处触发，这里做 500ms 去重，
     * 避免面板出现「关了又开」的情况。
     */
    fun confirmSelect() {
        val now = System.currentTimeMillis()
        if (now - lastConfirmTime < 500) {
            return
        }
        lastConfirmTime = now

        val id = tvListViewModel.itemPositionCurrent.value ?: itemPosition
        if (id < 0 || id >= tvListViewModel.size()) {
            return
        }
        itemPosition = id
        tvListViewModel.setItemPosition(id)
        tvListViewModel.getTVViewModel(id)?.changed()
        (activity as? MainActivity)?.switchMainFragment()
    }

    private inner class ItemViewSelectedListener : OnItemViewSelectedListener {
        override fun onItemSelected(
            itemViewHolder: Presenter.ViewHolder?, item: Any?,
            rowViewHolder: RowPresenter.ViewHolder?, row: Row?
        ) {
            if (item is TVViewModel) {
                Log.d(TAG, "selected pos=${item.getRowPosition()} ${item.getTV().title}")
                tvListViewModel.setItemPositionCurrent(item.getRowPosition())
                // 驱动卡片高亮（卡片自身拿不到焦点状态）
                cardPresenter?.setSelected(item)
                (activity as? MainActivity)?.mainActive()
            }
        }
    }

    fun check(tvViewModel: TVViewModel): Boolean {
        val title = tvViewModel.getTV().title
        // 索引可能为 -1 或越界（换源/数据未就绪时），用 getOrNull 兜底避免直接抛异常
        val videoUrl = tvViewModel.videoIndex.value
            ?.let { tvViewModel.videoUrl.value?.getOrNull(it) }
        if (videoUrl == null || videoUrl == "") {
            Log.e(TAG, "$title videoUrl is empty")
            return false
        }

        if (videoUrl == lastVideoUrl) {
            Log.e(TAG, "$title videoUrl is duplication")
            return false
        }

        return true
    }

    fun fragmentReady() {
        tvListViewModel.getTVViewModel(itemPosition)?.changed()

        tvListViewModel.tvListViewModel.value?.forEach { tvViewModel ->
            updateEPG(tvViewModel)
        }
    }

    fun play(itemPosition: Int) {
        view?.post {
            if (itemPosition > -1 && itemPosition < tvListViewModel.size()) {
                this.itemPosition = itemPosition
                tvListViewModel.setItemPosition(itemPosition)
                tvListViewModel.getTVViewModel(itemPosition)?.changed()
            } else {
                Toast.makeText(context, "频道不存在", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun prev() {
        view?.post {
            itemPosition--
            if (itemPosition == -1) {
                itemPosition = tvListViewModel.size() - 1
            }
            tvListViewModel.setItemPosition(itemPosition)
            tvListViewModel.getTVViewModel(itemPosition)?.changed()
        }
    }

    fun next() {
        view?.post {
            itemPosition++
            if (itemPosition == tvListViewModel.size()) {
                itemPosition = 0
            }
            tvListViewModel.setItemPosition(itemPosition)
            tvListViewModel.getTVViewModel(itemPosition)?.changed()
        }
    }

    private fun updateEPG(tvViewModel: TVViewModel) {
        when (tvViewModel.getTV().programType) {
            ProgramType.Y_PROTO -> {
                Request.fetchYProtoEPG(tvViewModel)
            }

            ProgramType.Y_JCE -> {
                Request.fetchYJceEPG(tvViewModel)
            }

            ProgramType.F -> {
                Request.fetchFEPG(tvViewModel)
            }

            ProgramType.DIRECT -> {
                // 直连源：内置频道表已带播放地址，无需请求接口，暂不支持节目单
            }
        }
    }

    override fun onResume() {
        Log.i(TAG, "onResume")
        super.onResume()
    }

    override fun onStop() {
        Log.i(TAG, "onStop")
        super.onStop()
        SP.itemPosition = itemPosition
        Log.i(TAG, "$POSITION $itemPosition saved")
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        super.onDestroy()
    }

    /** 递归找网格控件，避免依赖不同 leanback 版本里不一致的资源 id */
    private fun findGridView(v: View?): androidx.leanback.widget.BaseGridView? {
        if (v is androidx.leanback.widget.BaseGridView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findGridView(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "MainFragment"
        private const val POSITION = "position"
    }
}
