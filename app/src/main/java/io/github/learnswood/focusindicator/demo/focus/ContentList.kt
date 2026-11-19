/**
 * @author learn-s-wood
 * @date 2026-11-18
 */
package io.github.learnswood.focusindicator.demo.focus

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 网格 / 多列内容列表，作为内容区的区域实现。
 *
 * 只回答两件事：
 *
 * - 焦点进来落在哪：上次停在哪个 item 就回哪个（[FocusCursor] 记位置，离开时不清零）。
 * - 焦点是不是已经停在第一行：第一行再往上就该交回 tab 区了。
 *
 * 这两件事原先还带一条"第一行向上回 tab"的拦截，现已挪到 [ContentArea]，
 * 那条规则只留一处。焦点跟随居中不在这里做，交给 [CenterGridLayoutManager]。
 */
class ContentList @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr), ContentRegion {

    /** 焦点位置游标：焦点移动时更新，离开内容区时不清零 */
    private val cursor = FocusCursor()

    /** 列数，决定哪些位置算第一行 */
    private val spanCount: Int
        get() = when (val manager = layoutManager) {
            is GridLayoutManager -> manager.spanCount
            else -> 1
        }

    override fun requestChildFocus(child: View, focused: View) {
        super.requestChildFocus(child, focused)
        cursor.update(getChildAdapterPosition(child))
    }

    /**
     * 焦点进入本列表时的落点：上次停在哪儿就回哪儿；没有记忆位置时落在第一个。
     *
     * 兜底必须有，否则第一次从 tab 区进内容区时无处可落，焦点会原地不动。
     */
    override fun focusEntry(): View? {
        val position = if (cursor.hasPosition) cursor.position else 0
        val holder = findViewHolderForAdapterPosition(position)
        if (holder != null) return holder.itemView
        // 目标 item 还不在屏幕上：先把列表滚过去，后面的落点自然落在它附近
        scrollToPosition(position)
        return null
    }

    /** [view] 是不是在本区域内 */
    override fun contains(view: View): Boolean = isDescendantOf(this, view)

    /**
     * 焦点是不是停在第一行。第一行再往上就该回 tab 区了。
     */
    override fun isFocusAtTopEdge(): Boolean {
        return currentPosition() in 0 until spanCount
    }

    /** 当前焦点所在的位置；焦点不在本列表时用记住的位置 */
    private fun currentPosition(): Int {
        val focused = findFocus() ?: return cursor.position
        val item = findContainingItemView(focused) ?: return cursor.position
        val position = getChildAdapterPosition(item)
        return if (position != RecyclerView.NO_POSITION) position else cursor.position
    }
}
