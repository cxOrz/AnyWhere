package com.cxorz.anywhere;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Address;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.SimpleAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.preference.PreferenceManager;

import com.elvishew.xlog.XLog;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.snackbar.Snackbar;

import org.osmdroid.views.overlay.TilesOverlay;
import com.cxorz.anywhere.database.DataBaseHistoryLocation;
import com.cxorz.anywhere.database.DataBaseHistorySearch;
import com.cxorz.anywhere.service.ServiceGo;
import com.cxorz.anywhere.utils.GoUtils;
import com.cxorz.anywhere.utils.ShareUtils;

import org.osmdroid.api.IMapController;
import org.osmdroid.bonuspack.location.GeocoderNominatim;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.OkHttpClient;

public class MainActivity extends BaseActivity implements SensorEventListener {
    /* 对外 */
    public static final String LAT_MSG_ID = "LAT_VALUE";
    public static final String LNG_MSG_ID = "LNG_VALUE";
    public static final String ALT_MSG_ID = "ALT_VALUE";

    public static final String POI_NAME = "POI_NAME";
    public static final String POI_ADDRESS = "POI_ADDRESS";
    public static final String POI_LONGITUDE = "POI_LONGITUDE";
    public static final String POI_LATITUDE = "POI_LATITUDE";

    private OkHttpClient mOkHttpClient;
    private SharedPreferences sharedPreferences;

    /* ============================== 主界面地图 相关 ============================== */
    /************** 地图 *****************/
    public static String mCurrentCity = null;
    private MapView mMapView;
    private IMapController mMapController;
    private MyLocationNewOverlay mLocationOverlay;
    private Marker mCurrentMarker;

    private static GeoPoint mMarkLatLngMap = new GeoPoint(39.9042, 116.4074); // 默认北京
    private static String mMarkName = null;
    private SensorManager mSensorManager;
    private Sensor mSensorAccelerometer;
    private Sensor mSensorMagnetic;
    private float[] mAccValues = new float[3];// 加速度传感器数据
    private float[] mMagValues = new float[3];// 地磁传感器数据
    private final float[] mR = new float[9];// 旋转矩阵，用来保存磁场和加速度的数据
    private final float[] mDirectionValues = new float[3];// 模拟方向传感器的数据（原始数据为弧度）
    /************** 定位 *****************/
    private double mCurrentLat = 0.0;
    private double mCurrentLon = 0.0;
    private float mCurrentDirection = 0.0f;
    private boolean isMockServStart = false;
    private static boolean isWifiWarningShown = false;
    private ServiceGo.ServiceGoBinder mServiceBinder;
    private ServiceConnection mConnection;
    private FloatingActionButton mButtonStart;
    /* ============================== 历史记录 相关 ============================== */
    private SQLiteDatabase mLocationHistoryDB;
    private SQLiteDatabase mSearchHistoryDB;
    /*
     * ============================== SearchView 相关 ==============================
     */
    private SearchView searchView;
    private ListView mSearchList;
    private LinearLayout mSearchLayout;
    private ListView mSearchHistoryList;
    private LinearLayout mHistoryLayout;
    private MenuItem searchItem;

