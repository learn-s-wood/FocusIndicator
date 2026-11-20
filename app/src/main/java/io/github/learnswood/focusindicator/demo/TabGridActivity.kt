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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.learnswood.focusindicator.FocusIndicatorLayout
import io.github.learnswood.focusindicator.demo.focus.FocusHelper
import io.github.learnswood.focusindicator.demo.focus.TabAdapter
import io.github.learnswood.focusindicator.follow.AlignerProvider
import io.github.learnswood.focusindicator.follow.FollowController
import io.github.learnswood.focusindicator.follow.GravityAligner

/**
 * 顶部 tab + 内容页。焦点策略全在 [FocusHelper]，tab 与列表均纯 RecyclerView。
 * 焦点样式分工：tab 上靠 behind 色块，进内容区后色块淡出、改红字记住当前页。
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
        R.string.tab_demo_music,
        R.string.tab_demo_variety,
        R.string.tab_demo_sports,
        R.string.tab_demo_news,
        R.string.tab_demo_finance,
        R.string.tab_demo_edu,
        R.string.tab_demo_opera,
        R.string.tab_demo_travel,
        R.string.tab_demo_food,
        R.string.tab_demo_tech
    )

    private lateinit var contentPages: List<ContentFragment>
    private lateinit var tabList: RecyclerView
    private lateinit var helper: FocusHelper

    /** tab 条的居中跟随控制器 */
    private var tabFollow: FollowController? = null

    /** 当前显示的页 */
    private var currentIndex = 0

    /** 首次落焦点只做一次，之后焦点由用户自己控 */
    private var hasInitialFocus = false

    /** 已接管的方向键，等它的抬起事件一并吃掉 */
    private var pendingKeyCode = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tab_grid)

        setupTopTabs()
        setupContentPages()
    }

    override fun focusHelper(): FocusHelper = helper

    /**
     * 四方向键先过 helper 定落点；接管的按键连抬起一起吃掉，
     * 否则抬起阶段系统再几何搜索会把落点挪走。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            else -> 0
        }
        if (direction == 0) {
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (!helper.handle(direction, currentFocus)) {
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
     * onCreate 里条目还没排出来，等这一帧排完再试；成功后不再抢焦点。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !hasInitialFocus) {
            tabList.post {
                if (!hasInitialFocus && helper.enter(tabList)) hasInitialFocus = true
            }
        }
    }

    override fun onDestroy() {
        tabFollow?.detach()
        tabFollow = null
        helper.detach()
        super.onDestroy()
    }

    /** tab 容器负责画焦点色块，tabList 是纯列表 */
    private fun setupTopTabs() {
        val container = findViewById<FocusIndicatorLayout>(R.id.fiTopTabs)
        container.setGroup(focusGroup)
        // 焦点色块：贴在 tab 下面的圆角实心块，文字是白字
        container.behind()
            .fill(accent())
            .corner(dp(8))
            .padding(0f)
        container.front().enable(false)

        tabList = findViewById(R.id.tabList)
        tabList.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        // 选中态只是换文字颜色，重新绑定不需要动画
        tabList.itemAnimator = null
        tabList.adapter = TabAdapter(tabTitles) { index -> selectTab(index) }

        helper = FocusHelper(tabList)
        helper.attach()

        // tab 也走居中跟随：焦点 tab 停在条中间，条自己滚
        tabFollow = FollowController(container, listOf(tabList), AlignerProvider { GravityAligner.center(horizontal = true) })
            .also { it.attach() }
    }

    /** 选中某个 tab：先更新红字再切页，同位置不重复切 */
    private fun selectTab(index: Int) {
        val adapter = tabList.adapter as? TabAdapter ?: return
        if (index == adapter.selectedIndex) return
        adapter.select(index)
        showPage(index)
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
        supportFragmentManager.executePendingTransactions()
        bindContent(0)
    }

    private fun showPage(index: Int) {
        currentIndex = index
        supportFragmentManager.commit {
            contentPages.forEachIndexed { i, page ->
                if (i == index) show(page) else hide(page)
            }
        }
        bindContent(index)
    }

    /** 把当前页的内容区装配交给 helper */
    private fun bindContent(index: Int) {
        val spec = contentPages.getOrNull(index)?.focusSpec() ?: return
        helper.bindContent(spec)
    }

    private fun accent(): Int = ContextCompat.getColor(this, R.color.demo_accent)

    private fun dp(value: Int): Float = value * resources.displayMetrics.density
}