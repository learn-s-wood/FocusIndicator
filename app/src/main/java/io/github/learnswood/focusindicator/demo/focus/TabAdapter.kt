/**
 * @author learn-s-wood
 * @date 2026-11-18
 */
package io.github.learnswood.focusindicator.demo.focus

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.github.learnswood.focusindicator.demo.R

/**
 * 顶部 tab 条的 adapter。
 *
 * item 自己可聚焦，选中态用文字颜色表示（见 color/tab_title.xml），不再画焦点框。
 * 某个 tab 拿到焦点就把它记为选中，再回调 [onTabFocused] 让页面跟着切。
 */
class TabAdapter(
    private val titles: IntArray,
    private val onTabFocused: (Int) -> Unit
) : RecyclerView.Adapter<TabAdapter.Holder>() {

    /** 当前选中的位置，只由 [select] 改 */
    var selectedIndex = 0
        private set

    /** 切换选中项，并让新旧两项重新绑定以刷新文字颜色 */
    fun select(position: Int) {
        if (position == selectedIndex) return
        val previous = selectedIndex
        selectedIndex = position
        notifyItemChanged(previous)
        notifyItemChanged(position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tab, parent, false)
        return Holder(view as TextView)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.title.setText(titles[position])
        holder.title.isSelected = position == selectedIndex
        holder.title.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) onTabFocused(position)
        }
    }

    override fun getItemCount(): Int = titles.size

    class Holder(val title: TextView) : RecyclerView.ViewHolder(title)
}
