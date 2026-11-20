/**
 * @author learn-s-wood
 * @date 2026-11-20
 */
package io.github.learnswood.focusindicator.demo.focus

import android.view.FocusFinder
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 一个内容页的焦点装配：根容器、列表集合、是否货架形态 */
data class FocusSpec(val root: ViewGroup, val lists: List<RecyclerView>, val shelf: Boolean)

/**
 * 页面级焦点策略单点收口：位置记忆、进入落点、跨区交接、区内限界。
 * 接管点在 dispatchKeyEvent，区内用公开 [FocusFinder] 算；不覆写 focusSearch、不 subclass RV。
 * 记忆只记 adapter position，item 回收重绑后仍可靠。
 */
class FocusHelper(private val tab: RecyclerView) {

    /** 每个列表上次持焦的位置；弱键，view 销毁自动出表 */
    private val remembered = WeakHashMap<RecyclerView, Int>()

    /**
     * 每个内容根上次持焦的列表，货架用来记行
     *
     * ponytail: 值也包弱引用，row.parent 反向强指 root，值强引用会让弱键永不回收
     */
    private val lastList = WeakHashMap<ViewGroup, WeakReference<RecyclerView>>()

    private var contentRoot: ViewGroup? = null
    private var contentLists: List<RecyclerView> = emptyList()
    private var shelf = false

    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, new ->
        if (new == null) return@OnGlobalFocusChangeListener
        val owner = ownerOf(new) ?: return@OnGlobalFocusChangeListener
        val item = owner.findContainingItemView(new) ?: return@OnGlobalFocusChangeListener
        val position = owner.getChildAdapterPosition(item)
        if (position != RecyclerView.NO_POSITION) remembered[owner] = position
        if (owner !== tab) contentRoot?.let { lastList[it] = WeakReference(owner) }
    }

    fun attach() {
        tab.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
    }

    fun detach() {
        tab.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
    }

    /** 换页时绑定当前内容区 */
    fun bindContent(spec: FocusSpec) {
        contentRoot = spec.root
        contentLists = spec.lists
        shelf = spec.shelf
    }

    /** 处理一次方向键，true 表示已消费、不再交给系统 */
    fun handle(direction: Int, focused: View?): Boolean {
        if (focused == null) return false
        val owner = ownerOf(focused) ?: return false
        return if (owner === tab) handleTab(direction, focused)
        else handleContent(direction, focused, owner)
    }

    /** 焦点进入某列表：记忆位置或第一个；目标不在屏先滚过去、下一帧再落 */
    fun enter(list: RecyclerView?): Boolean {
        if (list == null) return false
        val target = entryOf(list)
        if (target != null) return target.requestFocus()
        list.post { entryOf(list)?.requestFocus() }
        return true
    }

    private fun handleTab(direction: Int, focused: View): Boolean = when (direction) {
        View.FOCUS_LEFT, View.FOCUS_RIGHT -> moveWithin(tab, focused, direction)
        View.FOCUS_DOWN -> enter(currentList())
        // tab 在顶，UP 停住
        else -> true
    }

    private fun handleContent(direction: Int, focused: View, owner: RecyclerView): Boolean {
        val root = contentRoot ?: return false
        return when (direction) {
            View.FOCUS_LEFT, View.FOCUS_RIGHT -> moveWithin(owner, focused, direction)
            View.FOCUS_UP ->
                if (atTopEdge(owner, focused)) enter(tab) else moveWithin(root, focused, direction)
            else -> moveWithin(root, focused, direction)
        }
    }

    /**
     * 在 root 子树内几何搜索下一个落点
     *
     * ponytail: 到头停住并消费按键，不交回系统，避免跨区距离打平时落点由遍历顺序决定
     */
    private fun moveWithin(root: ViewGroup, focused: View, direction: Int): Boolean {
        val next = FocusFinder.getInstance().findNextFocus(root, focused, direction)
        if (next != null && next !== focused) next.requestFocus()
        return true
    }

    /** 焦点是否停在内容区上边界：货架看是不是第一行，网格看是不是第一排 */
    private fun atTopEdge(owner: RecyclerView, focused: View): Boolean {
        if (shelf) return owner === contentLists.firstOrNull()
        val item = owner.findContainingItemView(focused) ?: return false
        val position = owner.getChildAdapterPosition(item)
        val span = (owner.layoutManager as? GridLayoutManager)?.spanCount ?: 1
        return position in 0 until span
    }

    /** 内容区当前该接焦点的列表：上次持焦的那个，没来过取第一个 */
    private fun currentList(): RecyclerView? {
        val root = contentRoot ?: return null
        return lastList[root]?.get()?.takeIf { it in contentLists } ?: contentLists.firstOrNull()
    }

    private fun entryOf(list: RecyclerView): View? {
        val position = remembered[list] ?: 0
        val holder = list.findViewHolderForAdapterPosition(position)
        if (holder != null) return holder.itemView
        list.scrollToPosition(position)
        return null
    }

    /** 持焦 view 归属哪个列表；芯片等 item 内子按钮归到所在 item 的列表 */
    private fun ownerOf(view: View): RecyclerView? {
        if (tab.findContainingItemView(view) != null) return tab
        return contentLists.firstOrNull { it.findContainingItemView(view) != null }
    }
}