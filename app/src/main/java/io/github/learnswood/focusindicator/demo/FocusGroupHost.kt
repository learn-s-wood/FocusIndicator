/**
 * @author learn-s-wood
 * @date 2026-11-19
 */
package io.github.learnswood.focusindicator.demo

import io.github.learnswood.focusindicator.FocusIndicatorLayout
import io.github.learnswood.focusindicator.demo.focus.FocusHelper

/**
 * 内容页与宿主之间的约定。
 *
 * 内容页不关心 tab 条怎么实现，只知道两件事：从宿主拿共享的飞框接力组，
 * 以及页面级的焦点策略 helper。
 */
interface FocusGroupHost {

    /** 全页共享的飞框接力组 */
    val focusGroup: FocusIndicatorLayout.Group

    /** 页面级焦点策略：记忆、落点、跨区交接 */
    fun focusHelper(): FocusHelper
}
