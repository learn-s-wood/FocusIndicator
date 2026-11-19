/**
 * @author learn-s-wood
 * @date 2026-11-18
 */
package io.github.learnswood.focusindicator.demo.focus

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 让焦点项停在视口中间的 LayoutManager
 *
 * 方向由 orientation 决定，tab 条、横向行、垂直网格都用它
 * 滚动距离钳在内容范围内，首项和末项不会被强行居中而露出空白
 */
open class CenterLayoutManager @JvmOverloads constructor(
    context: Context,
    orientation: Int = RecyclerView.VERTICAL,
    reverseLayout: Boolean = false
) : LinearLayoutManager(context, orientation, reverseLayout), Centerable {

    override fun requestChildRectangleOnScreen(
        recyclerView: RecyclerView,
        child: View,
        rect: Rect,
        immediate: Boolean,
        focusedChildVisible: Boolean
    ): Boolean = centerChild(recyclerView, child, orientation == HORIZONTAL)

    override fun centerOn(recyclerView: RecyclerView, child: View): Boolean =
        centerChild(recyclerView, child, orientation == HORIZONTAL)

    override fun centerShiftOf(recyclerView: RecyclerView, child: View): CenterShift {
        val horizontal = orientation == HORIZONTAL
        val delta = centerDelta(recyclerView, child, horizontal)
        return if (horizontal) CenterShift(-delta, 0) else CenterShift(0, -delta)
    }
}

/** 网格版：居中逻辑同上，只是多带一个列数 */
open class CenterGridLayoutManager @JvmOverloads constructor(
    context: Context,
    spanCount: Int,
    orientation: Int = RecyclerView.VERTICAL,
    reverseLayout: Boolean = false
) : GridLayoutManager(context, spanCount, orientation, reverseLayout), Centerable {

    override fun requestChildRectangleOnScreen(
        recyclerView: RecyclerView,
        child: View,
        rect: Rect,
        immediate: Boolean,
        focusedChildVisible: Boolean
    ): Boolean = centerChild(recyclerView, child, orientation == HORIZONTAL)

    override fun centerOn(recyclerView: RecyclerView, child: View): Boolean =
        centerChild(recyclerView, child, orientation == HORIZONTAL)

    override fun centerShiftOf(recyclerView: RecyclerView, child: View): CenterShift {
        val horizontal = orientation == HORIZONTAL
        val delta = centerDelta(recyclerView, child, horizontal)
        return if (horizontal) CenterShift(-delta, 0) else CenterShift(0, -delta)
    }
}

/**
 * 能把某个子项滚到视口中心的布局管理器。
 *
 * [ContentFollow] 停稳后会核对焦点项到底在不在中心，不在就靠这个接口补一次居中。
 */
interface Centerable {
    /** 把 [child] 滚到视口中心，返回是否发起了滚动 */
    fun centerOn(recyclerView: RecyclerView, child: View): Boolean

    /** 把 [child] 滚到视口中心后，它的位置会平移多少 */
    fun centerShiftOf(recyclerView: RecyclerView, child: View): CenterShift
}

/**
 * 居中滚动带来的位置平移，按容器坐标分轴。
 *
 * 正数往右 / 往下。
 */
data class CenterShift(val dx: Int, val dy: Int)

/**
 * 把 [child] 挪到视口正中实际要滚的距离（像素），滚不动返回 0。
 *
 * 距离受内容范围钳住：列表到头时焦点项只能停在边上，滚不动就是 0。
 */
private fun centerDelta(recyclerView: RecyclerView, child: View, horizontal: Boolean): Int {
    val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return 0
    // 排版过程中改滚动位置会打乱这一帧，交给正常流程
    // 这里不能改成立即滚动或者排到下一帧重试：滚动时序一变，
    // 连续按键时几次居中请求互相打断，焦点项会停在半路上
    if (recyclerView.isComputingLayout) return 0

    val childCenter: Int
    val viewportCenter: Int
    if (horizontal) {
        childCenter = (manager.getDecoratedLeft(child) + manager.getDecoratedRight(child)) / 2
        viewportCenter = (recyclerView.paddingLeft + recyclerView.width - recyclerView.paddingRight) / 2
    } else {
        childCenter = (manager.getDecoratedTop(child) + manager.getDecoratedBottom(child)) / 2
        viewportCenter = (recyclerView.paddingTop + recyclerView.height - recyclerView.paddingBottom) / 2
    }

    val delta = childCenter - viewportCenter
    if (delta == 0) return 0

    val offset = if (horizontal) {
        recyclerView.computeHorizontalScrollOffset()
    } else {
        recyclerView.computeVerticalScrollOffset()
    }
    val range = if (horizontal) {
        recyclerView.computeHorizontalScrollRange()
    } else {
        recyclerView.computeVerticalScrollRange()
    }
    val extent = if (horizontal) {
        recyclerView.computeHorizontalScrollExtent()
    } else {
        recyclerView.computeVerticalScrollExtent()
    }
    val min = -offset
    val max = range - extent - offset
    if (max < min) return 0
    return delta.coerceIn(min, max)
}

/**
 * 把 [child] 挪到视口正中。
 *
 * 返回是否发起了滚动；越界的部分在 [centerDelta] 里已经钳过。
 */
private fun centerChild(recyclerView: RecyclerView, child: View, horizontal: Boolean): Boolean {
    val delta = centerDelta(recyclerView, child, horizontal)
    if (delta == 0) return false
    if (horizontal) {
        recyclerView.smoothScrollBy(delta, 0)
    } else {
        recyclerView.smoothScrollBy(0, delta)
    }
    return true
}
