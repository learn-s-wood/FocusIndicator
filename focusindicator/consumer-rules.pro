# 库自身不反射、不做序列化，无需额外保留规则。
# 若使用方开启了全量混淆，只要不混淆 FocusIndicatorLayout / FocusIndicatorView
# （XML 里按类名引用时才需要），本库即可正常工作。
-keep class io.github.learnswood.focusindicator.FocusIndicatorLayout { public *; }
-keep class io.github.learnswood.focusindicator.FocusIndicatorView { public *; }
