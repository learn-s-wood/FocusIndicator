/**
 * @author learn-s-wood
 * @date 2026-11-20
 */
package io.github.learnswood.focusindicator.follow

import androidx.recyclerview.widget.RecyclerView

/**
 * 按列表选安置策略，返回 null 表示该列表不归控制器管。
 * Kotlin 用 AlignerProvider { ... } 或 object 实现，Java 直接 implements 或传 lambda。
 */
fun interface AlignerProvider {
    fun alignerFor(list: RecyclerView): ListAligner?
}