package com.chargeisland.app;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final int REQ_POST_NOTIFICATIONS = 100;

    private TextView tvLevel, tvStatus, tvPower, tvTemp, tvVoltage, tvEta, tvPlug, tvHint, tvPromoted;
    private BatteryCurveView curve;
    private Button btnToggle, btnLiveSettings, btnCheckUpdate, btnChangelog;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            render(intent);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvLevel = findViewById(R.id.tvLevel);
        tvStatus = findViewById(R.id.tvStatus);
        tvPower = findViewById(R.id.tvPower);
        tvTemp = findViewById(R.id.tvTemp);
        tvVoltage = findViewById(R.id.tvVoltage);
        tvEta = findViewById(R.id.tvEta);
        tvPlug = findViewById(R.id.tvPlug);
        tvHint = findViewById(R.id.tvHint);
        tvPromoted = findViewById(R.id.tvPromoted);
        curve = findViewById(R.id.curve);
        btnToggle = findViewById(R.id.btnToggle);
        btnLiveSettings = findViewById(R.id.btnLiveSettings);
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate);
        btnChangelog = findViewById(R.id.btnChangelog);

        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleService();
            }
        });

        btnLiveSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPromotedSettings();
            }
        });

        btnCheckUpdate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UpdateChecker.check(MainActivity.this, true);
            }
        });

        btnChangelog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ChangelogActivity.class));
            }
        });

        // 启动静默自动检查更新（机制对齐 VideoSplitter）：静默进行，发现新版才弹窗
        btnCheckUpdate.postDelayed(new Runnable() {
            @Override
            public void run() {
                UpdateChecker.check(MainActivity.this, false);
            }
        }, 1200);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_POST_NOTIFICATIONS);
        }

        // 现代化视觉：电量大字黄→橙渐变（宽度就绪后再挂 shader）
        tvLevel.post(new Runnable() {
            @Override
            public void run() {
                int w = tvLevel.getWidth();
                if (w > 0) {
                    tvLevel.getPaint().setShader(new android.graphics.LinearGradient(
                            0, 0, w, 0, 0xFFFFD84D, 0xFFFF8F00,
                            android.graphics.Shader.TileMode.CLAMP));
                    tvLevel.invalidate();
                }
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // 更新下载完成通知点击 → 直接拉起安装
        if (intent != null && intent.getBooleanExtra("install_update", false)) {
            UpdateChecker.installApk(this, UpdateChecker.updateApkFile(this));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 安装授权跳设置返回后自动继续安装
        UpdateChecker.resumePendingInstall(this);
        try {
            registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (Exception ignored) {
        }
        updateToggleState();
        updatePromotedUi();
        curve.invalidate();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception ignored) {
        }
    }

    private void toggleService() {
        Intent svc = new Intent(this, ChargingService.class);
        if (ChargeDataStore.serviceRunning) {
            stopService(svc);
            tvHint.setText("已停止。开启后将常驻通知并实时展示充电进度。");
        } else {
            startForegroundService(svc);
            tvHint.setText("监控中：通知/锁屏/背屏将实时显示充电进度；插电/开机自动启动。");
            offerBatteryExemption();
        }
        // 服务启动是异步的，稍后刷新按钮状态
        btnToggle.postDelayed(this::updateToggleState, 400);
    }

    /** v1.7 后台保活：未豁免电池优化时引导一次（豁免后插电自动拉起与后台轮询更可靠）。 */
    private void offerBatteryExemption() {
        try {
            android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
            if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
            new android.app.AlertDialog.Builder(this)
                    .setTitle("开启后台保活")
                    .setMessage("允许本应用不受电池限制，插电时可自动启动、后台持续刷新充电进度。")
                    .setPositiveButton("去设置", (d, w) -> {
                        try {
                            startActivity(new Intent(
                                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    android.net.Uri.parse("package:" + getPackageName())));
                        } catch (Exception ignored) {
                        }
                    })
                    .setNegativeButton("暂不", null)
                    .show();
        } catch (Exception ignored) {
        }
    }

    private void updateToggleState() {
        boolean running = ChargeDataStore.serviceRunning;
        btnToggle.setText(running ? "停止监控" : "开始监控充电进度");
        if (running) {
            tvHint.setText("监控中：通知/锁屏/背屏将实时显示充电进度。");
        }
    }

    private void render(Intent battery) {
        if (battery == null) return;
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level < 0 || scale <= 0) return;
        int pct = Math.round(level * 100f / scale);

        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        boolean full = status == BatteryManager.BATTERY_STATUS_FULL || pct >= 100;
        int voltageMv = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        int tempDeci = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);

        tvLevel.setText(pct + "%");
        if (!charging) tvStatus.setText("未充电");
        else if (full) tvStatus.setText("充电完成");
        else tvStatus.setText("正在充电");

        BatteryManager bm = getSystemService(BatteryManager.class);
        double watts = -1;
        if (bm != null && charging) {
            int raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (raw != Integer.MIN_VALUE && raw != 0 && voltageMv > 0) {
                watts = (voltageMv / 1000.0) * (Math.abs(raw) / 1_000_000.0);
            }
        }
        tvPower.setText(watts >= 0 ? String.format("%.1f W", watts) : "—");
        tvTemp.setText(String.format("%.1f ℃", tempDeci / 10f));
        tvVoltage.setText(voltageMv > 0 ? String.format("%.2f V", voltageMv / 1000f) : "—");

        if (!charging) {
            tvEta.setText("—");
        } else if (full) {
            tvEta.setText("已充满");
        } else {
            double rate = ChargeDataStore.recentRatePerMin(System.currentTimeMillis());
            if (rate > 0.001) {
                int remain = (int) Math.ceil((100 - pct) / rate);
                tvEta.setText(remain > 0 && remain < 24 * 60
                        ? (remain >= 60 ? String.format("%d 小时 %d 分", remain / 60, remain % 60)
                                         : remain + " 分钟")
                        : "即将充满");
            } else {
                tvEta.setText("计算中…");
            }
        }

        String plug;
        if (plugged == BatteryManager.BATTERY_PLUGGED_AC) plug = "交流快充";
        else if (plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) plug = "无线充电";
        else if (plugged == BatteryManager.BATTERY_PLUGGED_USB) plug = "USB 充电";
        else plug = "未插入充电器";
        tvPlug.setText(plug);

        updatePromotedUi();
        curve.invalidate();
    }

    /** 跳转系统「实时动态」管理页；旧系统无该页面时回退到应用通知设置。 */
    private void openPromotedSettings() {
        try {
            Intent i = new Intent("android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS");
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
            return;
        } catch (Exception ignored) {
        }
        try {
            Intent i = new Intent("android.settings.APP_NOTIFICATION_SETTINGS");
            i.putExtra("android.provider.extra.APP_PACKAGE", getPackageName());
            startActivity(i);
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "无法打开设置页",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** 展示实时动态（Live Updates）接入状态。 */
    private void updatePromotedUi() {
        if (!ChargeDataStore.promotedApiAvailable) {
            tvPromoted.setText("实时动态：需 Android 16+ 系统");
        } else if (!ChargeDataStore.canPostPromoted) {
            tvPromoted.setText("实时动态：未开启，点右侧按钮开启");
        } else if (ChargeDataStore.promotedActive) {
            tvPromoted.setText("实时动态：已提升展示 ✓");
        } else if (ChargeDataStore.promotable) {
            tvPromoted.setText("实时动态：资格就绪，系统提升中");
        } else {
            tvPromoted.setText("实时动态：等待通知就绪");
        }
    }
}
