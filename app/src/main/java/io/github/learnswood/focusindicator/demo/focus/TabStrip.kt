/**
 * @author learn-s-wood
 * @date 2026-11-18
 */
package io.github.learnswood.focusindicator.demo.focus

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.FocusFinder
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * 顶部 tab 条，作为页面的 tab 区实现。
 *
 * 焦点记忆就是选中态本身：tab 拿到焦点即切页并标记选中，焦点离开后保持不变。
 * 焦点回来时不用外部指定落点，[onRequestFocusInDescendants] 会优先把焦点交给选中的条目。
 *
 * 左右方向只在自身内部移动，到头就停住，不会跑到内容区去；
 * 上下跨区由 [FocusNavigator] 接管，不靠几何搜索：多个 tab 与卡片的距离完全打平，
 * 几何搜索的落点由遍历顺序决定，还会顺带把页面切走。
 */
class TabStrip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr), FocusRegion {

    private var onTabSelected: ((Int) -> Unit)? = null

    init {
        layoutManager = CenterLayoutManager(context, HORIZONTAL, false)
        // 选中态只是换文字颜色，重新绑定不需要动画
        itemAnimator = null
    }

    /** 绑定标题与切页回调 */
    fun bind(titles: IntArray, onTabSelected: (Int) -> Unit) {
        this.onTabSelected = onTabSelected
        adapter = TabAdapter(titles) { position -> select(position) }
    }

    /** 选中某个 tab：先更新红字，再通知页面切换 */
    fun select(position: Int) {
        val tabAdapter = adapter as? TabAdapter ?: return
        if (position == tabAdapter.selectedIndex) return
        tabAdapter.select(position)
        onTabSelected?.invoke(position)
    }

    /**
     * 容器自己拿到焦点时挑子项：优先选中的那个，其次按 child 顺序找，与
     * 父类默认行为一致。这样外部不需要知道选中项是谁，requestFocus 这个容器即可。
     */
    override fun onRequestFocusInDescendants(
        direction: Int,
        previouslyFocusedRect: Rect?
    ): Boolean {
        val entry = focusEntry()
        if (entry != null && entry.requestFocus(direction, previouslyFocusedRect)) {
            return true
        }
        return super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
    }

    /** 焦点进入 tab 区时的落点：选中的那个 tab；还没有选中项时取第一个可见条目 */
    override fun focusEntry(): View? {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.isSelected && child.visibility == VISIBLE) {
                return child
            }
        }
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == VISIBLE) {
                return child
            }
        }
        return null
    }

    /** [view] 是不是在本区域内 */
    override fun contains(view: View): Boolean = isDescendantOf(this, view)

    /**
     * 焦点搜索走到 tab 条时，只管左右限界：左右到头就停在本条，不跑出 tab 区。
     *
     * 上下跨区不在这里处理，由 [FocusNavigator] 统一接管：系统的焦点搜索在 tab 条
     * 与内容区之间靠几何距离打平，落点不可控。
     *
     * 判据用 [findFocus] 拿真实焦点，不依赖传进来的 focused 参数：搜索链上每一层
     * 传下来的 focused 语义并不统一，拿真实焦点才靠得住。
     */
    override fun focusSearch(focused: View?, direction: Int): View? {
        val current = focused ?: findFocus()
        if (current == null || current.parent !== this) {
            // 焦点不在 tab 条里：把搜索交回父级继续冒泡。
            // RecyclerView 自己的实现假定 focused 是它的子项，坐标换算会算到别的条目上
            return parent?.focusSearch(current, direction)
        }
        if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) {
            return FocusFinder.getInstance().findNextFocus(this, current, direction) ?: current
        }
        return super.focusSearch(focused, direction)
    }
}
