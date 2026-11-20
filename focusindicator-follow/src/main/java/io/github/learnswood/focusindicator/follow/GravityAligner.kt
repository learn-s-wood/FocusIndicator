/**
 * @author learn-s-wood
 * @date 2026-11-20
 */
package io.github.learnswood.focusindicator.follow

import android.view.View
import androidx.recyclerview.widget.RecyclerView

/** 滚动轴上的对齐重力：起边 / 正中 / 末边 */
enum class Gravity { START, CENTER, END }

/**
 * 普通 RecyclerView 的重力安置：焦点项对应边/中心滚到视口 keyline，框同时飞向落点。
 * START/END 对齐边、CENTER 对齐中心，marginPx 从重力边向内量；位移按内容范围钳制。
 * 滚动由 smoothScrollBy 驱动，不需自定义 LayoutManager；到头钳 0 不强行安置。
 *
 * @param horizontal true 横向，false 纵向（含多列网格，只安置滚动轴）
 * @param gravity 对齐重力
 * @param marginPx 距重力边的边距，CENTER 时为相对中心的有符号偏移
 */
class GravityAligner(
    private val horizontal: Boolean,
    private val gravity: Gravity = Gravity.CENTER,
    private val marginPx: Int = 0
) : ListAligner {

    override fun shiftOf(list: RecyclerView, child: View): AlignShift {
        val delta = alignDelta(list, child)
        return if (horizontal) AlignShift(-delta, 0) else AlignShift(0, -delta)
    }

    override fun align(list: RecyclerView, child: View): Boolean {
        val delta = alignDelta(list, child)
        if (delta == 0) return false
        if (horizontal) list.smoothScrollBy(delta, 0) else list.smoothScrollBy(0, delta)
        return true
    }

    override fun offsetOf(list: RecyclerView, child: View): Int =
        anchor(child) - keyline(list)

    override fun canScrollToward(list: RecyclerView, offset: Int): Boolean {
        val direction = if (offset > 0) 1 else -1
        return if (horizontal) list.canScrollHorizontally(direction)
        else list.canScrollVertically(direction)
    }

    /** 把焦点项安置到位实际要滚的距离，受内容范围钳制，滚不动返回 0 */
    private fun alignDelta(list: RecyclerView, child: View): Int {
        // 排版过程中改滚动位置会打乱这一帧，交给正常流程；不能改成立即滚或排下一帧重试，
        // 滚动时序一变连续按键时几次安置请求互相打断，焦点项会停在半路
        if (list.isComputingLayout) return 0
        val delta = anchor(child) - keyline(list)
        if (delta == 0) return 0
        val min = -scrollOffset(list)
        val max = scrollRange(list) - scrollExtent(list) - scrollOffset(list)
        if (max < min) return 0
        return delta.coerceIn(min, max)
    }

    /** 焦点项上参与对齐的那条边或中心 */
    private fun anchor(child: View): Int = when (gravity) {
        Gravity.START -> if (horizontal) child.left else child.top
        Gravity.CENTER -> if (horizontal) (child.left + child.right) / 2 else (child.top + child.bottom) / 2
        Gravity.END -> if (horizontal) child.right else child.bottom
    }

    /** 视口上的对齐目标位置 */
    private fun keyline(list: RecyclerView): Int {
        val start = if (horizontal) list.paddingLeft else list.paddingTop
        val end = if (horizontal) list.width - list.paddingRight else list.height - list.paddingBottom
        return when (gravity) {
            Gravity.START -> start + marginPx
            Gravity.CENTER -> (start + end) / 2 + marginPx
            Gravity.END -> end - marginPx
        }
    }

    private fun scrollOffset(list: RecyclerView): Int =
        if (horizontal) list.computeHorizontalScrollOffset() else list.computeVerticalScrollOffset()

    private fun scrollRange(list: RecyclerView): Int =
        if (horizontal) list.computeHorizontalScrollRange() else list.computeVerticalScrollRange()

    private fun scrollExtent(list: RecyclerView): Int =
        if (horizontal) list.computeHorizontalScrollExtent() else list.computeVerticalScrollExtent()

    companion object {
        /** 居左：焦点项左边停在距左边距 marginPx 处 */
        fun left(marginPx: Int = 0) = GravityAligner(true, Gravity.START, marginPx)

        /** 居右：焦点项右边停在距右边距 marginPx 处 */
        fun right(marginPx: Int = 0) = GravityAligner(true, Gravity.END, marginPx)

        /** 居上：焦点项上边停在距上边距 marginPx 处 */
        fun top(marginPx: Int = 0) = GravityAligner(false, Gravity.START, marginPx)

        /** 居下：焦点项下边停在距下边距 marginPx 处 */
        fun bottom(marginPx: Int = 0) = GravityAligner(false, Gravity.END, marginPx)

        /** 居中：焦点项中心停在视口中心加 marginPx 处 */
        fun center(horizontal: Boolean, marginPx: Int = 0) =
            GravityAligner(horizontal, Gravity.CENTER, marginPx)
    }
}

