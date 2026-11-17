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
 * 单层焦点指示器：只负责把一个圆角矩形画出来，不参与焦点与布局。
 *
 * 支持三种视觉叠加，可任意组合：圆角实心底色、描边、外发光。
 * 变形动画通过 [update] 逐帧传入真实外形，宽高变化是真实重绘，
 * 不是 scale 缩放 —— 因此圆角不会椭圆化、描边粗细不会随拉伸比例变化。
 *
 * 每帧只标脏"上一帧 ∪ 当前帧"的外接矩形，低端设备上重绘面积可以压到全屏的几十分之一，
 * 这是 API 17 老盒子也能跑满帧的关键。
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

    /** 光晕原色与其两层 alpha，缓存下来避免每帧在 paint 上反复读写导致 alpha 逐渐衰减 */
    private var glowColorValue = Color.TRANSPARENT
    private var glowAlphaInner = 0
    private var glowAlphaOuter = 0

    init {
        // 纯绘制层：不抢焦点、不响应点击、不参与无障碍导航
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        strokePaint.style = Paint.Style.STROKE
    }

    /**
     * 更新绘制样式。仅在配置变化时调用，动画过程中不调用。
     */
    fun setStyle(
        hasFill: Boolean,
        fillColor: Int,
        hasStroke: Boolean,
        strokeColor: Int,
        strokeWidthPx: Float,
        hasGlow: Boolean,
        glowColor: Int,
        glowWidthPx: Float
    ) {
        drawFill = hasFill
        drawStroke = hasStroke
        strokeWidth = strokeWidthPx
        drawGlow = hasGlow
        glowWidth = glowWidthPx
        glowColorValue = glowColor
        glowAlphaInner = Color.alpha(glowColor)
        glowAlphaOuter = glowAlphaInner / 2

        fillPaint.color = fillColor
        strokePaint.color = strokeColor
        strokePaint.strokeWidth = strokeWidthPx
        glowPaint.color = glowColor

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
        rect.setEmpty()
        invalidate()
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
            // 两层半透明外扩模拟发光：低端设备禁用 setShadowLayer(会退化为软件绘制)
            glowPaint.color = glowColorValue

            glowPaint.alpha = glowAlphaOuter
            tmpRect.set(rect)
            tmpRect.inset(-glowWidth * 2f, -glowWidth * 2f)
            canvas.drawRoundRect(
                tmpRect,
                cornerRadius + glowWidth * 2f,
                cornerRadius + glowWidth * 2f,
                glowPaint
            )

            glowPaint.alpha = glowAlphaInner
            tmpRect.set(rect)
            tmpRect.inset(-glowWidth, -glowWidth)
            canvas.drawRoundRect(
                tmpRect,
                cornerRadius + glowWidth,
                cornerRadius + glowWidth,
                glowPaint
            )
        }

        if (drawFill) {
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)
        }

        if (drawStroke && strokeWidth > 0) {
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
