# :lianxu 备份、记忆、聊天记录与桥接基线使用 Gson 反射读写字段，保留成员字段名不被 R8 混淆。
-keepclassmembers class app.lianxu.independent.** { <fields>; }