    private double mCurrentZoom = 16.0;

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mMapView != null) {
            outState.putDouble("MAP_ZOOM", mMapView.getZoomLevelDouble());
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            mCurrentZoom = savedInstanceState.getDouble("MAP_ZOOM", 16.0);
        }

        // OSMDroid configuration
        Configuration.getInstance().load(getApplicationContext(),
                PreferenceManager.getDefaultSharedPreferences(getApplicationContext()));
        Configuration.getInstance().setUserAgentValue(getPackageName());

        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawer, toolbar, R.string.nav_drawer_open, R.string.nav_drawer_close);
        drawer.addDrawerListener(toggle);
        toggle.syncState();

        XLog.i("MainActivity: onCreate");

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        mOkHttpClient = new OkHttpClient();

        initNavigationView();

        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        mSensorAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mSensorMagnetic = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        initMap();

        initStoreHistory();

        boolean hasHistory = false;
        if (savedInstanceState == null) {
            hasHistory = moveToLastHistoryLocation();
        } else {
            // Restore map state on recreation (Theme Switch)
            if (mMarkLatLngMap != null && mMapController != null) {
                mMapController.setCenter(mMarkLatLngMap);
                markMap();
            }
        }

        initMapLocation(hasHistory || savedInstanceState != null);

        initMapButton();

        initGoBtn();

        mConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                mServiceBinder = (ServiceGo.ServiceGoBinder) service;
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {

            }
        };

        initSearchView();

        handleIntent(getIntent());

        // Modern way to handle back press
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                DrawerLayout drawer = findViewById(R.id.drawer_layout);
                if (drawer.isDrawerOpen(GravityCompat.START)) {
                    drawer.closeDrawer(GravityCompat.START);
                } else {
                    finish();
                }
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra("SHOW_LOCATION", false)) {
            String name = intent.getStringExtra("NAME");
            String lngStr = intent.getStringExtra("LNG");
            String latStr = intent.getStringExtra("LAT");
            if (lngStr != null && latStr != null) {
                try {
                    double lng = Double.parseDouble(lngStr);
                    double lat = Double.parseDouble(latStr);
                    mMarkLatLngMap = new GeoPoint(lat, lng);
                    mMarkName = name;

                    if (mMapView != null && mMapController != null) {
                        mMapController.setCenter(mMarkLatLngMap);
                        markMap();
                    }
                } catch (NumberFormatException e) {
                    XLog.e("Invalid coordinates in intent");
                }
            }
        }
    }

    @Override
    protected void onPause() {
        XLog.i("MainActivity: onPause");
        if (mMapView != null)
            mMapView.onPause();
        if (mSensorManager != null) {
            mSensorManager.unregisterListener(this);
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        XLog.i("MainActivity: onResume");
        if (mMapView != null)
            mMapView.onResume();
        if (mSensorManager != null) {
            if (mSensorAccelerometer != null) {
                mSensorManager.registerListener(this, mSensorAccelerometer, SensorManager.SENSOR_DELAY_UI);
            }
            if (mSensorMagnetic != null) {
                mSensorManager.registerListener(this, mSensorMagnetic, SensorManager.SENSOR_DELAY_UI);
            }
        }
        super.onResume();
    }

    @Override
    protected void onStop() {
        XLog.i("MainActivity: onStop");
        if (mSensorManager != null) {
            mSensorManager.unregisterListener(this);
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        XLog.i("MainActivity: onDestroy");

        if (isMockServStart) {
            unbindService(mConnection);
            Intent serviceGoIntent = new Intent(MainActivity.this, ServiceGo.class);
            stopService(serviceGoIntent);
        }

        mSensorManager.unregisterListener(this);

        mLocationHistoryDB.close();
        mSearchHistoryDB.close();

        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        searchItem = menu.findItem(R.id.action_search);
        searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                mSearchLayout.setVisibility(View.INVISIBLE);
                mHistoryLayout.setVisibility(View.INVISIBLE);
                return true;
            }

            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                mSearchLayout.setVisibility(View.INVISIBLE);
                List<Map<String, Object>> data = getSearchHistory();

                if (!data.isEmpty()) {
                    SimpleAdapter simAdapt = new SimpleAdapter(
                            MainActivity.this,
                            data,
                            R.layout.search_item,
                            new String[] { DataBaseHistorySearch.DB_COLUMN_KEY,
                                    DataBaseHistorySearch.DB_COLUMN_DESCRIPTION,
                                    DataBaseHistorySearch.DB_COLUMN_TIMESTAMP,
                                    DataBaseHistorySearch.DB_COLUMN_IS_LOCATION,
                                    DataBaseHistorySearch.DB_COLUMN_LONGITUDE_CUSTOM,
                                    DataBaseHistorySearch.DB_COLUMN_LATITUDE_CUSTOM },
                            new int[] { R.id.search_key,
                                    R.id.search_description,
                                    R.id.search_timestamp,
                                    R.id.search_isLoc,
                                    R.id.search_longitude,
                                    R.id.search_latitude });
                    mSearchHistoryList.setAdapter(simAdapt);
                    mHistoryLayout.setVisibility(View.VISIBLE);
                }

                return true;
            }
        });

        searchView = (SearchView) searchItem.getActionView();
        searchView.setIconified(false);
        searchView.onActionViewExpanded();
        searchView.setIconifiedByDefault(true);
        searchView.setSubmitButtonEnabled(false);
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                performSearch(query);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                mHistoryLayout.setVisibility(View.INVISIBLE);
                return true;
            }
        });

        ImageView closeButton = searchView.findViewById(androidx.appcompat.R.id.search_close_btn);
        closeButton.setOnClickListener(v -> {
            EditText et = findViewById(androidx.appcompat.R.id.search_src_text);
            et.setText("");
            searchView.setQuery("", false);
            mSearchLayout.setVisibility(View.INVISIBLE);
            mHistoryLayout.setVisibility(View.VISIBLE);
        });

        return true;
    }

    private void performSearch(String query) {
        new Thread(() -> {
            GeocoderNominatim geocoder = new GeocoderNominatim(Locale.getDefault(), getPackageName());
            try {
                List<Address> addresses = geocoder.getFromLocationName(query, 10);
                if (addresses != null && !addresses.isEmpty()) {
                    List<Map<String, Object>> data = new ArrayList<>();
                    for (Address addr : addresses) {
                        Map<String, Object> poiItem = new HashMap<>();
                        poiItem.put(POI_NAME, addr.getFeatureName());
                        poiItem.put(POI_ADDRESS, addr.getAddressLine(0));
                        poiItem.put(POI_LONGITUDE, "" + addr.getLongitude());
                        poiItem.put(POI_LATITUDE, "" + addr.getLatitude());
                        data.add(poiItem);
                    }
                    runOnUiThread(() -> {
                        SimpleAdapter simAdapt = new SimpleAdapter(
                                MainActivity.this,
                                data,
                                R.layout.search_poi_item,
                                new String[] { POI_NAME, POI_ADDRESS, POI_LONGITUDE, POI_LATITUDE },
                                new int[] { R.id.poi_name, R.id.poi_address, R.id.poi_longitude, R.id.poi_latitude });
                        mSearchList.setAdapter(simAdapt);
                        mSearchLayout.setVisibility(View.VISIBLE);

                        ContentValues contentValues = new ContentValues();
                        contentValues.put(DataBaseHistorySearch.DB_COLUMN_KEY, query);
                        contentValues.put(DataBaseHistorySearch.DB_COLUMN_DESCRIPTION, "搜索关键字");
                        contentValues.put(DataBaseHistorySearch.DB_COLUMN_IS_LOCATION,
                                DataBaseHistorySearch.DB_SEARCH_TYPE_KEY);
                        contentValues.put(DataBaseHistorySearch.DB_COLUMN_TIMESTAMP, System.currentTimeMillis() / 1000);
                        DataBaseHistorySearch.saveHistorySearch(mSearchHistoryDB, contentValues);
                    });
                } else {
                    runOnUiThread(() -> GoUtils.DisplayToast(MainActivity.this,
                            getResources().getString(R.string.app_search_null)));
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    GoUtils.DisplayToast(MainActivity.this, getResources().getString(R.string.app_error_search));
                    XLog.d(getResources().getString(R.string.app_error_search));
                });
            }
        }).start();
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        if (sensorEvent.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            mAccValues = sensorEvent.values;
        } else if (sensorEvent.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            mMagValues = sensorEvent.values;
        }

        SensorManager.getRotationMatrix(mR, null, mAccValues, mMagValues);
        SensorManager.getOrientation(mR, mDirectionValues);
        mCurrentDirection = (float) Math.toDegrees(mDirectionValues[0]);
        if (mCurrentDirection < 0) {
            mCurrentDirection += 360;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    private void initNavigationView() {
        NavigationView mNavigationView = findViewById(R.id.nav_view);
        mNavigationView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();

            if (id == R.id.nav_history) {
                Intent intent = new Intent(MainActivity.this, HistoryActivity.class);
                startActivity(intent);
            } else if (id == R.id.nav_settings) {
                Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
                startActivity(intent);
            }

            DrawerLayout drawer = findViewById(R.id.drawer_layout);
            drawer.closeDrawer(GravityCompat.START);

            return true;
        });

        // Theme Toggle Button in Footer
        com.google.android.material.button.MaterialButton btnToggle = findViewById(R.id.btn_theme_toggle);
        if (btnToggle != null) {
            int currentNightMode = getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            boolean isDark = currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;

            if (isDark) {
                btnToggle.setIconResource(R.drawable.ic_sunny);
            } else {
                btnToggle.setIconResource(R.drawable.ic_night);
            }

            btnToggle.setOnClickListener(v -> {
                if (isDark) {
                    androidx.appcompat.app.AppCompatDelegate
                            .setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
                } else {
                    androidx.appcompat.app.AppCompatDelegate
                            .setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES);
                }
            });
        }
    }

    private void initMap() {
        mMapView = findViewById(R.id.bdMapView);
        mMapView.setTileSource(TileSourceFactory.MAPNIK);
        mMapView.getZoomController().setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER);
        mMapView.setMultiTouchControls(true);

        // Apply dark mode filter if needed
        int nightModeFlags = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        if (nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
            // High-quality Night Mode Matrix: Inverts colors and slightly adjusts for eye
            // comfort
            float[] nightMatrix = {
                    -1.0f, 0, 0, 0, 255,
                    0, -1.0f, 0, 0, 255,
                    0, 0, -1.0f, 0, 255,
                    0, 0, 0, 1.0f, 0
            };
            mMapView.getOverlayManager().getTilesOverlay()
                    .setColorFilter(new android.graphics.ColorMatrixColorFilter(nightMatrix));
        }

        // Restore map click listener
        MapEventsOverlay mapEventsOverlay = new MapEventsOverlay(new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                mMarkLatLngMap = p;
                mMarkName = null;
                markMap();
                return true;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                return false;
            }
        });
        mMapView.getOverlays().add(0, mapEventsOverlay);

        mMapController = mMapView.getController();
        mMapController.setZoom(mCurrentZoom);
        GeoPoint startPoint = new GeoPoint(39.9042, 116.4074);
        mMapController.setCenter(startPoint);
    }

    private void performReverseGeocoding(GeoPoint p) {
        new Thread(() -> {
            GeocoderNominatim geocoder = new GeocoderNominatim(Locale.getDefault(), getPackageName());
            try {
                List<Address> addresses = geocoder.getFromLocation(p.getLatitude(), p.getLongitude(), 1);
                if (addresses != null && !addresses.isEmpty()) {
                    Address addr = addresses.get(0);
                    mMarkName = addr.getAddressLine(0);

                    runOnUiThread(() -> {
                        View poiView = View.inflate(MainActivity.this, R.layout.location_poi_info, null);
                        TextView poiAddress = poiView.findViewById(R.id.poi_address);
                        TextView poiLongitude = poiView.findViewById(R.id.poi_longitude);
                        TextView poiLatitude = poiView.findViewById(R.id.poi_latitude);

                        poiAddress.setText(mMarkName);
                        poiLongitude.setText(String.valueOf(p.getLongitude()));
                        poiLatitude.setText(String.valueOf(p.getLatitude()));

                        ImageButton ibSave = poiView.findViewById(R.id.poi_save);
                        ibSave.setOnClickListener(v -> {
                            recordCurrentLocation(mMarkLatLngMap.getLongitude(), mMarkLatLngMap.getLatitude());
                            GoUtils.DisplayToast(MainActivity.this,
                                    getResources().getString(R.string.app_location_save));
                        });
                        ImageButton ibCopy = poiView.findViewById(R.id.poi_copy);
                        ibCopy.setOnClickListener(v -> {
                            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                            ClipData mClipData = ClipData.newPlainText("Label", mMarkLatLngMap.toString());
                            cm.setPrimaryClip(mClipData);
                            GoUtils.DisplayToast(MainActivity.this,
                                    getResources().getString(R.string.app_location_copy));
                        });
                        ImageButton ibShare = poiView.findViewById(R.id.poi_share);
                        ibShare.setOnClickListener(v -> ShareUtils.shareText(MainActivity.this, "分享位置",
                                poiLongitude.getText() + "," + poiLatitude.getText()));
                        ImageButton ibFly = poiView.findViewById(R.id.poi_fly);
                        ibFly.setOnClickListener(this::doGoLocation);

                        if (mCurrentMarker != null) {
                            mCurrentMarker.setTitle(mMarkName);
                            mCurrentMarker.setSubDescription(
                                    String.valueOf(p.getLatitude()) + "," + String.valueOf(p.getLongitude()));
                            mCurrentMarker.showInfoWindow();
                        }
                    });
                }
            } catch (Exception e) {
                XLog.e("Reverse Geocoding failed", e);
            }
        }).start();
    }

    private android.graphics.Bitmap getBitmapFromDrawable(int resId) {
        android.graphics.drawable.Drawable drawable = androidx.core.content.ContextCompat.getDrawable(this, resId);
        if (drawable == null)
            return null;

        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(drawable.getIntrinsicWidth(),
                drawable.getIntrinsicHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    private void initMapLocation(boolean hasHistory) {
        mLocationOverlay = new MyLocationNewOverlay(new GpsMyLocationProvider(this), mMapView);

        android.graphics.Bitmap myLocBitmap = getBitmapFromDrawable(R.drawable.ic_mylocation_dot);
        if (myLocBitmap != null) {
            mLocationOverlay.setPersonIcon(myLocBitmap);
            mLocationOverlay.setDirectionIcon(myLocBitmap);
            mLocationOverlay.setPersonAnchor(0.5f, 0.5f);
            mLocationOverlay.setDirectionAnchor(0.5f, 0.5f);
        }

        mLocationOverlay.enableMyLocation();
        mMapView.getOverlays().add(mLocationOverlay);

        if (!hasHistory) {
            mLocationOverlay.runOnFirstFix(() -> runOnUiThread(() -> {
                GeoPoint myLoc = mLocationOverlay.getMyLocation();
                if (myLoc != null) {
                    mMapController.setCenter(myLoc);
                    mMapController.animateTo(myLoc);
                    mCurrentLat = myLoc.getLatitude();
                    mCurrentLon = myLoc.getLongitude();
                    mMarkLatLngMap = myLoc;
                    GoUtils.DisplayToast(MainActivity.this, "已成功获取当前位置");
                }
            }));
        }
    }

    private boolean moveToLastHistoryLocation() {
        if (mLocationHistoryDB == null)
            return false;
        boolean found = false;
        try {
            Cursor cursor = mLocationHistoryDB.query(DataBaseHistoryLocation.TABLE_NAME, null,
                    DataBaseHistoryLocation.DB_COLUMN_ID + " > ?", new String[] { "0" },
                    null, null, DataBaseHistoryLocation.DB_COLUMN_TIMESTAMP + " DESC", "1");

            if (cursor.moveToFirst()) {
                String lngStr = cursor
                        .getString(cursor.getColumnIndexOrThrow(DataBaseHistoryLocation.DB_COLUMN_LONGITUDE_WGS84));
                String latStr = cursor
                        .getString(cursor.getColumnIndexOrThrow(DataBaseHistoryLocation.DB_COLUMN_LATITUDE_WGS84));

                double lng = Double.parseDouble(lngStr);
                double lat = Double.parseDouble(latStr);

                mMarkLatLngMap = new GeoPoint(lat, lng);
                mMarkName = cursor.getString(cursor.getColumnIndexOrThrow(DataBaseHistoryLocation.DB_COLUMN_LOCATION));

                if (mMapController != null) {
                    mMapController.setCenter(mMarkLatLngMap);
                    mMapController.animateTo(mMarkLatLngMap);
                    markMap();
                    GoUtils.DisplayToast(this, "已恢复至上次位置");
                    found = true;
                }
            }
            cursor.close();
        } catch (Exception e) {
            XLog.e("ERROR: moveToLastHistoryLocation", e);
        }
        return found;
    }

    private void initMapButton() {
        ImageButton curPosBtn = this.findViewById(R.id.cur_position);
        curPosBtn.setOnClickListener(v -> resetMap());

        ImageButton zoomInBtn = this.findViewById(R.id.zoom_in);
        zoomInBtn.setOnClickListener(v -> mMapController.zoomIn());

        ImageButton zoomOutBtn = this.findViewById(R.id.zoom_out);
        zoomOutBtn.setOnClickListener(v -> mMapController.zoomOut());

        ImageButton inputPosBtn = this.findViewById(R.id.input_pos);
        inputPosBtn.setOnClickListener(v -> {
            AlertDialog dialog;
            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
            builder.setTitle("请输入经度和纬度");
            View view = LayoutInflater.from(MainActivity.this).inflate(R.layout.location_input, null);
            builder.setView(view);
            dialog = builder.show();

            final EditText dialog_lng = view.findViewById(R.id.joystick_longitude);
            final EditText dialog_lat = view.findViewById(R.id.joystick_latitude);

            final EditText dialog_ip = view.findViewById(R.id.input_ip_address);
            final com.google.android.material.button.MaterialButton btnGetIp = view
                    .findViewById(R.id.btn_get_ip_location);

            btnGetIp.setOnClickListener(v3 -> {
                String ip = dialog_ip.getText().toString();
                GoUtils.getIpLocation(ip, new GoUtils.LocationCallback() {
                    @Override
                    public void onSuccess(double lat, double lng) {
                        runOnUiThread(() -> {
                            dialog_lat.setText(String.valueOf(lat));
                            dialog_lng.setText(String.valueOf(lng));
                            GoUtils.DisplayToast(MainActivity.this,
                                    getResources().getString(R.string.ip_location_success));
                        });
                    }

                    @Override
                    public void onError(String msg) {
                        runOnUiThread(() -> {
                            GoUtils.DisplayToast(MainActivity.this,
                                    getResources().getString(R.string.ip_location_error) + ": " + msg);
                        });
                    }
                });
            });

            final MaterialButton btnCancel = view.findViewById(R.id.input_position_cancel);
            final MaterialButton btnGo = view.findViewById(R.id.input_position_ok);

            btnGo.setOnClickListener(v2 -> {
                String dialog_lng_str = dialog_lng.getText().toString();
                String dialog_lat_str = dialog_lat.getText().toString();

                if (TextUtils.isEmpty(dialog_lng_str) || TextUtils.isEmpty(dialog_lat_str)) {
                    GoUtils.DisplayToast(MainActivity.this, getResources().getString(R.string.app_error_input));
                } else {
                    try {
                        double dialog_lng_double = Double.parseDouble(dialog_lng_str);
                        double dialog_lat_double = Double.parseDouble(dialog_lat_str);

                        if (dialog_lng_double > 180.0 || dialog_lng_double < -180.0) {
                            GoUtils.DisplayToast(MainActivity.this,
                                    getResources().getString(R.string.app_error_longitude));
                        } else if (dialog_lat_double > 90.0 || dialog_lat_double < -90.0) {
                            GoUtils.DisplayToast(MainActivity.this,
                                    getResources().getString(R.string.app_error_latitude));
                        } else {
                            mMarkLatLngMap = new GeoPoint(dialog_lat_double, dialog_lng_double);
                            mMarkName = "手动输入的坐标";

                            markMap();
                            mMapController.setCenter(mMarkLatLngMap);

                            dialog.dismiss();
                        }
                    } catch (NumberFormatException e) {
                        GoUtils.DisplayToast(MainActivity.this, getResources().getString(R.string.app_error_input));
                    }
                }
            });

            btnCancel.setOnClickListener(v1 -> dialog.dismiss());
        });
    }

    private void markMap() {
        if (mMarkLatLngMap != null) {
            if (mCurrentMarker != null) {
                mMapView.getOverlays().remove(mCurrentMarker);
            }
            mCurrentMarker = new Marker(mMapView);
            mCurrentMarker.setPosition(mMarkLatLngMap);
            mCurrentMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            mCurrentMarker.setIcon(androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_marker_pin));
            mCurrentMarker.setTitle("Selected Location");
            mMapView.getOverlays().add(mCurrentMarker);
            mMapView.invalidate();
        }
    }

    private void resetMap() {
        if (mLocationOverlay.getMyLocation() != null) {
            mMapController.animateTo(mLocationOverlay.getMyLocation());
            mMarkLatLngMap = mLocationOverlay.getMyLocation();
        } else {
            if (!GoUtils.isGpsOpened(this)) {
                GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_gps));
            } else {
                GoUtils.DisplayToast(this, "定位失败：请确保处于室外开阔地带并稍后重试");
            }
        }
    }

    public static boolean showLocation(String name, String longitude, String latitude) {
        return false;
    }

    private void initGoBtn() {
        mButtonStart = findViewById(R.id.faBtnStart);
        mButtonStart.setOnClickListener(this::doGoLocation);
    }

    private void startGoLocation() {
        Intent serviceGoIntent = new Intent(MainActivity.this, ServiceGo.class);
        bindService(serviceGoIntent, mConnection, BIND_AUTO_CREATE);
        serviceGoIntent.putExtra(LNG_MSG_ID, mMarkLatLngMap.getLongitude());
        serviceGoIntent.putExtra(LAT_MSG_ID, mMarkLatLngMap.getLatitude());
        double alt = Double.parseDouble(sharedPreferences.getString("setting_altitude", "55.0"));
        serviceGoIntent.putExtra(ALT_MSG_ID, alt);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceGoIntent);
        } else {
            startService(serviceGoIntent);
        }
        XLog.d("startForegroundService: ServiceGo");

        isMockServStart = true;
    }

    private void stopGoLocation() {
        unbindService(mConnection);
        Intent serviceGoIntent = new Intent(MainActivity.this, ServiceGo.class);
        stopService(serviceGoIntent);
        isMockServStart = false;
    }

    private void doGoLocation(View v) {
        if (!GoUtils.isNetworkAvailable(this)) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_network));
            return;
        }

        if (!GoUtils.isGpsOpened(this)) {
            GoUtils.showEnableGpsDialog(this);
            return;
        }

        if (!Settings.canDrawOverlays(getApplicationContext())) {
            GoUtils.showEnableFloatWindowDialog(this);
            XLog.e("无悬浮窗权限!");
            return;
        }

        if (isMockServStart) {
            if (mMarkLatLngMap == null) {
                stopGoLocation();
                Snackbar.make(v, "模拟位置已终止", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();
                mButtonStart.setImageResource(R.drawable.ic_position);
            } else {
                double alt = Double.parseDouble(sharedPreferences.getString("setting_altitude", "55.0"));
                mServiceBinder.setPosition(mMarkLatLngMap.getLongitude(), mMarkLatLngMap.getLatitude(), alt);
                Snackbar.make(v, "已传送到新位置", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();

                recordCurrentLocation(mMarkLatLngMap.getLongitude(), mMarkLatLngMap.getLatitude());

                if (mCurrentMarker != null)
                    mMapView.getOverlays().remove(mCurrentMarker);
                mMarkLatLngMap = null;
                mMapView.invalidate();

                if (GoUtils.isWifiEnabled(MainActivity.this) && !isWifiWarningShown) {
                    GoUtils.showWifiWarningToast(MainActivity.this);
                    isWifiWarningShown = true;
                }
            }
        } else {
            if (!GoUtils.isAllowMockLocation(this)) {
                GoUtils.showEnableMockLocationDialog(this);
                XLog.e("无模拟位置权限!");
            } else {
                if (mMarkLatLngMap == null) {
                    Snackbar.make(v, "请先点击地图位置或者搜索位置", Snackbar.LENGTH_LONG)
                            .setAction("Action", null).show();
                } else {
                    startGoLocation();
                    mButtonStart.setImageResource(R.drawable.ic_fly);
                    Snackbar.make(v, "模拟位置已启动", Snackbar.LENGTH_LONG)
                            .setAction("Action", null).show();

                    recordCurrentLocation(mMarkLatLngMap.getLongitude(), mMarkLatLngMap.getLatitude());

                    if (mCurrentMarker != null)
                        mMapView.getOverlays().remove(mCurrentMarker);
                    mMarkLatLngMap = null;
                    mMapView.invalidate();

                    if (GoUtils.isWifiEnabled(MainActivity.this) && !isWifiWarningShown) {
                        GoUtils.showWifiWarningToast(MainActivity.this);
                        isWifiWarningShown = true;
                    }
                }
            }
        }
    }

    private void initStoreHistory() {
        try {
            DataBaseHistoryLocation dbLocation = new DataBaseHistoryLocation(getApplicationContext());
            mLocationHistoryDB = dbLocation.getWritableDatabase();
            DataBaseHistorySearch dbHistory = new DataBaseHistorySearch(getApplicationContext());
            mSearchHistoryDB = dbHistory.getWritableDatabase();
        } catch (Exception e) {
            XLog.e("ERROR: sqlite init error");
        }
    }

    private List<Map<String, Object>> getSearchHistory() {
        List<Map<String, Object>> data = new ArrayList<>();

        try {
            Cursor cursor = mSearchHistoryDB.query(DataBaseHistorySearch.TABLE_NAME, null,
                    DataBaseHistorySearch.DB_COLUMN_ID + " > ?", new String[] { "0" },
                    null, null, DataBaseHistorySearch.DB_COLUMN_TIMESTAMP + " DESC", null);

            while (cursor.moveToNext()) {
                Map<String, Object> searchHistoryItem = new HashMap<>();
                searchHistoryItem.put(DataBaseHistorySearch.DB_COLUMN_KEY, cursor.getString(1));
                searchHistoryItem.put(DataBaseHistorySearch.DB_COLUMN_DESCRIPTION, cursor.getString(2));
                searchHistoryItem.put(DataBaseHistorySearch.DB_COLUMN_TIMESTAMP, "" + cursor.getInt(3));
                searchHistoryItem.put(DataBaseHistorySearch.DB_COLUMN_IS_LOCATION, "" + cursor.getInt(4));
                searchHistoryItem.put(DataBaseHistorySearch.DB_COLUMN_LONGITUDE_CUSTOM, cursor.getString(7));
                searchHistoryItem.put(DataBaseHistorySearch.DB_COLUMN_LATITUDE_CUSTOM, cursor.getString(8));
                data.add(searchHistoryItem);
            }
            cursor.close();
        } catch (Exception e) {
            XLog.e("ERROR: getSearchHistory");
        }

        return data;
    }

    private void recordCurrentLocation(double lng, double lat) {
        new Thread(() -> {
            GeocoderNominatim geocoder = new GeocoderNominatim(Locale.getDefault(), getPackageName());
            try {
                List<Address> addresses = geocoder.getFromLocation(lat, lng, 1);
                String addressStr = mMarkName != null ? mMarkName : "Unknown Location";
                if (addresses != null && !addresses.isEmpty()) {
                    addressStr = addresses.get(0).getAddressLine(0);
                }

                ContentValues contentValues = new ContentValues();
                contentValues.put(DataBaseHistoryLocation.DB_COLUMN_LOCATION, addressStr);
                contentValues.put(DataBaseHistoryLocation.DB_COLUMN_LONGITUDE_WGS84, String.valueOf(lng));
                contentValues.put(DataBaseHistoryLocation.DB_COLUMN_LATITUDE_WGS84, String.valueOf(lat));
                contentValues.put(DataBaseHistoryLocation.DB_COLUMN_TIMESTAMP, System.currentTimeMillis() / 1000);
                contentValues.put(DataBaseHistoryLocation.DB_COLUMN_LONGITUDE_CUSTOM, Double.toString(lng));
                contentValues.put(DataBaseHistoryLocation.DB_COLUMN_LATITUDE_CUSTOM, Double.toString(lat));

                DataBaseHistoryLocation.saveHistoryLocation(mLocationHistoryDB, contentValues);
            } catch (Exception e) {
                XLog.e("ERROR: recordCurrentLocation", e);
            }
        }).start();
    }

    private void initSearchView() {
        mSearchLayout = findViewById(R.id.search_linear);
        mHistoryLayout = findViewById(R.id.search_history_linear);

        mSearchList = findViewById(R.id.search_list_view);
        mSearchList.setOnItemClickListener((parent, view, position, id) -> {
            String lng = ((TextView) view.findViewById(R.id.poi_longitude)).getText().toString();
            String lat = ((TextView) view.findViewById(R.id.poi_latitude)).getText().toString();
            mMarkName = ((TextView) view.findViewById(R.id.poi_name)).getText().toString();
            mMarkLatLngMap = new GeoPoint(Double.parseDouble(lat), Double.parseDouble(lng));

            mMapController.setCenter(mMarkLatLngMap);
            markMap();

            ContentValues contentValues = new ContentValues();
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_KEY, mMarkName);
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_DESCRIPTION,
                    ((TextView) view.findViewById(R.id.poi_address)).getText().toString());
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_IS_LOCATION, DataBaseHistorySearch.DB_SEARCH_TYPE_RESULT);
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_LONGITUDE_CUSTOM, lng);
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_LATITUDE_CUSTOM, lat);
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_LONGITUDE_WGS84, lng);
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_LATITUDE_WGS84, lat);
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_TIMESTAMP, System.currentTimeMillis() / 1000);

            DataBaseHistorySearch.saveHistorySearch(mSearchHistoryDB, contentValues);
            mSearchLayout.setVisibility(View.INVISIBLE);
            searchItem.collapseActionView();
        });
        mSearchHistoryList = findViewById(R.id.search_history_list_view);
        mSearchHistoryList.setOnItemClickListener((parent, view, position, id) -> {
            String searchDescription = ((TextView) view.findViewById(R.id.search_description)).getText().toString();
            String searchKey = ((TextView) view.findViewById(R.id.search_key)).getText().toString();
            String searchIsLoc = ((TextView) view.findViewById(R.id.search_isLoc)).getText().toString();

            if (searchIsLoc.equals("1")) {
                String lng = ((TextView) view.findViewById(R.id.search_longitude)).getText().toString();
                String lat = ((TextView) view.findViewById(R.id.search_latitude)).getText().toString();
                mMarkLatLngMap = new GeoPoint(Double.parseDouble(lat), Double.parseDouble(lng));
                mMapController.setCenter(mMarkLatLngMap);
                markMap();

                mHistoryLayout.setVisibility(View.INVISIBLE);
                searchItem.collapseActionView();
                ContentValues contentValues = new ContentValues();
                contentValues.put(DataBaseHistorySearch.DB_COLUMN_KEY, searchKey);
                contentValues.put(DataBaseHistorySearch.DB_COLUMN_DESCRIPTION, searchDescription);
                contentValues.put(DataBaseHistorySearch.DB_COLUMN_IS_LOCATION,
                        DataBaseHistorySearch.DB_SEARCH_TYPE_RESULT);
                contentValues.put(DataBaseHistorySearch.DB_COLUMN_LONGITUDE_CUSTOM, lng);
                contentValues.put(DataBaseHistorySearch.DB_COLUMN_LATITUDE_CUSTOM, lat);
                contentValues.put(DataBaseHistorySearch.DB_COLUMN_LONGITUDE_WGS84, lng);
                contentValues.put(DataBaseHistorySearch.DB_COLUMN_LATITUDE_WGS84, lat);
                contentValues.put(DataBaseHistorySearch.DB_COLUMN_TIMESTAMP, System.currentTimeMillis() / 1000);

                DataBaseHistorySearch.saveHistorySearch(mSearchHistoryDB, contentValues);
            } else if (searchIsLoc.equals("0")) {
                try {
                    searchView.setQuery(searchKey, true);
                } catch (Exception e) {
                    GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_search));
                    XLog.e(getResources().getString(R.string.app_error_search));
                }
            } else {
                XLog.e(getResources().getString(R.string.app_error_param));
            }
        });
        mSearchHistoryList.setOnItemLongClickListener((parent, view, position, id) -> {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("警告")
                    .setMessage("确定要删除该项搜索记录吗?")
                    .setPositiveButton("确定", (dialog, which) -> {
                        String searchKey = ((TextView) view.findViewById(R.id.search_key)).getText().toString();

                        try {
                            mSearchHistoryDB.delete(DataBaseHistorySearch.TABLE_NAME,
                                    DataBaseHistorySearch.DB_COLUMN_KEY + " = ?", new String[] { searchKey });
                            List<Map<String, Object>> data = getSearchHistory();

                            if (!data.isEmpty()) {
                                SimpleAdapter simAdapt = new SimpleAdapter(
                                        MainActivity.this,
                                        data,
                                        R.layout.search_item,
                                        new String[] { DataBaseHistorySearch.DB_COLUMN_KEY,
                                                DataBaseHistorySearch.DB_COLUMN_DESCRIPTION,
                                                DataBaseHistorySearch.DB_COLUMN_TIMESTAMP,
                                                DataBaseHistorySearch.DB_COLUMN_IS_LOCATION,
                                                DataBaseHistorySearch.DB_COLUMN_LONGITUDE_CUSTOM,
                                                DataBaseHistorySearch.DB_COLUMN_LATITUDE_CUSTOM },
                                        new int[] { R.id.search_key, R.id.search_description, R.id.search_timestamp,
                                                R.id.search_isLoc, R.id.search_longitude, R.id.search_latitude });
                                mSearchHistoryList.setAdapter(simAdapt);
                                mHistoryLayout.setVisibility(View.VISIBLE);
                            }
                        } catch (Exception e) {
                            XLog.e("ERROR: delete database error");
                            GoUtils.DisplayToast(MainActivity.this,
                                    getResources().getString(R.string.history_delete_error));
                        }
                    })
                    .setNegativeButton("取消",
                            (dialog, which) -> {
                            })
                    .show();
            return true;
        });
    }
}
