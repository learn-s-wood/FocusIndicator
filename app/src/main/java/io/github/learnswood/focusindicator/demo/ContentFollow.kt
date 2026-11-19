/**
 * @author learn-s-wood
 * @date 2026-11-18
 */
package io.github.learnswood.focusindicator.demo

import android.view.View
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.RecyclerView
import io.github.learnswood.focusindicator.FocusIndicatorLayout
import io.github.learnswood.focusindicator.demo.focus.Centerable
import kotlin.math.abs

/**
 * 内容区的跟随控制器：框和列表同时动
 *
 * 焦点一变就把框放到焦点项居中之后的位置，列表同时滚过去；落点算法见 docs/design.md
 */
class ContentFollow(
    private val container: FocusIndicatorLayout,
    private val lists: List<RecyclerView>
) : ViewTreeObserver.OnGlobalFocusChangeListener, RecyclerView.OnScrollListener() {

    private var tree: ViewTreeObserver? = null

    /** 补居中的目标，补居中会让列表再滚一次，这期间框跟着焦点项走 */
    private var centering: View? = null

    /** 一次焦点移动里补过几次居中，封顶防绕圈 */
    private var centerAttempts = 0

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
        centering = null
        centerAttempts = 0
        predicted = null
        container.setTrackTargetLayout(true)
    }

    override fun onGlobalFocusChanged(oldFocus: View?, newFocus: View?) {
        centering = null
        centerAttempts = 0
        predicted = null
        if (newFocus == null || !isInside(newFocus)) {
            return
        }
        val list = listOfTarget(newFocus) ?: return
        val centerable = list.layoutManager as? Centerable ?: return
        // 焦点可能在 item 内部的子 view 上：列表滚的是 item，框跟的是焦点本身
        val item = list.findContainingItemView(newFocus) ?: newFocus
        val shift = centerable.centerShiftOf(list, item)
        container.followToOffset(newFocus, shift.dx.toFloat(), shift.dy.toFloat())
        predicted = predictedCenterOf(newFocus, shift.dx, shift.dy)
        // 焦点刚变时列表可能还没开始滚，排到下一帧确认一次
        container.post(::alignIfIdle)
    }

    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
        if (newState == RecyclerView.SCROLL_STATE_IDLE) {
            alignIfIdle()
        }
    }

    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        // 补居中期间框跟着焦点项一起走
        val target = centering ?: return
        container.snapTo(target)
    }

    /**
     * 所有列表都停稳后再核对一次
     *
     * 预测落点和实际对不上就把框放回焦点项上；焦点项没落在中心且还能朝那侧滚就补一次居中
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
        // 居中偏差按 item 算，item 内部有子焦点时也一样
        val item = list.findContainingItemView(target) ?: target

        val prediction = predicted
        if (prediction != null && !matchesPrediction(target, prediction)) {
            // 预测落点和实际对不上：先把框放回焦点项上，再看要不要补居中
            container.snapTo(target)
            predicted = null
        }

        val offset = offsetOf(item, list)
        if (abs(offset) > CENTER_TOLERANCE && centerAttempts < MAX_CENTER_ATTEMPTS &&
            canScrollToward(list, offset)
        ) {
            val centerable = list.layoutManager as? Centerable
            if (centerable != null && centerable.centerOn(list, item)) {
                centerAttempts++
                centering = target
                // 补居中会再滚一次，预测落点作废，之后由 [onScrolled] 带着框走
                predicted = null
                return
            }
        }
        centering = null
    }

    /** 焦点项中心加上居中位移，得到的预测落点（窗口坐标） */
    private fun predictedCenterOf(target: View, dx: Int, dy: Int): IntArray {
        val location = IntArray(2)
        target.getLocationInWindow(location)
        val centerX = location[0] + (target.width * target.scaleX).toInt() / 2
        val centerY = location[1] + (target.height * target.scaleY).toInt() / 2
        return intArrayOf(centerX + dx, centerY + dy)
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
        return abs(actual[0] - prediction[0]) <= CENTER_TOLERANCE &&
            abs(actual[1] - prediction[1]) <= CENTER_TOLERANCE
    }

    /**
     * 焦点项离列表的滚动中心还差多少像素，正数表示要往后滚
     *
     * 只看列表能滚的那条轴：网格多列时每列的水平中心不同，拿横向去比会一直当"偏了"
     */
    private fun offsetOf(target: View, list: RecyclerView): Int {
        val layoutManager = list.layoutManager ?: return 0
        val targetLocation = IntArray(2)
        val listLocation = IntArray(2)
        target.getLocationInWindow(targetLocation)
        list.getLocationInWindow(listLocation)
        // 视口中心按 padding 算，与 [centerShiftOf] 的口径一致
        return if (layoutManager.canScrollVertically()) {
            val viewCenter = listLocation[1] + (list.paddingTop + list.height - list.paddingBottom) / 2
            targetLocation[1] + target.height / 2 - viewCenter
        } else if (layoutManager.canScrollHorizontally()) {
            val viewCenter = listLocation[0] + (list.paddingLeft + list.width - list.paddingRight) / 2
            targetLocation[0] + target.width / 2 - viewCenter
        } else {
            0
        }
    }

    /**
     * 列表还能不能朝 [offset] 的方向滚
     *
     * 直接问列表本身：自己拿滚动量算出来的是滚动途中的瞬时值，滚得越快偏得越多
     */
    private fun canScrollToward(list: RecyclerView, offset: Int): Boolean {
        val layoutManager = list.layoutManager ?: return false
        return if (layoutManager.canScrollVertically()) {
            list.canScrollVertically(if (offset > 0) 1 else -1)
        } else if (layoutManager.canScrollHorizontally()) {
            list.canScrollHorizontally(if (offset > 0) 1 else -1)
        } else {
            false
        }
    }

    /** 目标落在哪条列表里，不在列表里返回 null */
    private fun listOfTarget(view: View?): RecyclerView? {
        if (view == null) return null
        return lists.firstOrNull { isInside(view, it) }
    }

    private fun isInside(view: View): Boolean {
        var parent = view.parent
        while (parent != null) {
            if (parent === container) {
                return true
            }
            parent = parent.parent
        }
        return false
    }

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

    private companion object {
        /** 判定"在中心"的容差，几像素的偏差不值得再滚一次 */
        const val CENTER_TOLERANCE = 4

        /** 一次焦点移动里最多补几次居中 */
        const val MAX_CENTER_ATTEMPTS = 3
    }
}
