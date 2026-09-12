# 入口类及其 hook 逻辑
-keep class me.livephoto.assist.LivePhotoUnlockHook { *; }
# 排错可读：保留行号与源码文件名（r8-map-id 堆栈看不出位置时用）
-keepattributes SourceFile,LineNumberTable
