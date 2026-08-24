
package com.topjohnwu.magisk.core.utils;



interface IRootUtils {
    android.app.ActivityManager.RunningAppProcessInfo getAppProcess(int pid);
    IBinder getFileSystem();
    boolean addSystemlessHosts();
}
