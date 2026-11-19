/**
 * @author learn-s-wood
 * @date 2026-11-18
 */
package io.github.learnswood.focusindicator.demo.focus

import android.content.Context
import android.util.AttributeSet
import android.view.FocusFinder
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * 一行可以横向滚动的列表，电视上最常见的形态。
 *
 * 自带 [CenterLayoutManager]：焦点项始终停在行中间；左右走到头就停在本行，
 * 不交给几何搜索去猜邻行，否则会跳到别的行的某个 item 上。
 *
 * 行内焦点位置记在 [FocusCursor] 里，供 [ShelfStack] 恢复焦点时用；
 * 焦点进了哪一行由 [onFocusEntered] 上报给 [ShelfStack]。
 */
class ShelfRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    /** 焦点位置游标：焦点移动时更新，离开内容区时不清零 */
    private val cursor = FocusCursor()

    init {
        layoutManager = CenterLayoutManager(context, HORIZONTAL, false)
        itemAnimator = null
        overScrollMode = OVER_SCROLL_NEVER
        isFocusable = false
    }

    override fun requestChildFocus(child: View, focused: View) {
        super.requestChildFocus(child, focused)
        cursor.update(getChildAdapterPosition(child))
        // 焦点落在哪一行，交给所在的货架记住
        (parent as? ShelfStack)?.onFocusEntered(this)
    }

    /**
     * 焦点进入本行时的落点：上次停在哪儿就回哪儿；没有记忆位置时落在第一个。
     *
     * 兜底必须有，否则第一次从 tab 区进内容区时无处可落，焦点会原地不动。
     */
    fun focusEntry(): View? {
        val position = if (cursor.hasPosition) cursor.position else 0
        val holder = findViewHolderForAdapterPosition(position)
        if (holder != null) return holder.itemView
        // 目标 item 还不在屏幕上：先把这一行滚过去，后面的落点自然落在它附近
        scrollToPosition(position)
        return null
    }

    /**
     * 焦点搜索冒泡到这一层。
     *
     * 判据用 [findFocus] 拿真实焦点，不依赖传进来的 focused 参数：搜索链上每一层
     * 传下来的 focused 语义并不统一（可能为 null），拿真实焦点才靠得住。
     */
    override fun focusSearch(focused: View?, direction: Int): View? {
        val current = focused ?: findFocus()
        if (current == null || !isDescendantOf(this, current)) {
            // 焦点不在本行里：把搜索交回父级继续冒泡。
            // RecyclerView 自己的实现假定 focused 是它的子项，坐标换算会算到别的条目上
            return parent?.focusSearch(current, direction)
        }
        if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) {
            return FocusFinder.getInstance().findNextFocus(this, current, direction) ?: current
        }
        // 其余方向交给列表自己做几何搜索；上下跨区由 FocusNavigator 接管
        return super.focusSearch(focused, direction)
    }
}
