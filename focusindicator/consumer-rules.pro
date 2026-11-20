# 库不反射自有类、不做序列化；反射只碰 Framework（R8 不混淆 android.*）。
# XML 按类名 inflate 由 aapt 自动 keep；这里再保一层公开 API，防使用方反射/动态类名。
-keep class io.github.learnswood.focusindicator.FocusIndicatorLayout { public *; }
-keep class io.github.learnswood.focusindicator.FocusIndicatorView { public *; }
