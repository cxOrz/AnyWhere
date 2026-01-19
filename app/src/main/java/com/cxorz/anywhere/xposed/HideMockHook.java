package com.cxorz.anywhere.xposed;

import android.content.ContentResolver;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HideMockHook implements IXposedHookLoadPackage {

    private static final String TAG = "AnyWhereHook";

    // List of apps to ignore (don't hide mock from these, e.g., our own app or system)
    // Actually, usually we want to hide from everyone except maybe the system location server itself?
    // But safely, let's just apply to everyone and exclude our own app so we don't confuse ourselves if we check it.
    private static final List<String> WHITELIST_PACKAGES = Arrays.asList(
            "com.cxorz.anywhere",
            "android", // Don't hook system server
            "com.android.systemui" // Don't hook UI
    );

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName == null) return;
        
        if (WHITELIST_PACKAGES.contains(lpparam.packageName)) {
            return;
        }

        // XposedBridge.log(TAG + ": Hooking app: " + lpparam.packageName);

        try {
            // 1. Hook Location.isFromMockProvider() (API 18+)
            XposedHelpers.findAndHookMethod(Location.class, "isFromMockProvider", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    return false;
                }
            });

            // 2. Hook Location.isMock() (API 31+ / Android 12+)
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    XposedHelpers.findAndHookMethod(Location.class, "isMock", new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            return false;
                        }
                    });
                } catch (Throwable t) {
                    // Ignore if method not found
                }
            }

            // 3. Hook Location.getExtras() - 深度清理
            XposedHelpers.findAndHookMethod(Location.class, "getExtras", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Bundle extras = (Bundle) param.getResult();
                    if (extras != null) {
                        if (extras.containsKey("mockLocation")) {
                            extras.remove("mockLocation");
                        }
                        // 某些厂商或检测库可能会检查 satellites 数量，如果是 0 可能会被怀疑
                        // 我们在 ServiceGo 里设置了 satellites=7，这里确保它没被抹掉即可
                        // XposedBridge.log(TAG + " Cleaned extras for " + lpparam.packageName);
                    }
                }
            });

            // 4. Hook Settings.Secure - 隐藏“模拟位置”开启状态
            // 很多 App 会检查 Settings.Secure.ALLOW_MOCK_LOCATION (旧版) 
            // 或者通过 AppOpsManager 检查，但 Settings 是最直接的
            XposedHelpers.findAndHookMethod(Settings.Secure.class, "getString", ContentResolver.class, String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String name = (String) param.args[1];
                    if ("mock_location".equals(name)) { // 旧版检测 key
                        param.setResult("0");
                        // XposedBridge.log(TAG + " Hiding mock_location setting for " + lpparam.packageName);
                    }
                }
            });
            
            // 针对 getStringForUser (多用户支持)
            try {
                XposedHelpers.findAndHookMethod(Settings.Secure.class, "getStringForUser", ContentResolver.class, String.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        String name = (String) param.args[1];
                        if ("mock_location".equals(name)) {
                            param.setResult("0");
                        }
                    }
                });
            } catch (Throwable t) {
                // Method might not exist on older androids
            }

        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }
}
