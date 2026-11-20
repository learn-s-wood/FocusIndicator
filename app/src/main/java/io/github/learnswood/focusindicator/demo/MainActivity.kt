/**
 * @author learn-s-wood
 * @date 2026-11-17
 */
package io.github.learnswood.focusindicator.demo

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.TransitionDrawable
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.learnswood.focusindicator.FocusIndicatorLayout

/**
 * 焦点飞框 Demo 页：底色滑动、描边变形、双层组合、焦点放大贴合、RecyclerView 网格五场景。
 * 各场景验证点见对应 setup 方法注释与 README 预览。
 */
class MainActivity : Activity() {

    /**
     * 五个演示区共享的接力组。焦点上下换行时，飞框会从上一行飞过去而不是闪现；
     * 不给它们设同一个 Group 的话，跨行移动只能各管各的。
     */
    private val focusGroup = FocusIndicatorLayout.Group()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupTabGridDemo()
        setupColorBlockDemo()
        setupStrokeDemo()
        setupComboDemo()
        setupScaleDemo()
        setupGridDemo()

        findViewById<View>(R.id.btnTabGridDemo).setOnClickListener {
            startActivity(Intent(this, TabGridActivity::class.java))
        }

        // 默认焦点落在第一个 tab 上
        val firstTab = findViewById<View>(R.id.tab1)
        firstTab.post { firstTab.requestFocus() }
    }

    private fun setupTabGridDemo() {
        val tabGridDemo = findViewById<FocusIndicatorLayout>(R.id.tabGridHome)
        tabGridDemo.setGroup(focusGroup)
        tabGridDemo.behind()
            .fill(COLOR_ACCENT)
            .corner(dp(3))
            .padding(0f)
        tabGridDemo.front().enable(false)
    }

    /**
     * 场景 1：同尺寸 tab，圆角实心底色在 item 下层滑动。
     * 底色层必须放 [FocusIndicatorLayout.behind]：一旦放到 front 就会盖住 item 文字。
     */
    private fun setupColorBlockDemo() {
        val container = findViewById<FocusIndicatorLayout>(R.id.fiTabs)
        container.setGroup(focusGroup)
        container.behind()
            .fill(COLOR_ACCENT)
            .corner(dp(18))
            .padding(0f)
        // 顶层没有配置任何样式，保持关闭状态：纯色块效果
        container.front().enable(false)

        // 豁免项的焦点态由 item 自己画，这里给它也加上过渡：飞框淡出的同一段时间里
        // 色块渐亮，两边的节奏才连得上。不用 selector 的原因见 bg_tab_own_focus.xml
        val tab4 = findViewById<View>(R.id.tab4)
        tab4.setOnFocusChangeListener { view, hasFocus ->
            val background = view.background as? TransitionDrawable ?: return@setOnFocusChangeListener
            if (hasFocus) {
                background.startTransition(FOCUS_TRANSITION_DURATION)
            } else {
                background.reverseTransition(FOCUS_TRANSITION_DURATION)
            }
        }
    }

    /**
     * 场景 2：不同尺寸 item + 描边飞框。
     * item 尺寸差异越大，"变形"越明显；尺寸进度相对位移滞后，观感更顺。
     */
    private fun setupStrokeDemo() {
        val container = findViewById<FocusIndicatorLayout>(R.id.fiCards)
        container.setGroup(focusGroup)
        container.front()
            .stroke(Color.WHITE, dp(3))
            .corner(dp(10))
            .padding(dp(6))
    }

    /**
     * 场景 3：底色层 + 描边层组合，两层同时做位移与尺寸变形。
     */
    private fun setupComboDemo() {
        val container = findViewById<FocusIndicatorLayout>(R.id.fiCombo)
        container.setGroup(focusGroup)
        // 底层实心色块（不透明，item 文字绘制在其上方）
        container.behind()
            .fill(COLOR_ACCENT)
            .corner(dp(8))
            .padding(0f)
        // 顶层白色描边，外扩一圈
        container.front()
            .stroke(Color.WHITE, dp(3))
            .corner(dp(12))
            .padding(dp(8))
    }

    /**
     * 场景 4：item 焦点态放大，验证飞框贴住放大后的外形。
     * 放大动画在 item 自己的焦点监听里启动，靠 setTrackTargetLayout(true) 逐帧重采追平；
     * 改成 false 可见框比 item 小一圈。
     */
    private fun setupScaleDemo() {
        val container = findViewById<FocusIndicatorLayout>(R.id.fiScale)
        container.setGroup(focusGroup)
        // padding(0)：框正好压在 item 边界上，有没有贴合放大后的大小一眼就能看出来
        container.front()
            .stroke(Color.WHITE, dp(3))
            .corner(dp(8))
            .padding(0f)

        intArrayOf(R.id.scale1, R.id.scale2, R.id.scale3, R.id.scale4, R.id.scale5)
            .forEach { id ->
                findViewById<View>(id).setOnFocusChangeListener { view, hasFocus ->
                    // View.setScaleX 默认以 item 中心为 pivot；飞框走 getLocationInWindow，
                    // 任意 pivot 都能贴住，不需要业务方额外配置
                    val scale = if (hasFocus) SCALE_FACTOR else 1f
                    view.animate()
                        .scaleX(scale)
                        .scaleY(scale)
                        .setDuration(SCALE_DURATION)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
            }
    }

    /**
     * 场景 5：RecyclerView 网格。item 自带圆角背景，故只开顶层描边 + 外发光。
     * 故意不调 corner()：自动读卡片 10dp 圆角再叠 2dp 外扩；容器自动跟踪复用 item。
     */
    private fun setupGridDemo() {
        val container = findViewById<FocusIndicatorLayout>(R.id.fiGrid)
        container.setGroup(focusGroup)
        container.front()
            .stroke(COLOR_ACCENT, dp(3))
            .glow(Color.argb(0x66, 0xFF, 0x50, 0x00), dp(4))
            .padding(dp(2))

        val grid = findViewById<RecyclerView>(R.id.gridList)
        grid.layoutManager = GridLayoutManager(this, GRID_SPAN_COUNT)
        grid.adapter = GridAdapter(List(GRID_ITEM_COUNT) { position -> "卡片 ${position + 1}" })
    }

    private fun dp(value: Int): Float = value * resources.displayMetrics.density

    private companion object {
        /** 指示器主色 */
        const val COLOR_ACCENT = 0xFFFF5000.toInt()

        /** 场景 4：item 获得焦点时的放大倍数 */
        const val SCALE_FACTOR = 1.2f

        /**
         * 场景 4：放大动画时长。
         * 故意比飞框默认的 220ms 长，用来暴露框跟不上 item 的时序问题。
         */
        const val SCALE_DURATION = 300L

        /**
         * 场景 1：英语 tab 自带焦点态的淡入淡出时长。
         * 和飞框淡出的 140ms 取同一量级，两段过渡才能接上。
         */
        const val FOCUS_TRANSITION_DURATION = 120

        /** 场景 5：网格列数与 item 数量（三行） */
        const val GRID_SPAN_COUNT = 4
        const val GRID_ITEM_COUNT = 12
    }
}
