





















-obfuscationdictionary ../dict.txt
-classobfuscationdictionary ../dict.txt
-packageobfuscationdictionary ../dict.txt


-repackageclasses
-allowaccessmodification
-keepclassmembers class com.topjohnwu.magisk.dummy.* { <init>(); }
-keepclassmembers class com.topjohnwu.magisk.DownloadActivity { <init>(); }
-keepclassmembers class com.topjohnwu.magisk.StubRootService { <init>(); }
