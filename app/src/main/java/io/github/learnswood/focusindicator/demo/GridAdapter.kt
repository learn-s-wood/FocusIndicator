/**
 * @author learn-s-wood
 * @date 2026-11-17
 */
package io.github.learnswood.focusindicator.demo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 网格场景的最小 adapter。
 * item 只需要 focusable，焦点指示由外层 [io.github.learnswood.focusindicator.FocusIndicatorLayout] 统一绘制，
 * adapter 里不需要维护任何"选中/焦点"状态或 selector。
 */
class GridAdapter(private val items: List<String>) : RecyclerView.Adapter<GridAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_grid_card, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.text.text = items[position]
    }

    override fun getItemCount(): Int = items.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val text: TextView = itemView.findViewById(R.id.cardText)
    }
}
