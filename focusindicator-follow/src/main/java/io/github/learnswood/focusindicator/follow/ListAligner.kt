/**
 * @author learn-s-wood
 * @date 2026-11-19
 */
package io.github.learnswood.focusindicator.follow

import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * 列表焦点安置策略，与 LayoutManager 解耦，[FollowController] 只依赖本接口。
 * 普通 RecyclerView 用 [GravityAligner]；自带对齐的列表自行实现（只预测不驱动）。
 */
interface ListAligner {

    /** 焦点项被安置到位后的平移量，框据此飞向落点（落点只能算不能看） */
    fun shiftOf(list: RecyclerView, child: View): AlignShift

    /** 驱动列表把焦点项安置到位，返回是否发起了滚动；自安置列表恒返回 false */
    fun align(list: RecyclerView, child: View): Boolean

    /** 焦点项离安置目标还差多少像素（滚动轴），停稳后据此判断要不要补一次 */
    fun offsetOf(list: RecyclerView, child: View): Int = 0

    /** 列表还能不能朝 [offset] 的方向滚 */
    fun canScrollToward(list: RecyclerView, offset: Int): Boolean = false
}