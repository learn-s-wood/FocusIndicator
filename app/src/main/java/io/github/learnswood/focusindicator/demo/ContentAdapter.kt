/**
 * @author learn-s-wood
 * @date 2026-11-18
 */
package io.github.learnswood.focusindicator.demo

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import io.github.learnswood.focusindicator.FocusIndicatorLayout

/**
 * 一张内容卡的显示数据。
 *
 * [heightDp] / [widthDp] 为 0 时沿用一个撑满的默认值：网格页给固定高度、宽度由列
 * 数决定；横向行里的卡片反过来，宽度给固定值、高度撑满行高。
 */
data class ContentCard(
    val title: String,
    val subtitle: String,
    val heightDp: Int = 0,
    val widthDp: Int = 0,
    val chips: List<String> = emptyList(),
    val ignored: Boolean = false
)

/**
 * Tab 示例页各个面板共用的 adapter。
 *
 * item 只需 focusable，焦点指示由外层 [io.github.learnswood.focusindicator.FocusIndicatorLayout]
 * 统一绘制；第一行向上回 tab 的边界逻辑在 [ContentList] / [ShelfStack] 里，adapter 不用管。
 */
class ContentAdapter(
    private val items: List<ContentCard>
) :
    RecyclerView.Adapter<ContentAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_content_card, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.bind(item)

        val density = holder.itemView.resources.displayMetrics.density
        val params = holder.itemView.layoutParams
        val width = if (item.widthDp > 0) (item.widthDp * density).toInt() else params.width
        val height = if (item.heightDp > 0) (item.heightDp * density).toInt() else ViewGroup.LayoutParams.MATCH_PARENT
        if (params.width != width || params.height != height) {
            params.width = width
            params.height = height
            holder.itemView.layoutParams = params
        }
    }

    override fun getItemCount(): Int = items.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.contentTitle)
        val subtitle: TextView = itemView.findViewById(R.id.contentSubtitle)
        private val chipRow: LinearLayout = itemView.findViewById(R.id.chipRow)
        private val chipA: TextView = itemView.findViewById(R.id.chipA)
        private val chipB: TextView = itemView.findViewById(R.id.chipB)

        /** 绑定卡片数据 */
        fun bind(item: ContentCard) {
            title.text = item.title
            subtitle.text = item.subtitle
            // 豁免卡打 fi:ignore tag 让飞框不套它，背景换成直角无框样例；回收复用时两者都要复位
            itemView.tag = if (item.ignored) FocusIndicatorLayout.TAG_IGNORE else null
            itemView.setBackgroundResource(
                if (item.ignored) R.drawable.bg_ignored_card else R.drawable.bg_grid_card
            )
            // 豁免卡没有背景框，焦点态靠文字色 selector。焦点在 itemView 上，duplicateParentState
            // 的镜像传到中间 LinearLayout 就断了、到不了文字，所以直接监听 item 焦点取 selector 色
            if (item.ignored) {
                val colors = ContextCompat.getColorStateList(itemView.context, R.color.ignored_card_text)
                itemView.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                    applyIgnoredTextColor(colors, hasFocus)
                }
                applyIgnoredTextColor(colors, itemView.isFocused)
            } else {
                itemView.onFocusChangeListener = null
                title.setTextColor(color(R.color.demo_text_primary))
                subtitle.setTextColor(color(R.color.demo_text_secondary))
            }
            val chips = item.chips
            // 带 chip 的 item 自己不接焦点，焦点直接落在 chip 上
            itemView.isFocusable = chips.isEmpty()
            chipRow.visibility = if (chips.isEmpty()) View.GONE else View.VISIBLE
            chipA.text = chips.getOrNull(0).orEmpty()
            chipB.text = chips.getOrNull(1).orEmpty()
            chipB.visibility = if (chips.size > 1) View.VISIBLE else View.GONE
        }

        /** 按焦点态从文字色 selector 取色，上到标题/副标题 */
        private fun applyIgnoredTextColor(colors: ColorStateList?, focused: Boolean) {
            if (colors == null) return
            val state = if (focused) intArrayOf(android.R.attr.state_focused) else IntArray(0)
            val c = colors.getColorForState(state, colors.defaultColor)
            title.setTextColor(c)
            subtitle.setTextColor(c)
        }

        private fun color(resId: Int): Int = ContextCompat.getColor(itemView.context, resId)
    }
}
