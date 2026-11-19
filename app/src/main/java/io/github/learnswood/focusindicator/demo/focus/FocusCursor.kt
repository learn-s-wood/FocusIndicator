/**
 * @author learn-s-wood
 * @date 2026-11-19
 */
package io.github.learnswood.focusindicator.demo.focus

import androidx.recyclerview.widget.RecyclerView

/**
 * 焦点位置游标。
 *
 * 只记"焦点上次停在第几个"，不记 view 本身：item 可能被回收重绑，位置更可靠。
 * 思路照搬 Leanback 的焦点记忆（BaseGridView 的 mFocusPosition）：位置与真实焦点
 * 分开记，焦点离开时不清零，重新进入时按它恢复。
 */
class FocusCursor {

    /** 记住的位置；[RecyclerView.NO_POSITION] 表示还没记过 */
    var position: Int = RecyclerView.NO_POSITION
        private set

    /** 有没有记住过位置 */
    val hasPosition: Boolean
        get() = position != RecyclerView.NO_POSITION

    /** 记下新位置 */
    fun update(position: Int) {
        if (position != RecyclerView.NO_POSITION) {
            this.position = position
        }
    }

    /** 忘掉位置：换了数据源才需要 */
    fun clear() {
        position = RecyclerView.NO_POSITION
    }
}
