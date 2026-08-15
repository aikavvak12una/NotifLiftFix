# Keep the libxposed entry point referenced from META-INF/xposed/java_init.list.
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep class com.sysui.gxzwfix.ModuleMain { *; }
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# R8 会改写 Class.forName(...) 的类名字符串常量（改写为混淆名导致运行时
# ClassNotFoundException，如 "kotlin.coroutines.Continuation" -> "a"）。
# keep 这些被反射加载的类：不混淆它们，R8 就不会改写对应字符串。
-keep class kotlin.coroutines.Continuation
-keep class kotlinx.coroutines.flow.FlowCollector
-keep class kotlinx.coroutines.flow.StateFlowKt { *; }
