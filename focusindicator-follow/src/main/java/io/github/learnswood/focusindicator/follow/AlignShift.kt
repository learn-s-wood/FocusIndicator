/**
 * @author learn-s-wood
 * @date 2026-11-19
 */
package io.github.learnswood.focusindicator.follow

/**
 * 列表安置焦点项带来的位置平移，按容器坐标分轴，正数往右 / 往下
 */
data class AlignShift(val dx: Int, val dy: Int)