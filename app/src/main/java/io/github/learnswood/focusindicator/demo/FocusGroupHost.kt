/**
 * @author learn-s-wood
 * @date 2026-11-19
 */
package io.github.learnswood.focusindicator.demo

import io.github.learnswood.focusindicator.FocusIndicatorLayout
import io.github.learnswood.focusindicator.demo.focus.FocusRegion

/**
 * 内容页与宿主之间的约定。
 *
 * 内容页不关心 tab 条怎么实现，只知道两件事：从宿主拿共享的飞框接力组，
 * 以及一个能给出落点的 tab 区。
 */
interface FocusGroupHost {

    /** 全页共享的飞框接力组 */
    val focusGroup: FocusIndicatorLayout.Group

    /** 顶部 tab 区；内容区往上时向它要落点 */
    fun tabRegion(): FocusRegion
}
