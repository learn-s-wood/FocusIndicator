/**
 * @author learn-s-wood
 * @date 2026-11-19
 */
package io.github.learnswood.focusindicator.follow

import android.view.View
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.RecyclerView
import io.github.learnswood.focusindicator.FocusIndicatorLayout
import kotlin.math.abs

/**
 * 滚动列表跟随控制器：焦点一变框飞向安置落点、列表同时滚，观感焦点不动列表动。
 * 策略由 [ListAligner] 注入；停稳核对预测落点，偏离且还能滚就补安置（封顶）。
 * 落点预测与钳制的推导见 docs/design.md「框与列表同时动」。
 *
 * @param alignerProvider 按列表取安置策略，返回 null 表示该列表不归本控制器管
 * @param tolerancePx 判定"已到位"的容差，几像素的偏差不值得再滚一次
 * @param maxAlignAttempts 一次焦点移动里最多补几次安置
 */
class FollowController(
    private val container: FocusIndicatorLayout,
    private val lists: List<RecyclerView>,
    private val alignerProvider: AlignerProvider,
    private val tolerancePx: Int = DEFAULT_TOLERANCE,
    private val maxAlignAttempts: Int = DEFAULT_MAX_ATTEMPTS
) : ViewTreeObserver.OnGlobalFocusChangeListener, RecyclerView.OnScrollListener() {

    private var tree: ViewTreeObserver? = null

    /** 补安置的目标，补安置会让列表再滚一次，这期间框跟着焦点项走 */
    private var aligning: View? = null

    /** 一次焦点移动里补过几次安置，封顶防绕圈 */
    private var alignAttempts = 0

    /** 焦点项预测落点（窗口坐标），滚完核对，对不上就把框放回焦点项上 */
    private var predicted: IntArray? = null

    /** 切手动模式并开始监听，容器已 attach 时调用 */
    fun attach() {
        container.setAutoFollow(false)
        tree = container.viewTreeObserver.also { it.addOnGlobalFocusChangeListener(this) }
        lists.forEach { it.addOnScrollListener(this) }
    }

    fun detach() {
        tree?.takeIf { it.isAlive }?.removeOnGlobalFocusChangeListener(this)
        tree = null
        lists.forEach { it.removeOnScrollListener(this) }
        aligning = null
        alignAttempts = 0
        predicted = null
        container.setTrackTargetLayout(true)
    }

    override fun onGlobalFocusChanged(oldFocus: View?, newFocus: View?) {
        aligning = null
        alignAttempts = 0
        predicted = null
        if (newFocus == null || !isInside(newFocus)) {
            return
        }
        val list = listOfTarget(newFocus) ?: return
        val aligner = alignerProvider.alignerFor(list) ?: return
        // 焦点可能在 item 内部的子 view 上：列表滚的是 item，框跟的是焦点本身
        val item = list.findContainingItemView(newFocus) ?: newFocus
        val shift = aligner.shiftOf(list, item)
        container.followToOffset(newFocus, shift.dx.toFloat(), shift.dy.toFloat())
        predicted = predictedCenterOf(newFocus, shift.dx, shift.dy)
        // 普通列表的安置滚动在这里驱动；自安置列表 align 是空操作，滚动由列表自己发起
        aligner.align(list, item)
        // 焦点刚变时列表可能还没开始滚，排到下一帧确认一次
        container.post(::alignIfIdle)
    }

    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
        if (newState == RecyclerView.SCROLL_STATE_IDLE) {
            alignIfIdle()
        }
    }

    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        // 补安置期间框跟着焦点项一起走
        val target = aligning ?: return
        container.snapTo(target)
    }

    /**
     * 所有列表都停稳后再核对一次
     *
     * 预测落点和实际对不上就把框放回焦点项上；焦点项没落在安置目标且还能朝那侧滚就补一次安置
     */
    private fun alignIfIdle() {
        val target = container.findFocus() ?: return
        if (lists.any { it.scrollState != RecyclerView.SCROLL_STATE_IDLE }) {
            return
        }
        if (!target.isShown) {
            return
        }
        val list = listOfTarget(target) ?: return
        val aligner = alignerProvider.alignerFor(list) ?: return
        // 安置偏差按 item 算，item 内部有子焦点时也一样
        val item = list.findContainingItemView(target) ?: target

        val prediction = predicted
        if (prediction != null && !matchesPrediction(target, prediction)) {
            // 预测落点和实际对不上：先把框放回焦点项上，再看要不要补安置
            container.snapTo(target)
            predicted = null
        }

        val offset = aligner.offsetOf(list, item)
        if (abs(offset) > tolerancePx && alignAttempts < maxAlignAttempts &&
            aligner.canScrollToward(list, offset)
        ) {
            if (aligner.align(list, item)) {
                alignAttempts++
                aligning = target
                // 补安置会再滚一次，预测落点作废，之后由 [onScrolled] 带着框走
                predicted = null
                return
            }
        }
        aligning = null
    }

    /** 焦点项中心加上安置位移，得到的预测落点（窗口坐标） */
    private fun predictedCenterOf(target: View, dx: Int, dy: Int): IntArray {
        val center = centerOf(target)
        return intArrayOf(center[0] + dx, center[1] + dy)
    }

    /** 焦点项实际中心（窗口坐标） */
    private fun centerOf(target: View): IntArray {
        val location = IntArray(2)
        target.getLocationInWindow(location)
        val centerX = location[0] + (target.width * target.scaleX).toInt() / 2
        val centerY = location[1] + (target.height * target.scaleY).toInt() / 2
        return intArrayOf(centerX, centerY)
    }

    /** 焦点项是不是落在预测的落点上了 */
    private fun matchesPrediction(target: View, prediction: IntArray): Boolean {
        val actual = centerOf(target)
        return abs(actual[0] - prediction[0]) <= tolerancePx &&
            abs(actual[1] - prediction[1]) <= tolerancePx
    }

    /** 目标落在哪条列表里，不在列表里返回 null */
    private fun listOfTarget(view: View?): RecyclerView? {
        if (view == null) return null
        return lists.firstOrNull { isInside(view, it) }
    }

    private fun isInside(view: View): Boolean = isInside(view, container)

    private fun isInside(view: View, root: View): Boolean {
        var parent = view.parent
        while (parent != null) {
            if (parent === root) {
                return true
            }
            parent = parent.parent
        }
        return false
    }

    companion object {
        /** 判定"已到位"的默认容差 */
        const val DEFAULT_TOLERANCE = 4

        /** 一次焦点移动里默认最多补几次安置 */
        const val DEFAULT_MAX_ATTEMPTS = 3
    }
}