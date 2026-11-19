/**
 * @author learn-s-wood
 * @date 2026-11-19
 */
package io.github.learnswood.focusindicator.demo.focus

import android.view.View

/**
 * 跨区焦点导航。
 *
 * 焦点在区内移动交给系统（列表、tab 条各自的几何搜索）；焦点跨区（tab ↔ 内容区）
 * 由这里接管。
 *
 * 为什么必须接管：系统的几何搜索在这两个区之间是没法区分的：tab 条与卡片在垂直
 * 方向上距离完全打平，落点由遍历顺序决定。按 UP 从内容区出去会落到"最近的某个
 * tab"（实测是"少儿"而不是选中的"推荐"），还会顺带把整页切走；按 DOWN 从 tab
 * 出去也只会落到内容区的第一个卡片，不会回到上次停留的位置。
 *
 * 区域之间怎么交接由区域自己决定：这里只做"谁该接手"的判断，落点一律问区域要。
 */
class FocusNavigator(
    /** 当前 tab 区 */
    private val tabRegion: () -> FocusRegion?,
    /** 当前内容区边界 */
    private val contentArea: () -> ContentArea?
) {

    /**
     * 处理一次方向键。返回 true 表示已消费，这次按键不再交给系统。
     *
     * @param direction [View.FOCUS_UP] / [View.FOCUS_DOWN]
     * @param focused 当前持焦的 view
     */
    fun handle(direction: Int, focused: View?): Boolean {
        if (focused == null) return false
        val tab = tabRegion()
        val area = contentArea()
        if (tab == null || area == null) return false

        // tab 区往下走：交给内容区，落点由内容区决定（焦点记忆：回到上次停的位置）
        if (direction == View.FOCUS_DOWN && tab.contains(focused)) {
            val region = area.region()
            val target = region?.focusEntry()
            if (target == null) return false
            return target.requestFocus()
        }

        // 内容区往上走：只有已经停在上边界才交回 tab 区，落点是选中的那个 tab
        if (direction == View.FOCUS_UP && area.contains(focused)) {
            val region = area.region() ?: return false
            val target = area.upTarget(region) ?: return false
            return target.requestFocus()
        }

        return false
    }
}
