/**
 * @author learn-s-wood
 * @date 2026-11-18
 */
package io.github.learnswood.focusindicator.demo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import io.github.learnswood.focusindicator.FocusIndicatorLayout
import io.github.learnswood.focusindicator.demo.focus.CenterGridLayoutManager
import io.github.learnswood.focusindicator.demo.focus.CenterLayoutManager
import io.github.learnswood.focusindicator.demo.focus.ContentArea
import io.github.learnswood.focusindicator.demo.focus.ContentList
import io.github.learnswood.focusindicator.demo.focus.ContentRegion
import io.github.learnswood.focusindicator.demo.focus.ShelfRow
import io.github.learnswood.focusindicator.demo.focus.ShelfStack

/**
 * 一个 tab 的内容页，三种形态共用：
 *
 * - [MODE_GRID]：四列网格
 * - [MODE_ROWS]：两列大卡片
 * - [MODE_SHELF]：三行横向列表，电视上最常见的那种
 * - [MODE_MIXED]：单列混排尺寸
 *
 * 每个实例自带 [FocusIndicatorLayout] 容器，Group 由宿主提供。
 *
 * 这里只做装配：形态内容绑数据，内容区实现挂到 [ContentArea] 上，焦点怎么交接
 * 由焦点层自己决定。
 */
class ContentFragment : Fragment() {

    private val focusGroup: FocusIndicatorLayout.Group
        get() = (requireActivity() as FocusGroupHost).focusGroup

    /** tab 的名字，用来拼卡片的标题，让每页的假数据能区分开 */
    private val pageName: String
        get() = requireArguments().getString(ARG_NAME).orEmpty()

    private val mode: Int
        get() = requireArguments().getInt(ARG_MODE)

    /** 内容区的跟随控制器：滚动期间冻结飞框，等滚停再对齐 */
    private var follow: ContentFollow? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(
        if (mode == MODE_SHELF) R.layout.fragment_shelf else R.layout.fragment_content,
        container,
        false
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val content = view as FocusIndicatorLayout
        content.setGroup(focusGroup)
        content.front()
            .stroke(accent(), dp(3))
            .glow(glow(), dp(4))
            .padding(dp(2))

        when (mode) {
            MODE_SHELF -> bindShelf(content)
            MODE_ROWS -> bindList(content, ROW_SPAN, ROW_COUNT, ROW_ITEM_HEIGHT_DP)
            MODE_MIXED -> bindMixed(content)
            else -> bindList(content, GRID_SPAN, GRID_COUNT, GRID_ITEM_HEIGHT_DP)
        }
    }

    override fun onDestroyView() {
        follow?.detach()
        follow = null
        super.onDestroyView()
    }

    /** 本页内容区的焦点边界，供宿主导航时取落点 */
    fun contentArea(): ContentArea? = view?.findViewById(R.id.contentArea)

    /** 网格 / 两列大卡：一个列表 */
    private fun bindList(content: FocusIndicatorLayout, spanCount: Int, count: Int, heightDp: Int) {
        val list = content.findViewById<ContentList>(R.id.contentList)
        list.layoutManager = CenterGridLayoutManager(requireContext(), spanCount)
        list.adapter = ContentAdapter(List(count) { i ->
            // 网格页放一张豁免卡：验证 followToOffset 落到豁免项时滑过去淡出、不钉在旧落点
            if (spanCount == GRID_SPAN && i == GRID_IGNORED_INDEX) {
                ContentCard("豁免卡", "飞框不套·文字变色", heightDp, ignored = true)
            } else {
                ContentCard("$pageName ${i + 1}", "片长 ${78 + i * 3} 分钟", heightDp)
            }
        })
        bindArea(content, list)
        follow = ContentFollow(content, listOf(list)).also { it.attach() }
    }

    /** 多行横向列表：每行自己横向滚 */
    private fun bindShelf(content: FocusIndicatorLayout) {
        val stack = content.findViewById<ShelfStack>(R.id.shelfStack)
        val rows = SHELF_ROW_IDS.map { content.findViewById<ShelfRow>(it) }
        rows.forEachIndexed { rowIndex, row ->
            val label = SHELF_ROW_LABELS[rowIndex]
            row.adapter = ContentAdapter(List(SHELF_ITEM_COUNT) { i ->
                val size = SHELF_ITEM_SIZES[i % SHELF_ITEM_SIZES.size]
                ContentCard(
                    "$label ${i + 1}",
                    "第 ${i + 1} 集",
                    heightDp = size[1],
                    widthDp = size[0],
                    chips = if (rowIndex == 0 && i == SHELF_CHIPS_INDEX) SHELF_CHIPS else emptyList()
                )
            })
        }
        bindArea(content, stack)
        // 三行共用一个容器：只要还有一行在滚，框就继续等着
        follow = ContentFollow(content, rows).also { it.attach() }
    }

