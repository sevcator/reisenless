
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}


-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
	public static void check*(...);
	public static void throw*(...);
}
-assumenosideeffects class java.util.Objects {
    public static ** requireNonNull(...);
}
-assumenosideeffects public class kotlin.coroutines.jvm.internal.DebugMetadataKt {
   private static ** getDebugMetadataAnnotation(...) return null;
}


-keep class com.topjohnwu.magisk.core.App { <init>(java.lang.Object); }
-keepclassmembers class androidx.appcompat.app.AppCompatDelegateImpl {
  boolean mActivityHandlesConfigFlagsChecked;
  int mActivityHandlesConfigFlags;
}




-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

-dontwarn org.junit.**
-dontwarn org.apache.**
