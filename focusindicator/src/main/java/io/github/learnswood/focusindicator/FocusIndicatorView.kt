/**
 * @author learn-s-wood
 * @date 2026-11-17
 */
package io.github.learnswood.focusindicator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * 单层焦点指示器：只画一个圆角矩形，不参与焦点与布局。
 * 底色、描边、外发光可任意组合；外形由 [update] 逐帧传入，宽高是真实重绘而不是 scale 拉伸。
 * 脏区策略见 docs/design.md
 */
class FocusIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 当前外形（已包含 padding 外扩），由 [FocusIndicatorLayout] 逐帧更新 */
    private val rect = RectF()

    /** onDraw 里的临时外形，避免每帧分配 */
    private val tmpRect = RectF()

    /** update 里计算脏区用的临时对象 */
    private val dirty = Rect()

    private var cornerRadius = 0f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var drawFill = false
    private var drawStroke = false
    private var drawGlow = false
    private var strokeWidth = 0f
    private var glowWidth = 0f

    /** 光晕的基准 alpha、环数，以及由它们算出的总厚度 / 单环厚度（setStyle 时定好） */
    private var glowBaseAlpha = 0
    private var glowLayers = 1
    private var glowTotal = 0f
    private var glowBand = 0f

    /** 填充/描边的原始 alpha，onDraw 时与 [contentAlpha] 相乘，用于淡入淡出 */
    private var fillBaseAlpha = 0
    private var strokeBaseAlpha = 0

    /** 内容整体透明度（0~1），由容器在显示/隐藏时驱动 */
    private var contentAlpha = 1f

    init {
        // 纯绘制层：不抢焦点、不响应点击、不参与无障碍导航
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        strokePaint.style = Paint.Style.STROKE
        // 光晕同样只描边；环与环之间要圆角相接，否则每环的接缝处会露出尖角
        glowPaint.style = Paint.Style.STROKE
        glowPaint.strokeJoin = Paint.Join.ROUND
        glowPaint.isDither = true
    }

    /**
     * 更新绘制样式。仅在配置变化时调用，动画过程中不调用。
     *
     * `glowLayers` 带默认值：1.0.0 里按 8 个参数调用本方法的代码不会断（Java 侧靠
     * [JvmOverloads] 生成 8 参重载）。
     */
    @JvmOverloads
    fun setStyle(
        hasFill: Boolean,
        fillColor: Int,
        hasStroke: Boolean,
        strokeColor: Int,
        strokeWidthPx: Float,
        hasGlow: Boolean,
        glowColor: Int,
        glowWidthPx: Float,
        glowLayers: Int = FocusIndicatorLayout.DEFAULT_GLOW_LAYERS
    ) {
        drawFill = hasFill
        drawStroke = hasStroke
        strokeWidth = strokeWidthPx
        drawGlow = hasGlow
        glowWidth = glowWidthPx
        glowBaseAlpha = Color.alpha(glowColor)
        this.glowLayers = glowLayers.coerceAtLeast(1)
        // 总厚度沿用 2×width：width 参数的语义保持不变；环宽只在这里算一次
        glowTotal = glowWidthPx * 2f
        glowBand = glowTotal / this.glowLayers

        fillBaseAlpha = Color.alpha(fillColor)
        strokeBaseAlpha = Color.alpha(strokeColor)

        fillPaint.color = fillColor
        strokePaint.color = strokeColor
        strokePaint.strokeWidth = strokeWidthPx
        glowPaint.color = glowColor
        glowPaint.strokeWidth = glowBand

        invalidate()
    }

    /**
     * 更新外形（动画每帧调用）。
     *
     * @param target 目标外形，坐标为本 View 坐标系
     * @param radius 圆角半径(px)
     */
    @Suppress("DEPRECATION") // 局部刷新的 4 参 invalidate 自 API 28 起废弃，但它是 API 17 上唯一的脏区重绘入口
    fun update(target: RectF, radius: Float) {
        computeDirty(rect, target)
        rect.set(target)
        cornerRadius = radius
        if (dirty.isEmpty) {
            invalidate()
        } else {
            invalidate(dirty.left, dirty.top, dirty.right, dirty.bottom)
        }
    }

    /**
     * 清空并隐藏（焦点离开时调用）。
     */
    fun clear() {
        if (rect.isEmpty) {
            return
        }
        invalidateRect()
        rect.setEmpty()
    }

    /**
     * 设置整体透明度（0~1），用于淡入淡出。
     *
     * 用 Paint 的 alpha 而不是 View.alpha：后者会让铺满容器的整个浮层全量重绘，
     * 而这里只把当前外形那一小块脏区标脏，API 19 的机器上开销差一个数量级。
     */
    fun setContentAlpha(alpha: Float) {
        val value = alpha.coerceIn(0f, 1f)
        if (contentAlpha == value) {
            return
        }
        contentAlpha = value
        if (rect.isEmpty) {
            invalidate()
        } else {
            invalidateRect()
        }
    }

    /** 只标脏当前外形（含描边/光晕外扩）所在矩形 */
    private fun invalidateRect() {
        val pad = max(strokeWidth, glowWidth * 2f) + ANTI_ALIAS_PAD
        dirty.set(
            floor(rect.left - pad).toInt(),
            floor(rect.top - pad).toInt(),
            ceil(rect.right + pad).toInt(),
            ceil(rect.bottom + pad).toInt()
        )
        invalidate(dirty.left, dirty.top, dirty.right, dirty.bottom)
    }

    /** 是否有可绘制内容 */
    fun hasIndicator(): Boolean = !rect.isEmpty && (drawFill || drawStroke || drawGlow)

    /**
     * 计算脏区：旧外形与新外形的并集，再按描边/光晕外扩量放大。
     * 两个矩形都可能为空，需要分别兜底。
     */
    private fun computeDirty(oldRect: RectF, newRect: RectF) {
        val pad = max(strokeWidth, glowWidth * 2f) + ANTI_ALIAS_PAD

        val left: Float
        val top: Float
        val right: Float
        val bottom: Float
        if (oldRect.isEmpty) {
            left = newRect.left
            top = newRect.top
            right = newRect.right
            bottom = newRect.bottom
        } else if (newRect.isEmpty) {
            left = oldRect.left
            top = oldRect.top
            right = oldRect.right
            bottom = oldRect.bottom
        } else {
            left = minOf(oldRect.left, newRect.left)
            top = minOf(oldRect.top, newRect.top)
            right = maxOf(oldRect.right, newRect.right)
            bottom = maxOf(oldRect.bottom, newRect.bottom)
        }

        dirty.set(
            floor(left - pad).toInt(),
            floor(top - pad).toInt(),
            ceil(right + pad).toInt(),
            ceil(bottom + pad).toInt()
        )

        // 不把脏区裁剪到自身范围：跨容器接力时起始外形落在容器外面（比如从上一行飞下来，
        // 起始 Y 为负），裁掉的话那块旧像素不会被重绘，飞行会变成从容器边缘"长出来"。
        // 越界坐标交给父容器逐层合并，窗级别会自动限制在屏幕内。
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rect.isEmpty) {
            return
        }

        if (drawGlow && glowWidth > 0) {
            // 同心环近似径向衰减：API 19 上用不了 setShadowLayer / BlurMaskFilter（会退化为软件绘制），
            // 只能堆几何。环太少时每环都是常量 alpha，肉眼会看出“几层色带 + 硬边”，
            // 所以把总厚度切成 glowLayers 段、alpha 随距离线性衰减到接近 0，段数越多越像渐变。
            // 颜色/描边/环宽已在 setStyle 里定好，这里每帧只逐环改 alpha
            val halfBand = glowBand / 2f
            // 从外向内画：各环区间互不重叠，避免叠加把内侧那档抬亮
            var i = glowLayers - 1
            while (i >= 0) {
                // 该环描边路径（环中心）到 item 边缘的距离
                val center = i * glowBand + halfBand
                glowPaint.alpha = (glowBaseAlpha * (1f - center / glowTotal) * contentAlpha).toInt()
                tmpRect.set(rect)
                tmpRect.inset(-center, -center)
                canvas.drawRoundRect(
                    tmpRect,
                    cornerRadius + center,
                    cornerRadius + center,
                    glowPaint
                )
                i--
            }
        }

        if (drawFill) {
            fillPaint.alpha = (fillBaseAlpha * contentAlpha).toInt()
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)
        }

        if (drawStroke && strokeWidth > 0) {
            strokePaint.alpha = (strokeBaseAlpha * contentAlpha).toInt()
            // 描边向内收缩半个线宽，避免一半线宽画到矩形外面被裁掉
            val half = strokeWidth / 2f
            tmpRect.set(rect)
            tmpRect.inset(half, half)
            val r = maxOf(0f, cornerRadius - half)
            canvas.drawRoundRect(tmpRect, r, r, strokePaint)
        }
    }

    private companion object {
        /** 抗锯齿会向矩形边缘外溢出不到 1px，脏区外扩一点避免残留半像素 */
        const val ANTI_ALIAS_PAD = 2
    }
}