    /**
     * 单列混排尺寸
     *
     * 相邻 item 高度不一样，焦点换过去时框停在落点上，只改尺寸不改位置
     */
    private fun bindMixed(content: FocusIndicatorLayout) {
        val list = content.findViewById<ContentList>(R.id.contentList)
        list.layoutManager = CenterLayoutManager(requireContext())
        list.adapter = ContentAdapter(
            List(MIXED_COUNT) { i ->
                val height = MIXED_HEIGHTS[i % MIXED_HEIGHTS.size]
                ContentCard("$pageName ${i + 1}", "高 ${height}dp", heightDp = height)
            }
        )
        bindArea(content, list)
        follow = ContentFollow(content, listOf(list)).also { it.attach() }
    }

    /**
     * 把内容区实现挂到边界容器上，并把"往上回到 tab"的落点接到宿主。
     *
     * 规则本身在 [ContentArea] 里，这里只是把两边接起来。
     */
    private fun bindArea(content: FocusIndicatorLayout, region: ContentRegion) {
        val area = content.findViewById<ContentArea>(R.id.contentArea)
        area.bind(region)
        area.upEntry = { (requireActivity() as FocusGroupHost).tabRegion().focusEntry() }
    }

    private fun accent(): Int = ContextCompat.getColor(requireContext(), R.color.demo_accent)

    private fun glow(): Int = ContextCompat.getColor(requireContext(), R.color.demo_accent_glow)

    private fun dp(value: Int): Float = value * resources.displayMetrics.density

    companion object {
        const val MODE_GRID = 0
        const val MODE_SHELF = 1
        const val MODE_ROWS = 2
        const val MODE_MIXED = 3

        private const val ARG_MODE = "mode"
        private const val ARG_NAME = "name"

        /** 四列网格，48 个够滚四屏，用来观察居中 */
        private const val GRID_SPAN = 4
        private const val GRID_COUNT = 48
        private const val GRID_ITEM_HEIGHT_DP = 62

        /** 网格里豁免卡的位置：第二行中段，焦点移过去列表会滚，便于观察落点固定后的豁免交接 */
        private const val GRID_IGNORED_INDEX = 6

        /** 两列大卡片，30 个能滚十五行 */
        private const val ROW_SPAN = 2
        private const val ROW_COUNT = 30
        private const val ROW_ITEM_HEIGHT_DP = 84

        /** 三行横向列表，每行 24 个，横向要滚七八屏 */
        private const val SHELF_ITEM_COUNT = 24

        /**
         * 横向列表里 item 的尺寸，按数组循环
         *
         * 宽高都不一样，焦点横向移动时框要跟着换形状，列表同时横向滚
         */
        private val SHELF_ITEM_SIZES = arrayOf(
            intArrayOf(120, 44),
            intArrayOf(180, 64),
            intArrayOf(140, 52),
            intArrayOf(210, 72)
        )

        /**
         * 带两个可聚焦子按钮的 item 位置
         *
         * 放在列表中段而不是第一个：第一个 item 停在起点，焦点移过去列表不滚，验证不到滚动
         */
        private const val SHELF_CHIPS_INDEX = 5

        /**
         * 横向列表里 item 内的两个可聚焦子按钮
         *
         * 两个按钮宽高都不一样：焦点在两者之间移动时，框要跟着换尺寸
         */
        private val SHELF_CHIPS = listOf("播放", "详情")

        /** 混排形态：单列 24 个，高度按数组循环 */
        private const val MIXED_COUNT = 24
        private val MIXED_HEIGHTS = intArrayOf(56, 96, 136, 76)
        private val SHELF_ROW_IDS = intArrayOf(R.id.shelfRow1, R.id.shelfRow2, R.id.shelfRow3)
        private val SHELF_ROW_LABELS = arrayOf("热播", "新片", "经典")

        fun newInstance(mode: Int, name: String): ContentFragment = ContentFragment().apply {
            arguments = Bundle().apply {
                putInt(ARG_MODE, mode)
                putString(ARG_NAME, name)
            }
        }
    }
}
