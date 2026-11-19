/**
 * @author learn-s-wood
 * @date 2026-11-18
 */
package io.github.learnswood.focusindicator.demo.focus

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout

/**
 * 若干 [ShelfRow] 的纵向容器，作为内容区的区域实现。
 *
 * 只回答两件事：
 *
 * - 焦点进来落在哪：上次停在哪一行，那一行再按它记住的位置接住。
 * - 焦点是不是已经停在第一行：第一行再往上就该交回 tab 区了。
 *
 * "第一行向上回 tab"这条规则本身不在这里，在 [ContentArea]：内容区形态会变，
 * 规则不该跟着变。行与行之间的上下移动也不用管，FocusFinder 在容器里按几何位置
 * 找最近的 item。
 */
class ShelfStack @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr), ContentRegion {

    /** 焦点当前所在的行 */
    private var focusedRowIndex = 0

    /** 由 [ShelfRow] 上报：焦点进了哪一行 */
    fun onFocusEntered(row: ShelfRow) {
        val index = indexOfChild(row)
        if (index >= 0) {
            focusedRowIndex = index
        }
    }

    /**
     * 焦点进入本货架时的落点：上次停的那一行，由那一行给出具体落点。
     */
    override fun focusEntry(): View? {
        val row = getChildAt(focusedRowIndex) as? ShelfRow ?: return null
        return row.focusEntry()
    }

    /** [view] 是不是在本区域内 */
    override fun contains(view: View): Boolean = isDescendantOf(this, view)

    /**
     * 焦点是不是停在第一行。第一行再往上就该回 tab 区了。
     */
    override fun isFocusAtTopEdge(): Boolean {
        val first = getChildAt(0) ?: return false
        val focused = findFocus() ?: return false
        return isDescendantOf(first, focused)
    }
}
