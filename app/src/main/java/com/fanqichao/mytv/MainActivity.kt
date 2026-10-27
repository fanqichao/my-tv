package com.fanqichao.mytv

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.fanqichao.mytv.models.TVViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class MainActivity : FragmentActivity(), Request.RequestListener {

    private var ready = 0

    // 首次播放是否已触发，防止计数超过 6 时重复或漏触发
    private var initialPlayStarted = false
    private val playerFragment = PlayerFragment()
    private val mainFragment = MainFragment()
    private val infoFragment = InfoFragment()
    private val channelFragment = ChannelFragment()
    private var timeFragment = TimeFragment()
    private val settingFragment = SettingFragment()
    private val errorFragment = ErrorFragment()

    private var doubleBackToExitPressedOnce = false

    private lateinit var gestureDetector: GestureDetector

    private val handler = Handler()
    private val delayHideMain: Long = 10000
    private val delayHideSetting: Long = 10000

    init {
        // 校准本地时钟（与服务器时间差），EPG 的"正在直播/即将播出"依赖它
        lifecycleScope.launch(Dispatchers.IO) {
            Utils.init()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "onCreate")
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        Request.setRequestListener(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = SYSTEM_UI_FLAG_HIDE_NAVIGATION

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.main_browse_fragment, playerFragment)
                .add(R.id.main_browse_fragment, timeFragment)
                .add(R.id.main_browse_fragment, infoFragment)
                .add(R.id.main_browse_fragment, channelFragment)
                .add(R.id.main_browse_fragment, mainFragment)
                .hide(mainFragment)
                .commit()
        }
        gestureDetector = GestureDetector(this, GestureListener())

        errorFragment.buttonClickListener = View.OnClickListener {
            supportFragmentManager.beginTransaction()
                .remove(errorFragment)
                .commit()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val connectivityManager =
                getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.registerDefaultNetworkCallback(object :
                ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    Log.i(TAG, "net ${Build.VERSION.SDK_INT}")
                    if (this@MainActivity.isNetworkConnected) {
                        Log.i(TAG, "net isNetworkConnected")
                        ready++
                    }
                }
            })
        } else {
            Log.i(TAG, "net ${Build.VERSION.SDK_INT}")
            ready++
        }

    }

    fun showInfoFragment(tvViewModel: TVViewModel) {
        infoFragment.show(tvViewModel)
        if (SP.channelNum) {
            channelFragment.show(tvViewModel)
        }
    }

    private fun showChannel(channel: String) {
        if (!mainFragment.isHidden) {
            return
        }

        if (settingFragment.isVisible) {
            return
        }

        if (SP.channelNum) {
            channelFragment.show(channel)
        }
    }

    fun play(tvViewModel: TVViewModel) {
        playerFragment.play(tvViewModel)
        mainFragment.view?.requestFocus()
    }

    fun play(itemPosition: Int) {
        mainFragment.play(itemPosition)
    }

    fun prev() {
        mainFragment.prev()
    }

    fun next() {
        mainFragment.next()
    }

    fun switchMainFragment() {
        val transaction = supportFragmentManager.beginTransaction()

        if (mainFragment.isHidden) {
            transaction.show(mainFragment)
            mainActive()
        } else {
            transaction.hide(mainFragment)
        }

        transaction.commit()

        // 频道面板显示时停靠在屏幕左侧并收窄，右侧留出视频画面；收起时恢复全屏
        dockMainFragment(mainFragment.isHidden)
    }

    private fun dockMainFragment(dock: Boolean) {
        val v = mainFragment.view ?: return
        val lp = v.layoutParams as? FrameLayout.LayoutParams ?: return
        if (dock) {
            lp.width = (resources.displayMetrics.widthPixels * PANEL_WIDTH_RATIO).toInt()
            lp.gravity = Gravity.START or Gravity.TOP
        } else {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        }
        v.layoutParams = lp
    }

    fun mainActive() {
        handler.removeCallbacks(hideMain)
        handler.postDelayed(hideMain, delayHideMain)
    }

    fun settingDelayHide() {
        handler.removeCallbacks(hideSetting)
        handler.postDelayed(hideSetting, delayHideSetting)
        showTime()
    }

    fun settingHideNow() {
        handler.removeCallbacks(hideSetting)
        handler.postDelayed(hideSetting, 0)
    }

    fun settingNeverHide() {
        handler.removeCallbacks(hideSetting)
    }

    private val hideMain = Runnable {
        Log.i(TAG, "hideMain: 面板到时自动隐藏")
        if (!mainFragment.isHidden) {
            supportFragmentManager.beginTransaction().hide(mainFragment).commit()
        }
    }

    private fun mainFragmentIsHidden(): Boolean {
        return mainFragment.isHidden
    }

    private fun hideMainFragment() {
        if (!mainFragment.isHidden) {
            supportFragmentManager.beginTransaction()
                .hide(mainFragment)
                .commit()
        }
    }

    fun fragmentReady(tag: String) {
        ready++
        Log.i(TAG, "ready $tag $ready ")
        // 原逻辑是 ready == 6 精确匹配，一旦网络回调等提前把计数顶过 6，
        // 首次播放就永远不会触发（表现为开机黑屏、要手动换台）。改为 >= 且只执行一次。
        if (ready >= 6 && !initialPlayStarted) {
            initialPlayStarted = true
            mainFragment.fragmentReady()
            showTime()
        }
    }

    private fun showTime() {
        Log.i(TAG, "showTime ${SP.time}")
        if (SP.time) {
            timeFragment.show()
        } else {
            timeFragment.hide()
        }
    }

    fun isPlaying() {
        if (errorFragment.isVisible) {
            supportFragmentManager.beginTransaction()
                .remove(errorFragment)
                .commit()
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event != null) {
            gestureDetector.onTouchEvent(event)
        }
        return super.onTouchEvent(event)
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            switchMainFragment()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            showSetting()
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            // 面板隐藏时上下 fling 直接换台；面板可见时交给网格自己处理
            if (mainFragment.isHidden) {
                if (velocityY > 0) prev() else next()
            }
            return super.onFling(e1, e2, velocityX, velocityY)
        }
    }

    private fun showSetting() {
        if (!mainFragment.isHidden) {
            return
        }

        Log.i(TAG, "settingFragment ${settingFragment.isVisible}")
        if (!settingFragment.isVisible) {
            settingFragment.show(supportFragmentManager, "setting")
            settingDelayHide()
        } else {
            handler.removeCallbacks(hideSetting)
            settingFragment.dismiss()
        }
    }

    private val hideSetting = Runnable {
        if (settingFragment.isVisible) {
            settingFragment.dismiss()
        }
    }

    // 面板可见时上下键交给网格移动焦点，这里不做处理
    private fun channelUp() {
        if (mainFragment.isHidden) {
            if (SP.channelReversal) next() else prev()
        }
    }

    private fun channelDown() {
        if (mainFragment.isHidden) {
            if (SP.channelReversal) prev() else next()
        }
    }

    private fun back() {
        if (!mainFragmentIsHidden()) {
            hideMainFragment()
            return
        }

        if (doubleBackToExitPressedOnce) {
            super.onBackPressed()
            return
        }

        doubleBackToExitPressedOnce = true
        Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show()

        handler.postDelayed({
            doubleBackToExitPressedOnce = false
        }, 2000)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d(TAG, "keyCode $keyCode")
        when (keyCode) {
            // 遥控器数字键 0~9：选台号，由 ChannelFragment 组合两位数后切台
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                showChannel((keyCode - KeyEvent.KEYCODE_0).toString())
                return true
            }

            KeyEvent.KEYCODE_ESCAPE -> {
                back()
                return true
            }

            KeyEvent.KEYCODE_BACK -> {
                back()
                return true
            }

            KeyEvent.KEYCODE_BOOKMARK -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_UNKNOWN -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_HELP -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_SETTINGS -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_MENU -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_ENTER -> {
                if (!mainFragment.isHidden) {
                    // 频道面板可见：确认键 = 切换到当前焦点所在频道
                    mainFragment.confirmSelect()
                } else {
                    switchMainFragment()
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (!mainFragment.isHidden) {
                    // 频道面板可见：确认键 = 切换到当前焦点所在频道
                    mainFragment.confirmSelect()
                } else {
                    switchMainFragment()
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                channelUp()
            }

            KeyEvent.KEYCODE_CHANNEL_UP -> {
                channelUp()
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                channelDown()
            }

            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                channelDown()
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (!mainFragment.isVisible && !settingFragment.isVisible) {
                    switchMainFragment()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!mainFragment.isVisible && !settingFragment.isVisible) {
                    showSetting()
                    return true
                }
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    /**
     * ⚠️ 不要删：libnative.so 通过 JNI 反射调用本方法（GetMethodID 忽略 private）。
     * 删除会导致启动时 NoSuchMethodError 崩溃。
     */
    private fun getAppSignature() = this.appSignature

    override fun onStart() {
        Log.i(TAG, "onStart")
        super.onStart()
    }

    override fun onResume() {
        Log.i(TAG, "onResume")
        super.onResume()
        if (!mainFragment.isHidden) {
            handler.postDelayed(hideMain, delayHideMain)
        }
    }

    override fun onPause() {
        Log.i(TAG, "onPause")
        super.onPause()
        handler.removeCallbacks(hideMain)
    }

    override fun onDestroy() {
        super.onDestroy()
        Request.onDestroy()
    }

    override fun onRequestFinished(message: String?) {
        if (message != null && !errorFragment.isVisible) {
            supportFragmentManager.beginTransaction()
                .add(R.id.main_browse_fragment, errorFragment)
                .commitNow()
            errorFragment.setErrorContent(message)
        }
        fragmentReady("Request")
    }

    companion object {
        private const val TAG = "MainActivity"

        // 频道面板宽度占屏幕比例（停靠左侧）
        const val PANEL_WIDTH_RATIO = 0.21f
    }
}