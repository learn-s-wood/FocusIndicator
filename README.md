# FocusIndicator

Android TV / 机顶盒的「焦点飞框」库：把焦点指示器从 item 自身剥离出来，作为独立浮层跟随焦点移动。
跨行、跨列、跨容器切换时，飞框是**飞过去**的，不是原地闪现。

![preview](docs/preview.png)

## 特性

- **只用 Framework API**：`minSdk 17`，除 Kotlin 标准库外零依赖，不引入 AndroidX，可与 support / AndroidX / Compose 项目共存。
- **低端设备友好**：动画期间每帧只重绘「上一帧 ∪ 当前帧」的脏区，重绘面积通常只有全屏的几十分之一。
- **真实变形**：宽高与圆角逐帧重绘，不是 `scale` 拉伸，圆角不会椭圆化、描边粗细不会随比例变化。
- **三种视觉可自由组合**：圆角实心底色 / 描边 / 外发光，两层独立配置、同步动画。
- **自动贴合放大动画**：item 在焦点态自己做 `animate().scaleX(1.2f)` 时，飞框逐帧重采它的真实外形，框始终贴住放大后的边缘。
- **跨容器接力**：同一页面多个容器绑定同一个 `Group`，上下换行时飞框从上一行飞过来。
- **业务零侵入**：item 只需要 `focusable="true"`，不需要焦点态 selector、不需要背景、不需要任何回调。

## 目录结构

```
FocusIndicator/
├── focusindicator/          # 功能库（发布产物）
│   └── src/main/java/io/github/learnswood/focusindicator/
│       ├── FocusIndicatorLayout.kt   # 焦点飞框容器（FrameLayout）
│       └── FocusIndicatorView.kt     # 单层指示器绘制 View
├── app/                     # 示例 Demo（五种典型用法）
├── jitpack.yml              # JitPack 构建环境（JDK 17）
└── docs/preview.png
```

## 接入

### JitPack（远程依赖）

仓库：<https://github.com/learn-s-wood/FocusIndicator>

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

```kotlin
// 模块的 build.gradle.kts
dependencies {
    implementation("com.github.learn-s-wood:FocusIndicator:1.0.0")
}
```

> 坐标由 JitPack 按 **GitHub 用户名 + 仓库名 + tag** 生成，仓库名**区分大小写**（是 `FocusIndicator` 而非 `focusindicator`）。
> 版本号需要与 GitHub 上的 **tag 完全一致**（当前为 `1.0.0`，不带 `v` 前缀）。
> 首次拉取时 JitPack 需要现场构建，可能要等几分钟。

### 源码模块（本地）

```kotlin
dependencies {
    implementation(project(":focusindicator"))
}
```

不需要额外配置：库除 Kotlin 标准库外没有任何依赖，`minSdk` 17 也不会抬高使用方的门槛。

## 快速开始

### 1. 界面里套一层容器

```xml
<io.github.learnswood.focusindicator.FocusIndicatorLayout
    android:id="@+id/fiTabs"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:clipChildren="false"
    android:clipToPadding="false">

    <LinearLayout
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:clipChildren="false"
        android:orientation="horizontal">

        <TextView
            android:layout_width="88dp"
            android:layout_height="44dp"
            android:focusable="true"
            android:gravity="center"
            android:text="推荐" />

        <!-- 其余 item，同样只需 focusable="true" -->
    </LinearLayout>
</io.github.learnswood.focusindicator.FocusIndicatorLayout>
```

### 2. 配置视觉样式（Kotlin）

```kotlin
val container = findViewById<FocusIndicatorLayout>(R.id.fiTabs)

// 底层：圆角实心底色（会画在 item 之下）
container.behind()
    .fill(0xFFFF5000.toInt())
    .corner(dp(22))
    .padding(0f)

// 顶层：白色描边（画在 item 之上，不遮挡内容）
container.front()
    .stroke(Color.WHITE, dp(3))
    .corner(dp(12))
    .padding(dp(6))
```

