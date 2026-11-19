/**
 * @author learn-s-wood
 * @date 2026-11-19
 */
package io.github.learnswood.focusindicator.demo.focus

import android.view.View

/**
 * 可以被焦点进入的区域。
 *
 * 区域只回答两个问题：焦点进到我这儿时该落在哪个 view 上；某个 view 是不是在我
 * 这个区域内。不落在几何搜索上：tab 条与卡片在垂直方向上距离完全打平，几何搜索
 * 的落点由遍历顺序决定，还会顺带把页面切走。
 *
 * 这个页面上焦点只跨两个区：tab 区与内容区。两边靠这套约定交接：
 * tab 区向下离开时问内容区要落点，内容区向上离开时问 tab 区要落点。
 */
interface FocusRegion {
    /**
     * 焦点进入本区域时的落点。
     *
     * 返回 null 表示本次不接管，调用方按默认逻辑继续。
     */
    fun focusEntry(): View?

    /** [view] 是不是在本区域内 */
    fun contains(view: View): Boolean
}

/**
 * 内容区。比 [FocusRegion] 多回答一个问题：焦点是不是已经停在最上面那条带，
 * 再往上就该交回 tab 区了。
 *
 * 内容区内部形态不同（一个网格列表 / 若干行横向列表），但对外面只暴露这几件事。
 */
interface ContentRegion : FocusRegion {
    /** 焦点是否停在内容区的上边界 */
    fun isFocusAtTopEdge(): Boolean
}

/** [view] 是不是在 [container] 这棵子树里 */
internal fun isDescendantOf(container: View, view: View): Boolean {
    var current: View? = view
    while (current != null) {
        if (current === container) return true
        current = current.parent as? View
    }
    return false
}
