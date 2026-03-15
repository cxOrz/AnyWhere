package com.example.detector;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.CellInfo;
import android.telephony.TelephonyManager;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private TextView logTv;
    private ScrollView scrollView;
    private LocationManager locationManager;
    private WifiManager wifiManager;
    private TelephonyManager telephonyManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 动态创建布局
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);

        Button btnCheck = new Button(this);
        btnCheck.setText("开始全面检测 / Start Detection");
        layout.addView(btnCheck);

        Button btnClear = new Button(this);
        btnClear.setText("清空日志 / Clear Log");
        layout.addView(btnClear);

        scrollView = new ScrollView(this);
        logTv = new TextView(this);
        logTv.setText("点击上方按钮开始检测...\n确保在 LSPosed 中已勾选本应用！\n");
        scrollView.addView(logTv);
        layout.addView(scrollView);

        setContentView(layout);

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);

        btnCheck.setOnClickListener(v -> startDetection());
        btnClear.setOnClickListener(v -> logTv.setText(""));

        checkPermissions();
    }

    private void checkPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.READ_PHONE_STATE
            }, 100);
        }
    }

    private static final String TAG = "MockDetector";

    private void log(String msg) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        android.util.Log.i(TAG, msg);
        runOnUiThread(() -> {
            logTv.append("\n[" + time + "] " + msg);
            scrollView.fullScroll(ScrollView.FOCUS_DOWN);
        });
    }

    @SuppressLint("MissingPermission")
    private void startDetection() {
        log("=== 开始检测 ===");

        // 1. 检查 Provider 列表
        List<String> providers = locationManager.getAllProviders();
        log("Provider 列表: " + providers.toString());
        if (providers.contains("gps_test") || providers.contains("mock")) {
            log("❌ 警告：检测到 Mock Provider！");
        } else {
            log("✅ Provider 列表看起来正常。");
        }

        // 2. 检查位置信息 (GPS)
        log("正在请求 GPS 位置...");
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                boolean isMock = false;
                if (Build.VERSION.SDK_INT >= 18) isMock = location.isFromMockProvider();
                if (Build.VERSION.SDK_INT >= 31) isMock = isMock || location.isMock();
                
                Bundle extras = location.getExtras();
                int sats = -1;
                if (extras != null) {
                    sats = extras.getInt("satellites", -1);
                }

                log("📍 位置更新: " + location.getLatitude() + ", " + location.getLongitude());
                if (isMock) {
                    log("❌ 暴露：检测到 isFromMockProvider=true");
                } else {
                    log("✅ 掩护成功：isFromMockProvider=false");
                }
                
                if (sats >= 0) {
                    log("✅ extras.satellites = " + sats);
                } else {
                    log("❓ extras 中没有 satellites");
                }
                locationManager.removeUpdates(this);
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(@NonNull String provider) {}
            @Override public void onProviderDisabled(@NonNull String provider) {}
        });

        // 3. 检查 GpsStatus (旧版)
        log("正在检查 GpsStatus (API < 24)...");
        try {
            // 注意：新版 Hook 模块已不再模拟过时的 GpsStatus，此处可能无数据
            locationManager.addGpsStatusListener(event -> {
                if (event == GpsStatus.GPS_EVENT_SATELLITE_STATUS) {
                    GpsStatus status = locationManager.getGpsStatus(null);
                    int count = 0;
                    if (status != null) {
                        for (Object s : status.getSatellites()) {
                            count++;
                        }
                    }
                    if (count > 0) {
                        log("⚠️ GpsStatus 捕获到卫星: " + count + " (旧版API)");
                    } else {
                        log("ℹ️ GpsStatus 卫星数量为 0 (符合预期，已废弃)");
                    }
                    locationManager.removeGpsStatusListener(this::onGpsStatusChanged);
                }
            });
            GpsStatus status = locationManager.getGpsStatus(null);
        } catch (Exception e) {
            log("跳过 GpsStatus 检测: " + e.getMessage());
        }

        // 4. 检查 GnssStatus (新版)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            log("正在检查 GnssStatus (API 24+)...");
            locationManager.registerGnssStatusCallback(new GnssStatus.Callback() {
                @Override
                public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
                    int count = status.getSatelliteCount();
                    if (count > 0) {
                        log("✅ GnssStatus 捕获到卫星: " + count + " 颗");
                        // 检查信噪比
                        float cn0 = status.getCn0DbHz(0);
                        log("ℹ️ 卫星#1 信号强度: " + cn0);
                    } else {
                        log("❌ GnssStatus 卫星数量为 0！");
                    }
                    locationManager.unregisterGnssStatusCallback(this);
                }
            }, new Handler(Looper.getMainLooper()));
        }

        // 5. 检查 Wi-Fi
        log("正在检查 Wi-Fi...");
        List<ScanResult> wifiList = wifiManager.getScanResults();
        if (wifiList == null || wifiList.isEmpty()) {
            log("✅ Wi-Fi 列表为空 (Hook 生效)");
        } else {
            log("❌ 警告：扫描到 " + wifiList.size() + " 个 Wi-Fi 热点！(Hook 失败)");
        }

        // 6. 检查基站
        log("正在检查基站...");
        List<CellInfo> cellList = telephonyManager.getAllCellInfo();
        if (cellList == null || cellList.isEmpty()) {
            log("✅ 基站列表为空 (Hook 生效)");
        } else {
            log("❌ 警告：扫描到 " + cellList.size() + " 个基站！(Hook 失败)");
        }

        // 7. 检查 getLastKnownLocation
        log("正在检查 getLastKnownLocation...");
        try {
            Location lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (lastGps != null) {
                boolean lastMock = false;
                if (Build.VERSION.SDK_INT >= 18) lastMock = lastGps.isFromMockProvider();
                if (Build.VERSION.SDK_INT >= 31) lastMock = lastMock || lastGps.isMock();
                if (lastMock) {
                    log("❌ getLastKnownLocation(GPS): isFromMockProvider=true");
                } else {
                    log("✅ getLastKnownLocation(GPS): isFromMockProvider=false");
                }
            } else {
                log("ℹ️ getLastKnownLocation(GPS) 返回 null");
            }
        } catch (Exception e) {
            log("跳过 getLastKnownLocation: " + e.getMessage());
        }

        // 8. 检查 getCurrentLocation (API 30+)
        if (Build.VERSION.SDK_INT >= 30) {
            log("正在检查 getCurrentLocation (API 30+)...");
            try {
                locationManager.getCurrentLocation(LocationManager.GPS_PROVIDER, null,
                        getMainExecutor(), location -> {
                    if (location != null) {
                        boolean mock = location.isMock();
                        if (mock) {
                            log("❌ getCurrentLocation: isMock=true");
                        } else {
                            log("✅ getCurrentLocation: isMock=false");
                        }
                    } else {
                        log("ℹ️ getCurrentLocation 返回 null");
                    }
                });
            } catch (Exception e) {
                log("跳过 getCurrentLocation: " + e.getMessage());
            }
        }

        // 9. 反射读取 mMock / mIsFromMockProvider 字段
        log("正在检查反射字段访问...");
        Location lastLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (lastLoc != null) {
            checkMockField(lastLoc);
        } else {
            // 用 network provider 再试一次
            try {
                Location netLoc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (netLoc != null) {
                    checkMockField(netLoc);
                } else {
                    log("ℹ️ 无可用 Location 对象，跳过反射检测");
                }
            } catch (Exception e) {
                log("ℹ️ 无可用 Location 对象，跳过反射检测");
            }
        }

        // 10. 检查 Extras Bundle 中的 mock 相关 key
        log("正在检查 Extras Bundle mock keys...");
        if (lastLoc != null) {
            checkExtrasMockKeys(lastLoc);
        } else {
            log("ℹ️ 无可用 Location 对象，跳过 Extras 检测");
        }

        // 11. 检查 isProviderEnabled 对非标准 provider
        log("正在检查 isProviderEnabled...");
        try {
            boolean mockEnabled = locationManager.isProviderEnabled("mock");
            if (mockEnabled) {
                log("❌ isProviderEnabled(\"mock\") = true");
            } else {
                log("✅ isProviderEnabled(\"mock\") = false");
            }
        } catch (Exception e) {
            log("✅ isProviderEnabled(\"mock\") 抛出异常 (正常)");
        }

        // 12. 检查 Settings.Secure mock_location
        log("正在检查 Settings.Secure mock_location...");
        try {
            String mockSetting = Settings.Secure.getString(getContentResolver(), "mock_location");
            if ("0".equals(mockSetting) || mockSetting == null) {
                log("✅ mock_location = " + mockSetting);
            } else {
                log("❌ mock_location = " + mockSetting);
            }
        } catch (Exception e) {
            log("跳过 mock_location: " + e.getMessage());
        }
    }

    private void onGpsStatusChanged(int event) {}

    private void checkMockField(Location location) {
        // 先列出 Location 所有 boolean 字段，方便排查
        Field[] allFields = Location.class.getDeclaredFields();
        StringBuilder fieldList = new StringBuilder();
        for (Field f : allFields) {
            if (f.getType() == boolean.class) {
                fieldList.append(f.getName()).append(", ");
            }
        }
        log("Location boolean 字段: " + fieldList.toString());

        // 依次尝试已知的 mock 字段名
        String[] mockFieldNames = {"mMock", "mIsFromMockProvider", "mIsMock"};
        for (String name : mockFieldNames) {
            try {
                Field field = Location.class.getDeclaredField(name);
                field.setAccessible(true);
                boolean val = (boolean) field.get(location);
                if (val) {
                    log("❌ 反射 " + name + " = true");
                } else {
                    log("✅ 反射 " + name + " = false");
                }
                return;
            } catch (NoSuchFieldException ignored) {
            } catch (Exception e) {
                log("跳过 " + name + " 反射: " + e.getMessage());
            }
        }
        log("⚠️ 未找到已知的 mock 字段，可能需要更新字段名列表");
    }

    private void checkExtrasMockKeys(Location location) {
        Bundle extras = location.getExtras();
        if (extras == null) {
            log("ℹ️ Extras 为 null");
            return;
        }
        String[] mockKeys = {"mockLocation"};
        boolean found = false;
        for (String key : mockKeys) {
            if (extras.containsKey(key)) {
                log("❌ Extras 包含 key: " + key);
                found = true;
            }
        }
        if (!found) {
            log("✅ Extras 中无 mock 相关 key");
        }
    }
}