### 3. 多行 / 多栏共享一个接力组（可选）

```kotlin
val group = FocusIndicatorLayout.Group()
row1.setGroup(group)
row2.setGroup(group)
row3.setGroup(group)
```

Java 用法完全一致：

```java
FocusIndicatorLayout container = findViewById(R.id.fi_tabs);
container.behind().fill(0xFFFF5000).corner(px(22)).padding(0);
container.front().stroke(Color.WHITE, px(3)).corner(px(12)).padding(px(6));
```

## API

### FocusIndicatorLayout

| 方法 | 说明 |
| --- | --- |
| `behind()` | 底层视觉（在 item 之下）：圆角实心底色等会遮挡内容的视觉只能放这层 |
| `front()` | 顶层视觉（在 item 之上）：描边、外发光等不遮挡内容的视觉放这层 |
| `setGroup(group)` | 绑定跨容器接力组，同页多个容器共用一个 `Group` 即可跨行飞行 |
| `setDuration(ms)` | 飞框动画时长，默认 220ms |
| `setAutoFollow(boolean)` | 关闭自动跟随，改为自行调用 `followTo()` |
| `setTrackTargetLayout(boolean)` | 是否逐帧重采目标外形，默认 `true`；item 焦点态不改尺寸时可关掉省测量 |
| `followTo(view)` | 手动飞行到指定 View |
| `snapTo(view)` | 直接对齐到指定 View（无动画），适合列表滚动后校正 |
| `requestSnap()` | 按当前焦点项重新对齐（无动画） |

### Layer（`behind()` / `front()` 返回，可链式调用）

| 方法 | 说明 |
| --- | --- |
| `fill(color)` | 圆角实心底色，传 `Color.TRANSPARENT` 关闭 |
| `stroke(color, widthPx)` | 描边，颜色 alpha 为 0 或宽度为 0 即关闭 |
| `glow(color, widthPx)` | 外发光（两层半透明外扩实现，不用 `setShadowLayer`，低端设备不会退化为软件绘制） |
| `padding(px)` | 相对 item 的外扩距离，正值表示比 item 大一圈 |
| `corner(px)` | 圆角半径 |
| `enable(boolean)` | 整体开关某一层 |

> 所有尺寸单位都是 **px**，若设计稿是 dp 请自行换算（Demo 中的 `dp()` 就是 `value * density`）。

## 注意事项

1. **item 不要设置不透明背景**。底层实心底色画在 item 之下，item 一旦有背景就会把色块盖住。需要卡片底色时，改用 `front()` 的描边 + 外发光（Demo 的网格场景就是这么做的）。
2. **容器与所有祖先都要 `clipChildren="false"`**。描边、外发光会画到 item 边界之外，跨容器飞行时起始位置甚至落在容器外。
3. **容器用 `wrap_content` 即可**。库内部已处理「绘制层铺满容器但不撑开容器」的测量问题，不需要像早期实现那样写死高度。
4. **item 焦点态做放大动画时不要关掉 `setTrackTargetLayout`**，否则飞框会按拿到焦点那一刻的尺寸画，最终比 item 小一圈。

## Demo

`app` 模块包含五个场景，装到电视 / 盒子 / 模拟器上用遥控器操作即可：

1. 同尺寸 tab + 圆角实心底色在 item 下层平移
2. 不同尺寸 item + 描边飞框，观察宽高与圆角逐帧重绘的变形
3. 底色层 + 描边层组合，两层同步变形
4. item 焦点态放大 1.2 倍，验证飞框贴合放大后的边缘
5. RecyclerView 网格，验证列表复用场景

```bash
./gradlew :app:installDebug
```

## 环境

- 库：`minSdk 17` / `compileSdk 33` / Kotlin 1.8.10 / AGP 8.1.3，仅依赖 Kotlin 标准库
- Demo 额外引入了 `androidx.recyclerview:1.2.1`（`minSdk 14`），库本体不依赖 AndroidX

## License

[Apache License 2.0](LICENSE)
