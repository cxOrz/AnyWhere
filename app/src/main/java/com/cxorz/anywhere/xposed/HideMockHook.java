package com.cxorz.anywhere.xposed;

import android.content.ContentResolver;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.telephony.CellInfo;
import android.telephony.CellLocation;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HideMockHook implements IXposedHookLoadPackage {

    private static final String TAG = "AnyWhereHook";

    // 白名单：排除自己和系统核心进程，避免误伤
    private static final List<String> WHITELIST_PACKAGES = Arrays.asList(
            "com.cxorz.anywhere",
            "android",
            "com.android.systemui",
            "com.android.phone" // 电话进程通常不需要 Hook，否则可能影响信号显示
    );

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName == null) return;
        
        if (WHITELIST_PACKAGES.contains(lpparam.packageName)) {
            return;
        }

        try {
            // =========================================================================
            // 1. 基础防检测：Location 对象本身
            // =========================================================================

            // API 18+: Location.isFromMockProvider() -> false
            XposedHelpers.findAndHookMethod(Location.class, "isFromMockProvider", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    return false;
                }
            });

            // API 31+: Location.isMock() -> false
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    XposedHelpers.findAndHookMethod(Location.class, "isMock", new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            return false;
                        }
                    });
                } catch (Throwable t) {
                    // Ignore
                }
            }

            // 清理 extras 中的 mockLocation 标记
            XposedHelpers.findAndHookMethod(Location.class, "getExtras", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Bundle extras = (Bundle) param.getResult();
                    if (extras != null) {
                        if (extras.containsKey("mockLocation")) {
                            extras.remove("mockLocation");
                        }
                    }
                }
            });

            // =========================================================================
            // 2. 屏蔽 Wi-Fi 扫描 (防止 Wi-Fi 纠偏/闪回)
            // =========================================================================
            try {
                // getScanResults -> 返回空列表
                XposedHelpers.findAndHookMethod(WifiManager.class, "getScanResults", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        // 返回空列表，让 App 以为周围没有任何 Wi-Fi，从而无法进行 Wi-Fi 定位
                        return new ArrayList<>(); 
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(TAG + " [WifiManager] Hook failed: " + t.getMessage());
            }

            // =========================================================================
            // 3. 屏蔽基站信息 (防止基站纠偏/闪回)
            // =========================================================================
            try {
                // getCellLocation -> null
                XposedHelpers.findAndHookMethod(TelephonyManager.class, "getCellLocation", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return null;
                    }
                });

                // getAllCellInfo -> null 或 空列表
                // 注意：某些 App 拿到 null 可能会崩溃，更安全的做法是返回空列表
                XposedHelpers.findAndHookMethod(TelephonyManager.class, "getAllCellInfo", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return new ArrayList<CellInfo>();
                    }
                });
                
                // getNeighboringCellInfo -> 空列表 (旧版 API)
                XposedHelpers.findAndHookMethod(TelephonyManager.class, "getNeighboringCellInfo", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return new ArrayList<>();
                    }
                });
                
            } catch (Throwable t) {
                XposedBridge.log(TAG + " [TelephonyManager] Hook failed: " + t.getMessage());
            }

            // =========================================================================
            // 4. 卫星状态屏蔽 (GpsStatus / GnssStatus)
            // =========================================================================
            
            // 方案：与其费力去伪造复杂的卫星对象，不如直接屏蔽监听。
            // App 收不到回调通常会认为“定位中”或“暂无详细信息”，比收到“0颗卫星”更安全。

            // 4.1 屏蔽旧版 GpsStatus 监听
            // 拦截 addGpsStatusListener，直接返回 true (注册成功)，但不真正注册
            try {
                XposedHelpers.findAndHookMethod(LocationManager.class, "addGpsStatusListener", GpsStatus.Listener.class, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        // 欺骗 App 说监听器添加成功了，但实际上我们什么都没做
                        return true; 
                    }
                });
            } catch (Throwable t) {
                // Ignore
            }

            // 4.2 屏蔽新版 GnssStatus 监听 (Android N+, API 24+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    // GnssStatus.Callback 是一个抽象类
                    Class<?> gnssCallbackClass = Class.forName("android.location.GnssStatus$Callback");
                    
                    // registerGnssStatusCallback 有两个重载，我们都拦截
                    // 1. registerGnssStatusCallback(GnssStatus.Callback callback)
                    // 2. registerGnssStatusCallback(GnssStatus.Callback callback, Handler handler)
                    
                    XC_MethodReplacement blockGnssRegistration = new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            // 同样欺骗 App 说注册成功
                            return true;
                        }
                    };

                    XposedHelpers.findAndHookMethod(LocationManager.class, "registerGnssStatusCallback", gnssCallbackClass, blockGnssRegistration);
                    XposedHelpers.findAndHookMethod(LocationManager.class, "registerGnssStatusCallback", gnssCallbackClass, android.os.Handler.class, blockGnssRegistration);

                } catch (Throwable t) {
                    XposedBridge.log(TAG + " [GnssStatus] Hook failed: " + t.getMessage());
                }
            }

            // =========================================================================
            // 5. 隐藏 Settings 中的模拟位置设置
            // =========================================================================
            XposedHelpers.findAndHookMethod(Settings.Secure.class, "getString", ContentResolver.class, String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String name = (String) param.args[1];
                    if ("mock_location".equals(name)) { 
                        param.setResult("0");
                    }
                }
            });
            
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
                // Method might not exist
            }

            // =========================================================================
            // 6. 清理 LocationManager 中的 Provider 列表 (防止出现重复或 test provider)
            // =========================================================================
             try {
                 // 定义标准 Provider 白名单
                 final List<String> standardProviders = Arrays.asList("gps", "network", "passive", "fused");

                 XC_MethodHook providerCleaner = new XC_MethodHook() {
                     @Override
                     protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                         List<String> providers = (List<String>) param.getResult();
                         if (providers == null) return;

                         // 倒序遍历删除不在白名单中的 Provider
                         for (int i = providers.size() - 1; i >= 0; i--) {
                             String name = providers.get(i);
                             if (!standardProviders.contains(name)) {
                                 providers.remove(i);
                             }
                         }
                     }
                 };

                 // Hook getProviders(boolean enabledOnly)
                 XposedHelpers.findAndHookMethod(LocationManager.class, "getProviders", boolean.class, providerCleaner);
                 
                 // Hook getAllProviders()
                 XposedHelpers.findAndHookMethod(LocationManager.class, "getAllProviders", providerCleaner);

            } catch (Throwable t) {
                XposedBridge.log(TAG + " [ProviderCleaner] Hook failed: " + t.getMessage());
            }

        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }
}
