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
import android.telephony.CellInfo;
import android.telephony.TelephonyManager;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

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

    private void log(String msg) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
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
    }

    private void onGpsStatusChanged(int event) {}
}
