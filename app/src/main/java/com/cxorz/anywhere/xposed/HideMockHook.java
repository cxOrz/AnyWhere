package com.cxorz.anywhere.xposed;

import android.content.ContentResolver;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationListener;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.telephony.CellInfo;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
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

    // 白名单：排除自己和系统核心进程，避免误伤
    private static final List<String> WHITELIST_PACKAGES = Arrays.asList(
            "com.cxorz.anywhere",
            "android",
            "com.android.systemui",
            "com.android.phone"
    );

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName == null) return;
        
        if (WHITELIST_PACKAGES.contains(lpparam.packageName)) {
            return;
        }

        try {
            // 1. 基础防检测
            XposedHelpers.findAndHookMethod(Location.class, "isFromMockProvider", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    return false;
                }
            });

            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    XposedHelpers.findAndHookMethod(Location.class, "isMock", new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            return false;
                        }
                    });
                } catch (Throwable t) {}
            }

            XposedHelpers.findAndHookMethod(Location.class, "getExtras", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Bundle extras = (Bundle) param.getResult();
                    if (extras != null && extras.containsKey("mockLocation")) {
                        extras.remove("mockLocation");
                    }
                }
            });

            // 2. 屏蔽 Wi-Fi 和 基站
            try {
                XposedHelpers.findAndHookMethod(WifiManager.class, "getScanResults", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return new ArrayList<>(); 
                    }
                });
            } catch (Throwable t) {}

            try {
                XposedHelpers.findAndHookMethod(TelephonyManager.class, "getCellLocation", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return null;
                    }
                });
                XposedHelpers.findAndHookMethod(TelephonyManager.class, "getAllCellInfo", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return new ArrayList<CellInfo>();
                    }
                });
            } catch (Throwable t) {}

            // 3. 卫星状态主动伪造 (GnssStatus Only)
            final Handler mainHandler = new Handler(Looper.getMainLooper());
            final List<Object> gnssCallbacks = new ArrayList<>();

            // 移除旧的 LocationManager removeUpdates Hook (如果只用于 GPS Listener 清理的话)
            // 但如果用于通用 LocationListener 清理，可以保留。这里主要关注 GpsStatus，所以保留 removeUpdates 对通用 Listener 还是有用的，
            // 不过为了纯净，我们假设 removeUpdates 主要影响 location 监听。
            // 这里我们不再维护 gpsListeners 列表。

            // =========================================================================
            // GnssStatus Hook (Android N+)
            // =========================================================================
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                
                final Class<?> gnssStatusClass = Class.forName("android.location.GnssStatus");
                
                // 1. Hook GnssStatus 的所有 Getter 方法
                XposedHelpers.findAndHookMethod(gnssStatusClass, "getSatelliteCount", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return 7; 
                    }
                });

                XposedHelpers.findAndHookMethod(gnssStatusClass, "getSvid", int.class, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return ((int) param.args[0]) + 1;
                    }
                });

                XposedHelpers.findAndHookMethod(gnssStatusClass, "getConstellationType", int.class, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return 1; // GPS
                    }
                });

                XposedHelpers.findAndHookMethod(gnssStatusClass, "getCn0DbHz", int.class, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return 35.0f;
                    }
                });

                XposedHelpers.findAndHookMethod(gnssStatusClass, "getElevationDegrees", int.class, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return 45.0f;
                    }
                });

                XposedHelpers.findAndHookMethod(gnssStatusClass, "getAzimuthDegrees", int.class, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return (float)((int)param.args[0] * 45);
                    }
                });

                XposedHelpers.findAndHookMethod(gnssStatusClass, "usedInFix", int.class, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return true;
                    }
                });
                
                try { XposedHelpers.findAndHookMethod(gnssStatusClass, "hasEphemeris", int.class, new XC_MethodReplacement() { @Override protected Object replaceHookedMethod(MethodHookParam param) { return true; } }); } catch (Throwable t) {}
                try { XposedHelpers.findAndHookMethod(gnssStatusClass, "hasAlmanac", int.class, new XC_MethodReplacement() { @Override protected Object replaceHookedMethod(MethodHookParam param) { return true; } }); } catch (Throwable t) {}
                try { XposedHelpers.findAndHookMethod(gnssStatusClass, "hasCarrierFrequencyHz", int.class, new XC_MethodReplacement() { @Override protected Object replaceHookedMethod(MethodHookParam param) { return true; } }); } catch (Throwable t) {}
                try { XposedHelpers.findAndHookMethod(gnssStatusClass, "getCarrierFrequencyHz", int.class, new XC_MethodReplacement() { @Override protected Object replaceHookedMethod(MethodHookParam param) { return 1.57542e9f; } }); } catch (Throwable t) {}


                // 2. 拦截注册
                Class<?> gnssCallbackClass = Class.forName("android.location.GnssStatus$Callback");
                XC_MethodReplacement registerGnssHook = new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        Object callback = param.args[0];
                        if (callback != null) {
                            synchronized (gnssCallbacks) {
                                if (!gnssCallbacks.contains(callback)) gnssCallbacks.add(callback);
                            }
                        }
                        return true;
                    }
                };
                XposedHelpers.findAndHookMethod(LocationManager.class, "registerGnssStatusCallback", gnssCallbackClass, registerGnssHook);
                XposedHelpers.findAndHookMethod(LocationManager.class, "registerGnssStatusCallback", gnssCallbackClass, android.os.Handler.class, registerGnssHook);

                // 3. 模拟循环
                Runnable simulator = new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (!gnssCallbacks.isEmpty()) {
                                Object statusToSend = getBestDummyGnssStatus(); 
                                if (statusToSend != null) {
                                    synchronized (gnssCallbacks) {
                                        for (Object callback : gnssCallbacks) {
                                            XposedHelpers.callMethod(callback, "onSatelliteStatusChanged", statusToSend);
                                        }
                                    }
                                } else {
                                    Log.e(TAG, "CRITICAL: getBestDummyGnssStatus returned NULL.");
                                }
                            }
                        } catch (Throwable t) { Log.e(TAG, "Sim loop error: " + t); }
                        mainHandler.postDelayed(this, 1000);
                    }
                };
                mainHandler.post(simulator);
            }
            
            // 4. 清理 Settings 和 Provider 列表
            XposedHelpers.findAndHookMethod(Settings.Secure.class, "getString", ContentResolver.class, String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if ("mock_location".equals(param.args[1])) param.setResult("0");
                }
            });

            final List<String> standardProviders = Arrays.asList("gps", "network", "passive", "fused");
            XC_MethodHook providerCleaner = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    List<String> providers = (List<String>) param.getResult();
                    if (providers != null) {
                        for (int i = providers.size() - 1; i >= 0; i--) {
                            if (!standardProviders.contains(providers.get(i))) providers.remove(i);
                        }
                    }
                }
            };
            XposedHelpers.findAndHookMethod(LocationManager.class, "getProviders", boolean.class, providerCleaner);
            XposedHelpers.findAndHookMethod(LocationManager.class, "getAllProviders", providerCleaner);

        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private Object getBestDummyGnssStatus() {
        try {
            Class<?> builderClass = Class.forName("android.location.GnssStatus$Builder");
            
            // 1. Try Builder No-Args
            try {
                Object builder = XposedHelpers.newInstance(builderClass); 
                return XposedHelpers.callMethod(builder, "build");
            } catch (Throwable t) {}
            
            // 2. Try Builder with int
            try {
                Object builder = XposedHelpers.newInstance(builderClass, 0); 
                return XposedHelpers.callMethod(builder, "build");
            } catch (Throwable t) {}

            // 3. Try Legacy GnssStatus Constructors
            Class<?> gnssStatusClass = Class.forName("android.location.GnssStatus");
            Constructor<?>[] constructors = gnssStatusClass.getDeclaredConstructors();
            for (Constructor<?> c : constructors) {
                try {
                    c.setAccessible(true);
                    Class<?>[] params = c.getParameterTypes();
                    Object[] args = new Object[params.length];
                    for (int i = 0; i < params.length; i++) {
                        Class<?> type = params[i];
                        if (type == int.class) args[i] = 0;
                        else if (type == float[].class) args[i] = new float[0];
                        else if (type == int[].class) args[i] = new int[0];
                        else args[i] = null;
                    }
                    return c.newInstance(args);
                } catch (Throwable t) {}
            }
        } catch (Throwable t) {
            Log.e(TAG, "getBestDummyGnssStatus fatal: " + t);
        }
        return null;
    }
}