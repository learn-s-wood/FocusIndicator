/**
 * @author learn-s-wood
 * @date 2026-11-18
 */
package io.github.learnswood.focusindicator.demo

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commit
import io.github.learnswood.focusindicator.FocusIndicatorLayout
import io.github.learnswood.focusindicator.demo.focus.ContentArea
import io.github.learnswood.focusindicator.demo.focus.FocusNavigator
import io.github.learnswood.focusindicator.demo.focus.FocusRegion
import io.github.learnswood.focusindicator.demo.focus.TabStrip

/**
 * 顶部 tab + 内容页的完整页面。
 *
 * 页面只有两个焦点区：顶部的 tab 区（[TabStrip]）和下面的内容区（每个 tab 一个
 * [ContentFragment]）。两个区靠 [FocusRegion] 交接：tab 往下走时向内容区要落点，
 * 内容区往上走时向 tab 要落点。两条链路的规则都在焦点层里，这里只负责接线。
 *
 * 两种焦点样式分工：焦点在 tab 上时靠色块（[FocusIndicatorLayout] 的 behind 层）；
 * 焦点移进内容区后色块淡出，改由红字（state_selected，见 color/tab_title.xml）记住当前页。
 */
@SuppressLint("RestrictedApi")
class TabGridActivity : FragmentActivity(), FocusGroupHost {

    override val focusGroup = FocusIndicatorLayout.Group()

    /** tab 标题，数量故意超过屏宽，用来看 tab 条自身的横向滚动 */
    private val tabTitles = intArrayOf(
        R.string.tab_demo_all,
        R.string.tab_demo_movie,
        R.string.tab_demo_series,
        R.string.tab_animation,
        R.string.tab_song,
        R.string.tab_english,
        R.string.tab_demo_doc,
        R.string.tab_demo_kids,
        R.string.tab_demo_music
    )

    private lateinit var contentPages: List<ContentFragment>
    private lateinit var tabStrip: TabStrip

    /** 当前显示的页 */
    private var currentIndex = 0

    /** 首次落焦点只做一次，之后焦点由用户自己控 */
    private var hasInitialFocus = false

    /**
     * 跨区焦点导航：tab 区 ↔ 内容区。
     *
     * 区内移动交给系统，跨区移动由这里接管（系统的几何搜索在两个区之间距离打平，
     * 落点不可控）。落点一律问区域要，页面只负责接线。
     */
    private val navigator = FocusNavigator(
        tabRegion = { if (::tabStrip.isInitialized) tabStrip else null },
        contentArea = { currentContentArea() }
    )

    /** 已接管的方向键，等它的抬起事件一并吃掉 */
    private var pendingKeyCode = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tab_grid)

        setupTopTabs()
        setupContentPages()
    }

    override fun tabRegion(): FocusRegion = tabStrip

    /**
     * 按键先过跨区导航：需要接管的方向键在这里就定下落点，不再交给系统去猜。
     *
     * 接管的按键把抬起事件一并吃掉：不然同一次按键的抬起阶段系统还会再做一遍
     * 几何搜索，把刚定下的落点又挪走。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            else -> 0
        }
        if (direction == 0) {
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (!navigator.handle(direction, currentFocus)) {
                return super.dispatchKeyEvent(event)
            }
            pendingKeyCode = event.keyCode
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && pendingKeyCode == event.keyCode) {
            pendingKeyCode = 0
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 窗口真正拿到焦点时把焦点交给 tab 条。
     * onCreate 里条目还没排出来，onRequestFocusInDescendants 找不到子项，
     * 所以等这一帧排完再试；成功后不再抢焦点。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !hasInitialFocus) {
            tabStrip.post {
                if (!hasInitialFocus && tabStrip.requestFocus()) hasInitialFocus = true
            }
        }
    }

    /** tab 容器负责画焦点色块，TabStrip 只管条目与选中记忆 */
    private fun setupTopTabs() {
        val container = findViewById<FocusIndicatorLayout>(R.id.fiTopTabs)
        container.setGroup(focusGroup)
        // 焦点色块：贴在 tab 下面的圆角实心块，文字是白字
        container.behind()
            .fill(accent())
            .corner(dp(8))
            .padding(0f)
        container.front().enable(false)

        tabStrip = findViewById(R.id.tabList)
        tabStrip.bind(tabTitles) { index -> showPage(index) }
    }

    /**
     * 每个 tab 一个内容页，一次性 add，之后只做 show/hide：hide() 会把 View 置为
     * GONE，GONE 的容器不参与焦点搜索，各页的滚动位置也能保留下来。
     */
    private fun setupContentPages() {
        contentPages = tabTitles.mapIndexed { index, titleRes ->
            // 四种形态轮着用，保证每个 tab 都有内容
            ContentFragment.newInstance(index % 4, getString(titleRes))
        }
        supportFragmentManager.commit {
            contentPages.forEachIndexed { index, page ->
                add(R.id.fragmentContainer, page)
                if (index > 0) hide(page)
            }
        }
    }

    /** TabStrip 只在选中项真的变了才回调，这里不用再判重 */
    private fun showPage(index: Int) {
        currentIndex = index
        supportFragmentManager.commit {
            contentPages.forEachIndexed { i, page ->
                if (i == index) show(page) else hide(page)
            }
        }
    }

    /** 当前页内容区的焦点边界 */
    private fun currentContentArea(): ContentArea? {
        if (!::contentPages.isInitialized) return null
        return contentPages.getOrNull(currentIndex)?.contentArea()
    }

    private fun accent(): Int = ContextCompat.getColor(this, R.color.demo_accent)

    private fun dp(value: Int): Float = value * resources.displayMetrics.density
}
