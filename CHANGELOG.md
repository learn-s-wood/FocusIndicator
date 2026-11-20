# 变更记录

版本号与 GitHub 上的 tag 一一对应，不带 `v` 前缀。JitPack 坐标：

```kotlin
implementation("com.github.learn-s-wood:FocusIndicator:<tag>")
```

## 1.2.0 — 2026-11-20

### 新增

- `followToOffset(view, dx, dy)`：飞到 view 平移 (dx, dy) 后的位置；位置固定，宽高仍跟随目标实时外形。
- 可选伴生模块 `focusindicator-follow`：滚动列表「焦点不动、列表动」。`FollowController` 编排 + `ListAligner` 安置策略接口 + `GravityAligner`（居中/居左/居右/居上/居下 + 边距）+ `AlignerProvider` + `AlignShift`。坐标 `com.github.learn-s-wood.FocusIndicator:focusindicator-follow:<tag>`。

## 1.1.0 — 2026-11-18

### 新增

- 单个 item 可以退出飞框：XML 用 `android:tag="fi:ignore"`（常量 `FocusIndicatorLayout.TAG_IGNORE`），代码用 `ignoreFocus(view)` / `unignoreFocus(view)`，查询用 `isFocusIgnored(view)`。带 tag 的整棵子树一起豁免。
- `setFadeDuration(fadeInMs, fadeOutMs)`，默认 180 / 140ms，传 `0` 关掉。
- 圆角自动跟随 item：不调 `corner()` 时读 item 自身的圆角，再叠上外扩量。`cornerAuto()` 切回自动。

### 修复

- 外发光染进 item 内部。两层半透明实心矩形改成 `STROKE` 环。
- 光晕只有两档色带、外缘一刀切。改成 `glow()` 的 `layers` 个互不重叠的同心环，默认 6 环。
- 容器移出窗口后残留飞框。`onDetachedFromWindow` 取消动画并清空绘制层与当前目标。
- 焦点项塌陷成 0 尺寸时飞框不消失。0 宽或 0 高直接收起。
- `GONE` 子 View 撑大容器。`onMeasure` 跳过它们，和原生 `FrameLayout` 一致。
- `android:foreground` 与 `minWidth` / `minHeight` 被忽略。`onMeasure` 补齐。

### 改进

- `setStyle()` 的 `glowLayers` 带默认值并加 `@JvmOverloads`，1.0.0 的 8 参调用不会断。
- `setDuration()` / `setFadeDuration()` 对负数按 0 处理。
- `clear()` 只标脏自身外形所在矩形，不再整块重绘。
- `padding()` 的语义：描边内侧与 item 的空隙 = `padding - 描边宽度`，负值内缩，默认 `0` 贴紧 item。

### 升级说明

- 原有调用不用改。
- 默认带了淡入淡出，要硬切调 `setFadeDuration(0, 0)`。
- 没调 `corner()` 的层圆角从直角变成跟随 item，要恢复调 `corner(0f)`。
- 光晕只画在边框外圈，开光晕又没开填充的 item 内部不再染色。
- `glow()` 的两参写法照常生效。默认 6 环比原来 2 圈开销大，API 19 的盒子传小一点。

## 1.0.0 — 2026-11-17

首个发布版本。

- `FocusIndicatorLayout` + `FocusIndicatorView`：焦点指示器作为独立浮层跟随焦点，支持跨行、跨列、跨容器飞行。
- 底色、描边、外发光三种视觉可任意组合，两层同步动画。
- 宽高与圆角逐帧重绘，不做 `scale` 拉伸。
- 逐帧贴合 item 的焦点态放大动画，结束后保留一段收尾贴合。
- 跨容器接力 `Group`。
- 脏区重绘，`minSdk 17`，除 Kotlin 标准库外零依赖。
