/**
 * @author learn-s-wood
 * @date 2026-11-17
 */
package io.github.learnswood.focusindicator

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.max

/**
 * 焦点飞框容器：把焦点指示器从 item 自身剥离出来，作为独立浮层跟随焦点移动。
 *
 * 结构：
 * ```
 * FocusIndicatorLayout
 *   ├─ behindLayer : FocusIndicatorView   (index=0，在 item 之下，画圆角实心底色)
 *   ├─ 业务子 View  (背景保持透明即可露出底色层)
 *   └─ frontLayer  : FocusIndicatorView   (末尾，在 item 之上，画描边/光晕)
 * ```
 * 两个绘制层都是独立 View，各自只 invalidate 自己的一小块脏区，
 * 动画期间不会让业务子 View 重新走 dispatchDraw。
 *
 * 用法：
 * ```kotlin
 * container.behind().fill(0xFFFF5000).corner(px).padding(0f)          // 滑动色块
 * container.front().stroke(Color.WHITE, px).corner(px).padding(px)    // 外圈描边
 * ```
 * 子 View 无需任何配合代码，只需 focusable="true"。
 *
 * 子 View 在焦点态自带放大/位移动画时也不需要额外配置：容器默认逐帧重采目标的真实外形
 * （含 scale / translation），并在飞框动画结束后保留一小段贴合窗口把尾巴追平。
 * 确实不需要时可以用 [setTrackTargetLayout] 关掉，省去动画期间的重复测量。
 *
 * 单个容器只能跟踪自己子树内的焦点。页面里有多个容器（多行/多栏）时，
 * 把它们绑到同一个 [Group]，焦点上下移动时飞框会跨容器飞过去而不是闪现：
 * ```kotlin
 * val group = FocusIndicatorLayout.Group()
 * row1.setGroup(group)
 * row2.setGroup(group)
 * ```
 */
class FocusIndicatorLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val behindView = FocusIndicatorView(context)
    private val frontView = FocusIndicatorView(context)
    private val behindLayer = Layer(behindView)
    private val frontLayer = Layer(frontView)

    /** 当前焦点项的外形（不含 padding），动画的实时值 */
    private val currentRect = RectF()
    private val fromRect = RectF()
    private val toRect = RectF()
    private val layerRect = RectF()
    private val selfLocation = IntArray(2)
    private val targetLocation = IntArray(2)

    private val interpolator = DecelerateInterpolator(1.8f)
    private val updateListener = ValueAnimator.AnimatorUpdateListener { animation ->
        applyProgress(animation.animatedValue as Float)
    }

    private var animator: ValueAnimator? = null
    private var duration = DEFAULT_DURATION

    /** 指示器当前是否已显示（用于首帧直接对齐、不做"从 0,0 飞过来"） */
    private var visible = false

    /** 自动跟随焦点，默认开启 */
    private var autoFollow = true

    /** 待跟随的目标，用于 post 后取值 */
    private var pendingTarget: View? = null

    /** 跨容器接力组，同页多个容器共享时可跨行飞行 */
    private var group: Group? = null

    /** 当前正在贴合的目标；放大/位移动画期间需要逐帧重采它的外形 */
    private var currentTarget: View? = null

    /** 是否逐帧贴合目标外形，默认开启 */
    private var trackTargetLayout = true

    /** 贴合循环用的临时外形与计时 */
    private val settleRect = RectF()
    private var settleStartTime = 0L
    private var settleStableFrames = 0

    private var observer: ViewTreeObserver? = null

    private val followRunnable = Runnable {
        val target = pendingTarget
        pendingTarget = null
        if (!autoFollow || target == null || !isShown || !isSelfOrDescendant(target) ||
            target.width == 0 || target.height == 0
        ) {
            hideIndicator()
            return@Runnable
        }
        followTo(target)
    }

    /**
     * 动画结束后的贴合收尾循环。
     * 外形还在变就继续跟；连续 [SETTLE_STABLE_FRAMES] 帧不变、
     * 或超过 [SETTLE_TIMEOUT] 就自行退出，不会常驻占用帧回调。
     */
    private val settleRunnable: Runnable = object : Runnable {
        override fun run() {
            val target = currentTarget
            if (!trackTargetLayout || !visible || target == null ||
                !isSelfOrDescendant(target) || target.width == 0
            ) {
                return
            }
            if (SystemClock.uptimeMillis() - settleStartTime > SETTLE_TIMEOUT) {
                return
            }
            measureRect(target, settleRect)
            if (isNear(settleRect, currentRect)) {
                if (++settleStableFrames >= SETTLE_STABLE_FRAMES) {
                    return
                }
            } else {
                settleStableFrames = 0
                currentRect.set(settleRect)
                drawLayers()
            }
            postOnAnimation(this)
        }
    }

    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        if (newFocus != null && isSelfOrDescendant(newFocus)) {
            postFollow(newFocus)
        } else {
            // 焦点离开本容器：移除待处理任务并隐藏
            removeCallbacks(followRunnable)
            pendingTarget = null
            hideIndicator()
        }
    }

    init {
        // 飞框需要能画到容器边界之外（跨行飞行时起始/结束位置在容器外），
        // 这里强制关掉子 View 裁剪，XML 上的 clipChildren 不要再覆盖成 true
        clipChildren = false
    }

    /** 底层（item 之下）：圆角实心底色等"会遮挡 item 内容"的视觉只能放这里 */
    fun behind(): Layer = behindLayer

    /** 顶层（item 之上）：描边、外发光等"不遮挡内容"的视觉放这里 */
    fun front(): Layer = frontLayer

    /** 关闭/开启自动跟随（手动模式下自行调用 [followTo]） */
    fun setAutoFollow(autoFollow: Boolean) {
        this.autoFollow = autoFollow
        if (!autoFollow) {
            hideIndicator()
        }
    }

    /** 动画时长(ms) */
    fun setDuration(duration: Long) {
        this.duration = duration
    }

    /**
     * 指定接力组。同一页面里多个 FocusIndicatorLayout 设为同一个 [Group] 后，
     * 焦点在容器之间上下移动时，飞框会从上一个容器的位置飞过来，而不是在新容器里直接闪现。
     */
    fun setGroup(group: Group?) {
        this.group = group
    }

    /**
     * 是否逐帧贴合目标的外形，默认开启。
     *
     * item 在焦点态自己做放大动画（`animate().scaleX(1.1f)`）时必须开启：
     * 否则飞框只会按「拿到焦点那一刻」的尺寸画，最终比放大后的 item 小一圈。
     * 纯静态 item（焦点态不改尺寸/位置）可以关掉，省去动画期间的重复测量。
     */
    fun setTrackTargetLayout(trackTargetLayout: Boolean) {
        this.trackTargetLayout = trackTargetLayout
        if (!trackTargetLayout) {
            stopSettle()
        }
    }

    /**
     * 手动跟随指定 View（不做动画，直接对齐）。
     * 适用于列表滚动、item 尺寸变化后需要重新校正的场景。
     */
    fun snapTo(target: View?) {
        if (target == null || !isSelfOrDescendant(target)) {
            hideIndicator()
            return
        }
        cancelAnimator()
        currentTarget = target
        measureRect(target, fromRect)
        currentRect.set(fromRect)
        visible = true
        drawLayers()
        startSettle()
    }

    /** 重新对齐当前焦点项（尺寸/位置变化后调用），不做动画 */
    fun requestSnap() {
        val focused = findFocus() ?: return
        if (isSelfOrDescendant(focused)) {
            postFollow(focused)
        }
    }

    /**
     * 手动飞行到指定 View。
     * 首次显示时，若同组其它容器刚离开过就从它的位置飞过来，否则直接对齐；
     * 已有指示器时从当前实时外形开始动画。
     */
    fun followTo(target: View?) {
        if (target == null || !isSelfOrDescendant(target)) {
            hideIndicator()
            return
        }
        // measureRect 会顺带刷新 selfLocation，后面的组接力靠它换算坐标
        measureRect(target, toRect)
        if (toRect.isEmpty) {
            hideIndicator()
            return
        }
        currentTarget = target

        cancelAnimator()

        if (!visible) {
            // 首次显示：同组其它容器刚离开过就从它的位置飞过来；
            // 否则直接对齐，避免首帧从 (0,0) 飞进画面
            if (group == null || !group!!.restore(fromRect, selfLocation) || fromRect.isEmpty) {
                currentRect.set(toRect)
                visible = true
                drawLayers()
                // 无动画落地也要贴合：item 的放大动画可能刚开始跑
                startSettle()
                return
            }
            currentRect.set(fromRect)
            visible = true
            startAnimation()
            return
        }

        // 打断续飞：起点取当前实时外形，而不是上一帧的目标值，快速连按时不会跳变
        fromRect.set(currentRect)
        startAnimation()
    }

    private fun startAnimation() {
        val animator = ValueAnimator.ofFloat(0f, 1f)
        this.animator = animator
        animator.duration = duration
        animator.interpolator = interpolator
        animator.addUpdateListener(updateListener)
        animator.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false

            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                // cancelAnimator() 会同步走到 onAnimationEnd，这里只处理自然结束
                if (!cancelled) {
                    startSettle()
                }
            }
        })
        animator.start()
    }

    private fun startSettle() {
        stopSettle()
        if (!trackTargetLayout || !visible || currentTarget == null) {
            return
        }
        settleStartTime = SystemClock.uptimeMillis()
        settleStableFrames = 0
        postOnAnimation(settleRunnable)
    }

    private fun stopSettle() {
        removeCallbacks(settleRunnable)
        settleStableFrames = 0
    }

    private fun postFollow(target: View) {
        pendingTarget = target
        removeCallbacks(followRunnable)
        post(followRunnable)
    }

    private fun hideIndicator() {
        if (!visible) {
            return
        }
        cancelAnimator()
        visible = false
        // 记下离开前的外形（窗口坐标），同组其它容器接手时从这儿起飞。
        // 隐藏是焦点回调里同步发生的，此时各组件的窗口位置都还是有效的。
        if (group != null && !currentRect.isEmpty) {
            getLocationInWindow(selfLocation)
            group!!.save(currentRect, selfLocation)
        }
        currentTarget = null
        currentRect.setEmpty()
        behindView.clear()
        frontView.clear()
    }

    private fun cancelAnimator() {
        stopSettle()
        animator?.let {
            it.removeUpdateListener(updateListener)
            it.cancel()
        }
        animator = null
    }

    /**
     * 按进度插值当前外形并重绘。
     * 位移用完整进度，尺寸用滞后进度，避免大尺寸差异时中间形状扭曲。
     */
    private fun applyProgress(progress: Float) {
        // 逐帧重采目标外形：业务方在焦点态做放大动画时，item 的真实外形在飞框动画期间还在变，
        // 用启动瞬间的静态快照会让框最终小于放大后的 item，看起来就是「框不住」
        val target = currentTarget
        if (trackTargetLayout && target != null && isSelfOrDescendant(target) &&
            target.width > 0 && target.height > 0
        ) {
            measureRect(target, toRect)
        }

        val sizeProgress = if (progress <= SIZE_LAG) 0f else (progress - SIZE_LAG) / (1f - SIZE_LAG)

        val fromCx = (fromRect.left + fromRect.right) * 0.5f
        val fromCy = (fromRect.top + fromRect.bottom) * 0.5f
        val toCx = (toRect.left + toRect.right) * 0.5f
        val toCy = (toRect.top + toRect.bottom) * 0.5f

        val cx = fromCx + (toCx - fromCx) * progress
        val cy = fromCy + (toCy - fromCy) * progress
        val halfW = fromRect.width() * 0.5f + (toRect.width() * 0.5f - fromRect.width() * 0.5f) * sizeProgress
        val halfH = fromRect.height() * 0.5f + (toRect.height() * 0.5f - fromRect.height() * 0.5f) * sizeProgress

        currentRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        drawLayers()
    }

    /** 把当前外形同步到两个绘制层 */
    private fun drawLayers() {
        drawLayer(behindLayer, behindView)
        drawLayer(frontLayer, frontView)
    }

    private fun drawLayer(layer: Layer, view: FocusIndicatorView) {
        if (!layer.enabled) {
            view.clear()
            return
        }
        layerRect.set(currentRect)
        layerRect.inset(-layer.paddingPx, -layer.paddingPx)
        view.update(layerRect, layer.cornerRadiusPx)
    }

    /**
     * 计算 item 在本容器坐标系下的外形。
     * 用 getLocationInWindow 相减，嵌套滚动/任意层级都成立。
     *
     * 注意 getLocationInWindow 内部会应用 item 自己的 matrix（`getMatrix().mapPoints`）：
     * item 缩放后它返回的已经是「绕 pivot 外扩之后」的左上角，translationX/Y 也已经算进去了。
     * 所以这里只能拿它当视觉左上角，配上 `宽高 * scale` 直接拼矩形；
     * 不能再按「layout 左上角 + 宽/2 是中心」去算 —— 那样会把 matrix 已经折算过的外扩
     * 再加一遍，框整体往左上方偏移 `(scale - 1) * 尺寸 / 2`，缩放越大偏得越多。
     *
     * 这样写对非中心 pivot 也成立（矩阵已处理），前提是父级容器自身不做缩放。
     */
    private fun measureRect(target: View, out: RectF) {
        target.getLocationInWindow(targetLocation)
        getLocationInWindow(selfLocation)

        val left = (targetLocation[0] - selfLocation[0]).toFloat()
        val top = (targetLocation[1] - selfLocation[1]).toFloat()
        val width = target.width * target.scaleX
        val height = target.height * target.scaleY

        out.set(left, top, left + width, top + height)
    }

    private fun isSelfOrDescendant(view: View): Boolean {
        if (view === this) {
            return false
        }
        var parent: ViewParent? = view.parent
        while (parent != null) {
            if (parent === this) {
                return true
            }
            parent = parent.parent
        }
        return false
    }

    /**
     * 保证两个绘制层就位：behind 永远在 index 0，front 永远在最后一个。
     * XML 场景由 onFinishInflate 触发；纯代码 addView 场景由 onAttachedToWindow 兜底。
     */
    private fun ensureLayers() {
        if (behindView.parent !== this) {
            addView(behindView, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } else if (indexOfChild(behindView) != 0) {
            removeView(behindView)
            addView(behindView, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        if (frontView.parent !== this) {
            addView(frontView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } else if (indexOfChild(frontView) != childCount - 1) {
            bringChildToFront(frontView)
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        ensureLayers()
    }

    /**
     * 容器尺寸只由业务子 View 决定。
     *
     * 两个绘制层必须铺满容器才能画到任意位置，但它们不能参与「撑开容器」的计算：
     * 否则 wrap_content 的容器会按 MATCH_PARENT 子 View 去要父容器全部可用尺寸，
     * 页面上就会多出一大块空白（业务子 View 被顶到容器底部）。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var contentWidth = 0
        var contentHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child === behindView || child === frontView) {
                continue
            }
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val lp = child.layoutParams as MarginLayoutParams
            contentWidth = max(contentWidth, child.measuredWidth + lp.leftMargin + lp.rightMargin)
            contentHeight = max(contentHeight, child.measuredHeight + lp.topMargin + lp.bottomMargin)
        }

        contentWidth += paddingLeft + paddingRight
        contentHeight += paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(contentWidth, widthMeasureSpec),
            resolveSize(contentHeight, heightMeasureSpec)
        )

        val layerWidthSpec = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY)
        val layerHeightSpec = MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        behindView.measure(layerWidthSpec, layerHeightSpec)
        frontView.measure(layerWidthSpec, layerHeightSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // 绘制层始终以容器左上角为原点铺满，不受容器 padding 影响：
        // measureRect 算出的就是容器局部坐标，差一个 padding 就会整体偏移
        behindView.layout(0, 0, width, height)
        frontView.layout(0, 0, width, height)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureLayers()

        val observer = viewTreeObserver
        this.observer = observer
        observer.addOnGlobalFocusChangeListener(focusListener)

        // 页面恢复时焦点可能已经落在本容器内，需要立即对齐（首次直接跳到位，不做动画）
        val focused = findFocus()
        if (focused != null && isSelfOrDescendant(focused)) {
            postFollow(focused)
        }
    }

    override fun onDetachedFromWindow() {
        observer?.let {
            if (it.isAlive) {
                it.removeOnGlobalFocusChangeListener(focusListener)
            }
        }
        observer = null
        removeCallbacks(followRunnable)
        pendingTarget = null
        cancelAnimator()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!visible) {
            return
        }
        // 容器尺寸变化后原坐标失效：下一帧直接重新对齐，不走动画
        val focused = findFocus()
        if (focused == null || !isSelfOrDescendant(focused)) {
            hideIndicator()
            return
        }
        post { snapTo(focused) }
    }

    /**
     * 跨容器接力的共享状态。
     *
     * 单个容器只能看到自己子树里的焦点，焦点跨容器（上下换行、换列）时会先隐藏、
     * 再在新容器里重新出现。把同页多个容器绑到同一个 Group 后，隐藏前的外形会以
     * 窗口坐标记下来，新容器接手时换算回自己的坐标系当作动画起点，
     * 视觉上就是一框飞过整页。
     *
     * 一个页面创建一个实例即可，随 Activity 一起回收，无需手动清理；
     * 隐藏是同步发生的而跟随是 post 出去的，所以容器之间的回调先后顺序不影响接力。
     */
    class Group {

        /** 最近一次离开时的外形（窗口坐标） */
        private val windowRect = RectF()
        private var valid = false

        internal fun save(localRect: RectF, windowLocation: IntArray) {
            windowRect.set(localRect)
            windowRect.offset(windowLocation[0].toFloat(), windowLocation[1].toFloat())
            valid = true
        }

        internal fun restore(out: RectF, windowLocation: IntArray): Boolean {
            if (!valid || windowRect.isEmpty) {
                return false
            }
            out.set(windowRect)
            out.offset(-windowLocation[0].toFloat(), -windowLocation[1].toFloat())
            return true
        }
    }

    /**
     * 单层视觉配置。链式调用，配置即刻生效。
     */
    inner class Layer internal constructor(private val target: FocusIndicatorView) {

        var enabled = false
            private set

        private var hasFill = false
        private var fillColor = Color.TRANSPARENT
        private var hasStroke = false
        private var strokeColor = Color.TRANSPARENT
        private var strokeWidthPx = 0f
        private var hasGlow = false
        private var glowColor = Color.TRANSPARENT
        private var glowWidthPx = 0f

        /** item 外扩距离(px)：正值表示指示器比 item 大一圈 */
        internal var paddingPx = 0f
            private set

        /** 圆角半径(px) */
        internal var cornerRadiusPx = 0f
            private set

        /** 圆角实心底色。传 [Color.TRANSPARENT] 表示关闭 */
        fun fill(color: Int): Layer {
            hasFill = Color.alpha(color) != 0
            fillColor = color
            applyStyle()
            return this
        }

        /** 描边。传 [Color.TRANSPARENT] 或 0 宽度表示关闭 */
        fun stroke(color: Int, widthPx: Float): Layer {
            hasStroke = Color.alpha(color) != 0 && widthPx > 0
            strokeColor = color
            strokeWidthPx = widthPx
            applyStyle()
            return this
        }

        /** 外发光。低端设备上建议半径 <= 8px、颜色 alpha <= 0x40，避免混合层过重 */
        fun glow(color: Int, widthPx: Float): Layer {
            hasGlow = Color.alpha(color) != 0 && widthPx > 0
            glowColor = color
            glowWidthPx = widthPx
            applyStyle()
            return this
        }

        /** 相对 item 的外扩距离(px) */
        fun padding(px: Float): Layer {
            paddingPx = px
            refresh()
            return this
        }

        /** 圆角半径(px) */
        fun corner(px: Float): Layer {
            cornerRadiusPx = px
            refresh()
            return this
        }

        /** 独立开关，可临时整体关闭某一层 */
        fun enable(enable: Boolean): Layer {
            enabled = enable
            if (!enable) {
                target.clear()
            } else {
                refresh()
            }
            return this
        }

        private fun applyStyle() {
            enabled = hasFill || hasStroke || hasGlow
            target.setStyle(
                hasFill, fillColor,
                hasStroke, strokeColor, strokeWidthPx,
                hasGlow, glowColor, glowWidthPx
            )
            if (!enabled) {
                target.clear()
            }
        }

        /** 配置变化后立即按当前外形重绘 */
        private fun refresh() {
            if (visible && !currentRect.isEmpty) {
                drawLayer(this, target)
            }
        }
    }

    private companion object {
        /** 默认动画时长(ms) */
        const val DEFAULT_DURATION = 220L

        /**
         * 尺寸进度相对位移进度的滞后比例：
         * 位移全程走完，尺寸在最后 (1 - SIZE_LAG) 段完成。
         * 0 表示同步变形；调大会更明显地"先飞过去、再长大"。
         */
        const val SIZE_LAG = 0.3f

        /**
         * 飞框动画结束后的贴合观察窗口(ms)。
         * 业务方的放大动画（ViewPropertyAnimator 默认 300ms）往往比飞框(220ms)长，
         * 或者是拿到焦点后才启动的，飞框收尾时 item 还在长，需要再跟一小段。
         */
        const val SETTLE_TIMEOUT = 700L

        /** 判定外形已稳定的阈值(px) */
        const val SETTLE_EPSILON = 0.5f

        /** 连续多少帧外形不变就认为稳定，退出贴合循环 */
        const val SETTLE_STABLE_FRAMES = 2

        fun isNear(a: RectF, b: RectF): Boolean =
            kotlin.math.abs(a.left - b.left) <= SETTLE_EPSILON &&
                kotlin.math.abs(a.top - b.top) <= SETTLE_EPSILON &&
                kotlin.math.abs(a.right - b.right) <= SETTLE_EPSILON &&
                kotlin.math.abs(a.bottom - b.bottom) <= SETTLE_EPSILON
    }
}
