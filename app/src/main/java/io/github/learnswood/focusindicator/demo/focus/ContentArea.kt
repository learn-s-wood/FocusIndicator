/**
 * @author learn-s-wood
 * @date 2026-11-19
 */
package io.github.learnswood.focusindicator.demo.focus

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * 内容区的焦点边界，焦点跨区规则的唯一落点。
 *
 * 为什么要一个专门的容器：内容区内部的形态（网格 / 货架）会变，但"从内容区往上走
 * 要回到 tab 条"这条规则不该跟着形态变。规则收在这里，形态只负责提供两件事：
 * 当前焦点在不在上边界、回到 tab 条该落哪个 view。
 *
 * 关于拦截点：焦点搜索只会真正问到 RecyclerView（它覆写了三参并委托自己的两参），
 * 普通容器拿不到搜索请求：[ViewGroup.focusSearch] 一律向上转发父级。所以形态层
 * 在搜索过程中来问这里要决策，规则本身仍然只有这一份。
 */
class ContentArea @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 当前绑定的内容区实现 */
    private var region: ContentRegion? = null

    /** 从内容区往上离开时的落点提供者（返回 tab 区的落点） */
    var upEntry: (() -> View?)? = null

    /** 绑定内容区实现 */
    fun bind(region: ContentRegion) {
        this.region = region
    }

    /** 当前绑定的内容区实现 */
    fun region(): ContentRegion? = region

    /** [view] 是不是在本内容区内 */
    fun contains(view: View): Boolean = isDescendantOf(this, view)

    /**
     * 内容区形态在焦点搜索过程中来问：往上走要不要交给 tab 区，交给哪个 view。
     *
     * 返回 null 表示按默认逻辑继续（几何搜索找同一形态里的候选）。
     */
    fun upTarget(region: ContentRegion): View? {
        if (!region.isFocusAtTopEdge()) return null
        return upEntry?.invoke()
    }

    override fun onRequestFocusInDescendants(
        direction: Int,
        previouslyFocusedRect: android.graphics.Rect?
    ): Boolean {
        val entry = region?.focusEntry()
        if (entry != null && entry.requestFocus(direction, previouslyFocusedRect)) {
            return true
        }
        return super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
    }
}
