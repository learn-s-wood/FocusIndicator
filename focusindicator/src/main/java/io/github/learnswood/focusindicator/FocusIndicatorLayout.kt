/**
 * @author learn-s-wood
 * @date 2026-11-17
 */
package io.github.learnswood.focusindicator

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.annotation.TargetApi
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.max

/**
 * 焦点飞框容器：焦点指示器从 item 里剥离出来，作为独立浮层跟随焦点移动。
 *
 * ```
 * FocusIndicatorLayout
 *   ├─ behindLayer : FocusIndicatorView   (index=0，在 item 之下，画圆角实心底色)
 *   ├─ 业务子 View  (背景保持透明即可露出底色层)
 *   └─ frontLayer  : FocusIndicatorView   (末尾，在 item 之上，画描边/光晕)
 * ```
 *
 * ```kotlin
 * container.behind().fill(0xFFFF5000).corner(px).padding(0f)          // 滑动色块
 * container.front().stroke(Color.WHITE, px).corner(px).padding(px)    // 外圈描边
 * ```
 * 子 View 只需 focusable="true"。多行/多栏的页面把各容器绑到同一个 [Group]。
 * 脏区重绘、逐帧贴合、跨容器接力怎么实现，见 docs/design.md
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

    /** 前景 drawable 内边距，避免 onMeasure 里临时分配 */
    private val foregroundPadding = Rect()

    private val interpolator = DecelerateInterpolator(1.8f)
    private val updateListener = ValueAnimator.AnimatorUpdateListener { animation ->
        applyProgress(animation.animatedValue as Float)
    }

    private var animator: ValueAnimator? = null
    private var duration = DEFAULT_DURATION

    /** 淡入/淡出动画 */
    private var fadeAnimator: ValueAnimator? = null

    /** 内容整体透明度：显示时淡入到 1，隐藏时淡出到 0 再清空绘制层 */
    private var contentAlpha = 0f

    private var fadeInDuration = DEFAULT_FADE_IN
    private var fadeOutDuration = DEFAULT_FADE_OUT

    /** 指示器当前是否已显示（用于首帧直接对齐、不做"从 0,0 飞过来"） */
    private var visible = false

    /** 自动跟随焦点，默认开启 */
    private var autoFollow = true

    /** 待跟随的目标，用于 post 后取值 */
    private var pendingTarget: View? = null

    /** 跨容器接力组，同页多个容器共享时可跨行飞行 */
    private var group: Group? = null

    /** 显式标记为不显示统一飞框的子 View（弱引用，列表回收不会泄漏） */
    private val ignoredViews: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    /** 当前正在贴合的目标；放大/位移动画期间需要逐帧重采它的外形 */
    private var currentTarget: View? = null

    /**
     * 正在飞向本容器的豁免项。这期间 currentRect 是插值出来的中间点，不是真实外形，
     * 接力点已在起飞前按目标位置写好，[hideIndicator] 不能再拿它覆盖。
     */
    private var flyingToIgnored = false

    /**
     * 本容器最近一次隐藏时的外形（容器局部坐标）。
     * 没设 [Group] 时靠它做单容器接力：从豁免项离开、或者焦点在容器内重新出现时，
     * 框能从上次的位置飞过来，而不是直接对齐。
     */
    private val lastRect = RectF()
    private var hasLastRect = false

    /** 是否逐帧贴合目标外形，默认开启 */
    private var trackTargetLayout = true

    /** 贴合循环用的临时外形与计时 */
    private val settleRect = RectF()
    private var settleStartTime = 0L
    private var settleStableFrames = 0

    private var observer: ViewTreeObserver? = null

    /**
     * 目标圆角缓存：View 或背景 Drawable 一换就失效。
     * 贴合循环逐帧调 [drawLayer]，没有缓存的话每帧都要读一次 Outline / 反射背景。
     */
    private var cornerSource: View? = null
    private var cornerSourceBackground: Drawable? = null
    private var cornerSourceValue = 0f

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
            if (!trackTargetLayout || !visible || target == null || !isSelfOrDescendant(target)) {
                return
            }
            // 目标塌陷（被折叠成 0 宽高 / 移出布局）后已无可贴合的外形，
            // 直接收起，否则会留下一个停在旧位置的悬空框
            if (target.width == 0 || target.height == 0) {
                hideIndicator()
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
            // 豁免项也走 postFollow：朝它滑过去并淡出。直接 hide 会丢掉它的位置，
            // 下次从它离开时接力组里还是更早那个 item 的坐标，框会从错误的位置起飞
            postFollow(newFocus)
        } else {
            // 焦点离开本容器
            removeCallbacks(followRunnable)
            pendingTarget = null
            if (newFocus != null && isFocusIgnored(newFocus)) {
                // 落到别的容器里的豁免项：常规跨容器本来就靠接力组交接，豁免项又没有
                // 自己的框，这里再飞过去就是多出来的一段动作。原地退场即可，
                // 但接力点要按它的位置记，否则下次从它离开时会从本容器的上一个 item 起飞
                hideAndRelayTo(newFocus)
            } else {
                hideIndicator()
            }
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

    /** 动画时长(ms)。负数按 0 处理，避免把非法值传给 [ValueAnimator] */
    fun setDuration(duration: Long) {
        this.duration = duration.coerceAtLeast(0L)
    }

    /**
     * 显示/隐藏时的淡入淡出时长(ms)，传 0 关闭淡入淡出（恢复生硬显示/隐藏）。
     * 默认淡入 180ms、淡出 140ms。
     */
    fun setFadeDuration(inDuration: Long, outDuration: Long) {
        fadeInDuration = inDuration.coerceAtLeast(0L)
        fadeOutDuration = outDuration.coerceAtLeast(0L)
    }

    /**
     * 豁免：让某个子 View（或它的整棵子树）不显示本容器的统一飞框，
     * 适合自己就有专属焦点样式的 item。焦点落到被豁免的 View 时飞框会朝它滑过去并淡出。
     *
     * 列表复用场景推荐直接在 XML 写 `android:tag="fi:ignore"`，不用维护调用；
     * 需要根据数据动态决定时用这个方法（内部弱引用，不会阻止回收）。
     */
    fun ignoreFocus(view: View?) {
        view ?: return
        ignoredViews.add(view)
        val focused = findFocus()
        if (focused != null && (focused === view || isChildOf(focused, view))) {
            removeCallbacks(followRunnable)
            pendingTarget = null
            hideIndicator()
        }
    }

    /** 取消 [ignoreFocus] 的豁免 */
    fun unignoreFocus(view: View?) {
        view ?: return
        ignoredViews.remove(view)
    }

    /**
     * 该 View 是否不参与统一飞框。
     * 命中条件：自身或它的某个祖先被 [ignoreFocus] 标记，或者带上了 [TAG_IGNORE] 这个 tag。
     * 祖先链一直遍历到根，焦点落到别的容器里的豁免项时也能识别。
     */
    fun isFocusIgnored(view: View?): Boolean {
        var current: View? = view
        while (current != null) {
            if (ignoredViews.contains(current) || current.tag == TAG_IGNORE) {
                return true
            }
            current = current.parent as? View
        }
        return false
    }

    /** [ancestor] 是否为 [view] 的祖先（不含相等） */
    private fun isChildOf(view: View, ancestor: View): Boolean {
        var current = view.parent
        while (current != null) {
            if (current === ancestor) {
                return true
            }
            current = current.parent
        }
        return false
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
     * 否则飞框只会按拿到焦点那一刻的尺寸画，最终比放大后的 item 小一圈。
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
        if (isFocusIgnored(target)) {
            // 豁免项不显示框，但位置要记下来给接力用
            rememberPosition(target)
            hideIndicator()
            return
        }
        cancelAnimator()
        currentTarget = target
        measureRect(target, fromRect)
        currentRect.set(fromRect)
        visible = true
        drawLayers()
        fadeIn()
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
        if (isFocusIgnored(target)) {
            flyToAndHide(target)
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
            // 首次显示：优先用接力组（跨容器接力），没设 Group 时用本容器上次离开的位置；
            // 都没有才直接对齐，避免首帧从 (0,0) 飞进画面
            if (!resolveStartRect()) {
                currentRect.set(toRect)
                visible = true
                drawLayers()
                fadeIn()
                // 无动画落地也要贴合：item 的放大动画可能刚开始跑
                startSettle()
                return
            }
            currentRect.set(fromRect)
            visible = true
            drawLayers()
            fadeIn()
            startAnimation()
            return
        }

        // 打断续飞：起点取当前实时外形，而不是上一帧的目标值，快速连按时不会跳变
        fromRect.set(currentRect)
        fadeIn()
        startAnimation()
    }

    /** 同容器内焦点落到豁免项：滑过去、路上淡完。跨容器走 [hideAndRelayTo]，见 docs/design.md */
    private fun flyToAndHide(target: View) {
        measureRect(target, toRect)
        if (toRect.isEmpty) {
            hideIndicator()
            return
        }
        if (!visible) {
            // 自己还没显示（焦点直接从别处落到豁免项）：只记位置，交给上一个容器的框收尾。
            // 这里不能显示自己的一套层，否则会临时冒出目标容器的样式
            rememberPosition(target, toRect)
            return
        }
        // 接力点先按目标位置写好：飞行途中 currentRect 只是插值出来的中间点，
        // 万一这段被打断（焦点又跳到本容器外），不能让它冒充接力起点
        recordRelay(toRect)
        flyingToIgnored = true
        currentTarget = target
        cancelAnimator()
        fromRect.set(currentRect)
        // 位移换用更均匀的曲线：常规 Decelerate 会把可见的那几十毫秒全塞在起步瞬间
        startAnimation(onEnd = { hideIndicator() }, animationInterpolator = HIDE_FLY_INTERPOLATOR)
        // 边滑边淡出：淡尽点绑在路程比例上，不受两个时长参数大小关系的影响。
        // fadeOutDuration 更小时仍以它为准，尊重“关掉/缩短淡出”的配置（传 0 即立即消失）
        val fadeMs = minOf((duration * HIDE_FLY_FADE_TIME_RATIO).toLong(), fadeOutDuration)
        fadeTo(0f, fadeMs, null)
    }

    /**
     * 取本次动画的起点：优先接力组（跨容器），其次本容器上次离开的位置。
     * 结果写进 [fromRect]，返回是否取到；调用前需保证 [selfLocation] 已刷新（`measureRect` 会做）。
     */
    private fun resolveStartRect(): Boolean = when {
        group?.restore(fromRect, selfLocation) == true -> !fromRect.isEmpty
        hasLastRect -> {
            fromRect.set(lastRect)
            true
        }
        else -> false
    }

    /** 把目标外形记成“下一次的起点”，不显示任何东西 */
    private fun rememberPosition(target: View, rect: RectF? = null) {
        if (rect == null) {
            measureRect(target, toRect)
            if (toRect.isEmpty) {
                return
            }
        }
        val value = rect ?: toRect
        currentRect.set(value)
        recordRelay(currentRect)
    }

    /** 把某个外形（容器局部坐标）记成接力点：同组其它容器下次从这儿起飞，未设 Group 时本容器自己用 */
    private fun recordRelay(rect: RectF) {
        getLocationInWindow(selfLocation)
        group?.save(rect, selfLocation)
        lastRect.set(rect)
        hasLastRect = true
    }

    /**
     * 跨容器落到豁免项时的收场：只原地退场，不飞过去。
     *
     * 接力点要在 [hideIndicator] 之后再写一次：前者会用当前外形覆盖，这里用豁免项的位置收尾，
     * 两个容器的回调先后顺序因此不影响结果（写的都是同一个窗口位置）。
     */
    private fun hideAndRelayTo(target: View) {
        flyingToIgnored = false
        measureRect(target, toRect)
        hideIndicator()
        if (!toRect.isEmpty) {
            recordRelay(toRect)
        }
    }

    private fun startAnimation(
        onEnd: (() -> Unit)? = null,
        animationInterpolator: TimeInterpolator = interpolator
    ) {
        val animator = ValueAnimator.ofFloat(0f, 1f)
        this.animator = animator
        animator.duration = duration
        animator.interpolator = animationInterpolator
        animator.addUpdateListener(updateListener)
        animator.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false

            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                // cancelAnimator() 会同步走到 onAnimationEnd，这里只处理自然结束
                if (!cancelled) {
                    if (onEnd != null) {
                        onEnd()
                    } else {
                        startSettle()
                    }
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
        // 飞向豁免项期间 currentRect 是插值出来的中间点，接力点已在起飞前写好，
        // 这里不能再拿它覆盖（飞行途中焦点跳到别处会走到这里）
        if (!currentRect.isEmpty && !flyingToIgnored) {
            // 记下离开前的外形（窗口坐标），同组其它容器接手时从这儿起飞；
            // 隐藏是焦点回调里同步发生的，此时各组件的窗口位置都还是有效的。
            // 同时记一份容器局部坐标，没设 Group 时也能做单容器接力。
            recordRelay(currentRect)
        }
        flyingToIgnored = false
        currentTarget = null
        // 不立即擦掉：保留最后一帧外形淡出，动画结束后在 [fadeOut] 的收尾里清空
        fadeOut()
    }

    private fun cancelAnimator() {
        stopSettle()
        animator?.let {
            it.removeUpdateListener(updateListener)
            it.cancel()
        }
        animator = null
    }

    /** 淡入到完全不透明 */
    private fun fadeIn() {
        fadeTo(1f, fadeInDuration)
    }

    /**
     * 淡出后清空。若淡出过程中又来了新目标，[fadeTo] 会取消本动画，
     * 收尾回调不会执行，绘制层保留给淡入继续用。
     */
    private fun fadeOut() {
        fadeTo(0f, fadeOutDuration) {
            currentRect.setEmpty()
            behindView.clear()
            frontView.clear()
        }
    }

    private fun fadeTo(target: Float, duration: Long, onEnd: (() -> Unit)? = null) {
        fadeAnimator?.cancel()
        fadeAnimator = null
        if (duration <= 0L || contentAlpha == target) {
            contentAlpha = target
            applyContentAlpha()
            onEnd?.invoke()
            return
        }
        val animator = ValueAnimator.ofFloat(contentAlpha, target)
        fadeAnimator = animator
        animator.duration = duration
        animator.addUpdateListener { animation ->
            contentAlpha = animation.animatedValue as Float
            applyContentAlpha()
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false

            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (fadeAnimator === animation) {
                    fadeAnimator = null
                }
                if (!cancelled) {
                    onEnd?.invoke()
                }
            }
        })
        animator.start()
    }

    private fun applyContentAlpha() {
        behindView.setContentAlpha(contentAlpha)
        frontView.setContentAlpha(contentAlpha)
    }

    /**
     * 按进度插值当前外形并重绘。
     * 位移用完整进度，尺寸用滞后进度，避免大尺寸差异时中间形状扭曲。
     */
    private fun applyProgress(progress: Float) {
        // 逐帧重采目标外形：业务方在焦点态做放大动画时，item 的真实外形在飞框动画期间还在变，
        // 用启动瞬间的静态快照会让框最终小于放大后的 item，看起来就是框不住
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
        view.update(layerRect, resolveCorner(layer))
    }

    /**
     * 计算某一层实际使用的圆角。
     *
     * 显式 [Layer.corner] 优先；未设置时跟随目标 View 自身的圆角：
     * 圆角矩形向外平移 [Layer.padding] 后，圆角会同步变大，所以自动模式下按
     * `目标圆角 + padding` 计算；目标本身是直角则保持直角，不会凭空变圆。
     */
    private fun resolveCorner(layer: Layer): Float {
        val explicit = layer.cornerRadiusPx
        if (explicit != null) {
            return explicit
        }
        val target = currentTarget ?: return 0f
        val base = targetCornerOf(target)
        if (base <= 0f) {
            return 0f
        }
        // item 焦点态放大时圆角也会跟着放大，取两轴较小值避免框比 item 更圆
        val scale = minOf(target.scaleX, target.scaleY)
        return max(0f, base * scale + layer.paddingPx)
    }

    /**
     * 读取目标 View 自身的圆角(px)，取不到返回 0。
     *
     * 先问 Outline（API 24+；getRadius 在 21~23 上不存在），
     * 再退回背景 Drawable（GradientDrawable / Ripple / LayerDrawable 包装）。
     * 结果按 View + 背景引用缓存：同一个 item 逐帧贴合时不重复反射。
     */
    private fun targetCornerOf(target: View): Float {
        val background = target.background
        if (target === cornerSource && background === cornerSourceBackground) {
            return cornerSourceValue
        }
        var value = 0f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            value = outlineCorner(target)
        }
        if (value <= 0f) {
            value = readDrawableCorner(background)
        }
        cornerSource = target
        cornerSourceBackground = background
        cornerSourceValue = value
        return value
    }

    @TargetApi(Build.VERSION_CODES.N)
    private fun outlineCorner(target: View): Float {
        val provider = target.outlineProvider ?: return 0f
        val outline = Outline()
        provider.getOutline(target, outline)
        return if (outline.isEmpty) 0f else outline.radius
    }

    /** 递归向下找背景里真正画圆角的那一层 */
    private fun readDrawableCorner(drawable: Drawable?): Float {
        var current = drawable
        var depth = 0
        while (current != null && depth < MAX_CORNER_LOOKUP_DEPTH) {
            when {
                current is GradientDrawable -> return gradientCorner(current)
                // Ripple / Layer 通常只包一层 shape，取第一层即可
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && current is RippleDrawable ->
                    current = current.getDrawable(0)
                current is LayerDrawable -> current = current.getDrawable(0)
                else -> return 0f
            }
            depth++
        }
        return 0f
    }

    private fun gradientCorner(drawable: GradientDrawable): Float {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val radii = drawable.cornerRadii
            if (radii != null) {
                val value = minPositive(radii)
                if (value > 0f) {
                    return value
                }
            }
            return drawable.cornerRadius
        }
        return reflectGradientCorner(drawable)
    }

    /**
     * API 17~23 没有公开的圆角读取接口，只能反射 GradientDrawable 的内部状态。
     * 读不到时静默返回 0（按直角绘制），不影响飞框的其它功能。
     */
    @Suppress("PrivateAPI")
    private fun reflectGradientCorner(drawable: GradientDrawable): Float {
        return try {
            val stateField = GradientDrawable::class.java.getDeclaredField("mGradientState")
            stateField.isAccessible = true
            val state = stateField.get(drawable) ?: return 0f

            val radiiField = state.javaClass.getDeclaredField("mRadiusArray")
            radiiField.isAccessible = true
            val radii = radiiField.get(state) as? FloatArray
            if (radii != null) {
                val value = minPositive(radii)
                if (value > 0f) {
                    return value
                }
            }

            val radiusField = state.javaClass.getDeclaredField("mRadius")
            radiusField.isAccessible = true
            (radiusField.get(state) as? Float) ?: 0f
        } catch (ignored: Throwable) {
            0f
        }
    }

    /** 四角半径可能不同（也可能只有部分角设置），取最小正值，避免框比 item 更圆 */
    private fun minPositive(values: FloatArray): Float {
        var min = Float.MAX_VALUE
        for (value in values) {
            if (value > 0f && value < min) {
                min = value
            }
        }
        return if (min == Float.MAX_VALUE) 0f else min
    }

    /**
     * 算 item 在本容器坐标系下的外形。getLocationInWindow 已应用过 item 的 matrix，只能当视觉左上角用；
     * 再按 layout 中心算一遍会把外扩加两次，框整体偏 `(scale - 1) * 尺寸 / 2`。
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
     * 两个绘制层必须铺满容器才能画到任意位置，但它们不能参与撑开容器的计算：
     * 否则 wrap_content 的容器会按 MATCH_PARENT 子 View 去要父容器全部可用尺寸，
     * 页面上就会多出一大块空白（业务子 View 被顶到容器底部）。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var contentWidth = 0
        var contentHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            // GONE 的子 View 不参与尺寸计算（原生 FrameLayout 也跳过它们），
            // 否则 MATCH_PARENT 的隐藏子 View 会把 wrap_content 容器撑满父级
            if (child === behindView || child === frontView || child.visibility == GONE) {
                continue
            }
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val lp = child.layoutParams as MarginLayoutParams
            contentWidth = max(contentWidth, child.measuredWidth + lp.leftMargin + lp.rightMargin)
            contentHeight = max(contentHeight, child.measuredHeight + lp.topMargin + lp.bottomMargin)
        }

        // 绕过原生 onMeasure 后需自己补两件事，否则与原 FrameLayout 行为不一致：
        // 1) android:foreground + FILL 占用的内边距；2) android:minWidth/minHeight
        val fg = foreground
        if (fg != null && (foregroundGravity and Gravity.FILL) != 0 &&
            fg.getPadding(foregroundPadding)
        ) {
            contentWidth += foregroundPadding.left + foregroundPadding.right
            contentHeight += foregroundPadding.top + foregroundPadding.bottom
        }

        contentWidth += paddingLeft + paddingRight
        contentHeight += paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(max(contentWidth, suggestedMinimumWidth), widthMeasureSpec),
            resolveSize(max(contentHeight, suggestedMinimumHeight), heightMeasureSpec)
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
        fadeAnimator?.cancel()
        fadeAnimator = null
        // 静默复位：容器被移出窗口（页面切换、列表复用）后可能带着旧外形重新挂回来，
        // 若此时焦点不在容器内，会残留一个停在屏幕上的飞框。
        // 这里不能走 hideIndicator()，否则会往 Group 里写一个已失效的位置
        visible = false
        currentTarget = null
        currentRect.setEmpty()
        // 局部坐标随卸载失效，不能留给下次挂载当接力起点
        hasLastRect = false
        lastRect.setEmpty()
        contentAlpha = 0f
        applyContentAlpha()
        behindView.clear()
        frontView.clear()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 容器尺寸一变，局部坐标就失效了：清掉单容器接力起点，避免从错位置飞进来
        hasLastRect = false
        lastRect.setEmpty()
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
     * 跨容器接力的共享状态：隐藏前的外形按窗口坐标记下，新容器换算回自己的坐标系当起点。
     * 一个页面一个实例，随 Activity 回收。细节见 docs/design.md
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
        private var glowLayers = DEFAULT_GLOW_LAYERS

        /** item 外扩距离(px)：正值表示指示器比 item 大一圈 */
        internal var paddingPx = 0f
            private set

        /** 圆角半径(px)；null 表示跟随目标 View 自身的圆角 */
        internal var cornerRadiusPx: Float? = null
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

        /**
         * 外发光。API 19 的机器上建议半径 <= 8px、颜色 alpha <= 0x40，避免混合层过重。
         *
         * 硬件加速下用不了 `setShadowLayer` / `BlurMaskFilter`，发光是用一圈圈半透明描边堆出来的：
         * [layers] 就是环数，环越多过渡越接近渐变，代价是每帧多画一圈。不传时用默认值
         * （[DEFAULT_GLOW_LAYERS]），环少时能看出几层色带和硬边。
         */
        @JvmOverloads
        fun glow(color: Int, widthPx: Float, layers: Int = DEFAULT_GLOW_LAYERS): Layer {
            hasGlow = Color.alpha(color) != 0 && widthPx > 0
            glowColor = color
            glowWidthPx = widthPx
            glowLayers = layers
            applyStyle()
            return this
        }

        /**
         * 相对 item 的外扩距离(px)，默认 0。
         *
         * 正值把框推到 item 外面：描边内侧与 item 之间的空隙 = `padding - 描边宽度`，
         * 想看到明显间隔就让它大于描边宽度。负值表示内缩到 item 内侧。
         */
        fun padding(px: Float): Layer {
            paddingPx = px
            refresh()
            return this
        }

        /**
         * 圆角半径(px)。
         *
         * 不调用时自动跟随目标 View 的圆角（背景是圆角矩形 / 有圆角 Outline 时），
         * 不同 item 圆角不一样也不用逐个配；传负数等价于 [cornerAuto]。
         */
        fun corner(px: Float): Layer {
            cornerRadiusPx = if (px < 0f) null else px
            refresh()
            return this
        }

        /** 切回自动跟随目标圆角 */
        fun cornerAuto(): Layer {
            cornerRadiusPx = null
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
                hasGlow, glowColor, glowWidthPx, glowLayers
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

    companion object {

        /**
         * 把子 View 的 tag 设为该字符串，就不显示本容器的统一飞框。
         * XML 可直接写 `android:tag="fi:ignore"`，代码里用 `view.tag = FocusIndicatorLayout.TAG_IGNORE`。
         * 带此 tag 的 View 的整棵子树同样被豁免。
         */
        const val TAG_IGNORE = "fi:ignore"

        /** 默认淡入时长(ms) */
        private const val DEFAULT_FADE_IN = 180L

        /** 默认淡出时长(ms) */
        private const val DEFAULT_FADE_OUT = 140L

        /** 默认动画时长(ms) */
        private const val DEFAULT_DURATION = 220L

        /** 飞向豁免项时，淡出在路程走到约 2/3 处完成（不是时间 2/3）。p = 2/3 换算成 AccelerateDecelerate 的时间约 0.61 */
        private const val HIDE_FLY_FADE_TIME_RATIO = 0.61f

        /**
         * 飞向豁免项的专用位移缓动。
         *
         * 常规飞框用 [DecelerateInterpolator]\\(1.8f\\)（指数是 2 × 1.8 = 3.6，前 1/4 时间就跑完
         * 2/3 路程），用它的话“淡尽在路程 2/3”只剩约 57ms 可见时间，观感接近闪一下就没。
         * 这段改用更均匀的曲线后，同样停在同一路程上，可见时间能拉到约 130ms，退场才像“飞走”。
         */
        private val HIDE_FLY_INTERPOLATOR: TimeInterpolator = AccelerateDecelerateInterpolator()

        /**
         * 光晕默认的分环数：越多过渡越平滑，每环一次 drawRoundRect。
         * [FocusIndicatorView.setStyle] 的默认参数也取这个值，只有一份。
         */
        internal const val DEFAULT_GLOW_LAYERS = 6

        /** 自动圆角最多向下找几层背景 Drawable */
        private const val MAX_CORNER_LOOKUP_DEPTH = 3

        /**
         * 尺寸进度相对位移进度的滞后比例：
         * 位移全程走完，尺寸在最后 (1 - SIZE_LAG) 段完成。
         * 0 表示同步变形；调大会更明显地"先飞过去、再长大"。
         */
        private const val SIZE_LAG = 0.3f

        /**
         * 飞框动画结束后的贴合观察窗口(ms)。
         * 业务方的放大动画（ViewPropertyAnimator 默认 300ms）往往比飞框(220ms)长，
         * 或者是拿到焦点后才启动的，飞框收尾时 item 还在长，需要再跟一小段。
         */
        private const val SETTLE_TIMEOUT = 700L

        /** 判定外形已稳定的阈值(px) */
        private const val SETTLE_EPSILON = 0.5f

        /** 连续多少帧外形不变就认为稳定，退出贴合循环 */
        private const val SETTLE_STABLE_FRAMES = 2

        private fun isNear(a: RectF, b: RectF): Boolean =
            kotlin.math.abs(a.left - b.left) <= SETTLE_EPSILON &&
                kotlin.math.abs(a.top - b.top) <= SETTLE_EPSILON &&
                kotlin.math.abs(a.right - b.right) <= SETTLE_EPSILON &&
                kotlin.math.abs(a.bottom - b.bottom) <= SETTLE_EPSILON
    }
}
