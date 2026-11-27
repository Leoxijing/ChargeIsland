package com.chargeisland.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * 前台服务：轮询电池状态，刷新充电进度通知。
 * 通知形态完全对齐 VideoSplitter（已验证可上背屏）：纯标准通知、不写任何厂商私有 extras
 * （v1.6 移除 miui.focus.param 超级岛参数——带厂商参数的通知疑似不参与实时动态→背屏映射），
 * 每秒刷新，API 36+ 无条件请求 Promoted 提升。
 */
public class ChargingService extends android.app.Service {

    private static final int NOTIFY_ID = 1001;
    // v1.6 换新渠道：对齐 VideoSplitter 的 IMPORTANCE_LOW（渠道属性创建后不可修改）
    private static final String CHANNEL_ID = "charging_island_v3";
    // v1.6：3s → 1s，对齐 VideoSplitter 的高频进度更新（促进系统持续评估提升资格）
    private static final long POLL_INTERVAL_MS = 1000;

    private NotificationManager nm;
    private Handler handler;
    private Runnable pollRunnable;
    private boolean registered = false;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refresh(intent);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "充电进度", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        channel.setSound(null, null);
        // 锁屏/息屏/背屏公开展示完整内容（默认 PRIVATE 会隐藏详情，导致背屏只见"内容已隐藏"）
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(channel);

        // 实时动态（Live Updates）：查询系统是否允许发送（Android 16+）
        try {
            java.lang.reflect.Method m = NotificationManager.class.getMethod("canPostPromotedNotifications");
            ChargeDataStore.canPostPromoted = (Boolean) m.invoke(nm);
            ChargeDataStore.promotedApiAvailable = true;
            android.util.Log.i("ChargeIsland", "canPostPromotedNotifications=" + ChargeDataStore.canPostPromoted);
        } catch (Exception e) {
            android.util.Log.i("ChargeIsland", "promoted API unavailable (system below Android 16)");
        }

        handler = new Handler(Looper.getMainLooper());
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                refresh(stickyIntent());
                handler.postDelayed(this, POLL_INTERVAL_MS);
            }
        };
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ChargeDataStore.loadSettings(this);
        // v4.1修复：startForegroundService() 后系统要求 5 秒内必须 startForeground()，
        // 否则抛 ForegroundServiceDidNotStartInTimeException 使进程崩溃——v4.0 早退路径
        // 直接 stopSelf() 未满足前台契约，导致关闭常驻开关后未充电点"开始监控"即闪退。
        // 修复：先满足前台契约，随即移除通知并停止（IMPORTANCE_LOW 无横幅，基本无感）。
        if (!isChargingNow() && !ChargeDataStore.keepIdle) {
            Notification base = buildNotification("充电进度岛", "未充电，已按设置停止常驻", "", 0, 0);
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFY_ID, base, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFY_ID, base);
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        ChargeDataStore.serviceRunning = true;
        Notification base = buildNotification("读取中…", "正在获取充电状态…", "", 0, 0);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFY_ID, base, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFY_ID, base);
        }
        if (!registered) {
            try {
                registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                registered = true;
            } catch (Exception ignored) {
            }
        }
        handler.removeCallbacks(pollRunnable);
        handler.post(pollRunnable);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(pollRunnable);
        if (registered) {
            try {
                unregisterReceiver(batteryReceiver);
            } catch (Exception ignored) {
            }
            registered = false;
        }
        ChargeDataStore.serviceRunning = false;
        nm.cancel(NOTIFY_ID);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Intent stickyIntent() {
        try {
            return registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (Exception e) {
            return null;
        }
    }

    /** v4.0：启动时读取当前充电状态；读取失败保守按充电处理，避免误退出。 */
    private boolean isChargingNow() {
        Intent i = stickyIntent();
        if (i == null) return true;
        int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return st == BatteryManager.BATTERY_STATUS_CHARGING
                || st == BatteryManager.BATTERY_STATUS_FULL;
    }

    private void refresh(Intent battery) {
        if (battery == null) return;

        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level < 0 || scale <= 0) return;
        int pct = Math.round(level * 100f / scale);

        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        boolean full = status == BatteryManager.BATTERY_STATUS_FULL || pct >= 100;

        // v4.0 开关：轮询中发现已拔电且关闭常驻 → 退出服务（onDestroy 取消通知）
        if (!charging && !ChargeDataStore.keepIdle) {
            stopSelf();
            return;
        }

        int voltageMv = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        int tempDeci = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);

        BatteryManager bm = getSystemService(BatteryManager.class);
        int currentUa = 0;
        boolean currentValid = false;
        if (bm != null) {
            int raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (raw != Integer.MIN_VALUE && raw != 0) {
                currentUa = Math.abs(raw);
                currentValid = true;
            }
        }

        // 曲线采样：仅在电量百分比变化时记录；充电与放电分开两组样本
        if (pct != ChargeDataStore.lastPct || !charging && ChargeDataStore.lastCharging) {
            if (charging && !ChargeDataStore.lastCharging) {
                ChargeDataStore.clear(); // 新一轮充电，重新起线
            }
            if (!charging && ChargeDataStore.lastCharging) {
                ChargeDataStore.clearDischarge(); // 拔电，新一轮放电
            }
            if (charging) {
                ChargeDataStore.addPoint(System.currentTimeMillis(), pct);
            } else {
                ChargeDataStore.addDischargePoint(System.currentTimeMillis(), pct);
            }
        }
        ChargeDataStore.lastPct = pct;
        ChargeDataStore.lastCharging = charging;

        // 功率估算：P(W) = 电压(V) × 电流(A)；充电时为充电功率，未充电时为耗电功率
        double watts = -1;
        if (currentValid && voltageMv > 0) {
            watts = (voltageMv / 1000.0) * (currentUa / 1_000_000.0);
        }

        String plugLabel;
        if (plugged == BatteryManager.BATTERY_PLUGGED_AC) plugLabel = "交流快充";
        else if (plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) plugLabel = "无线充电";
        else if (plugged == BatteryManager.BATTERY_PLUGGED_USB) plugLabel = "USB 充电";
        else plugLabel = "未插入充电器";

        // 通知样式（v2.3）：
        // - 第一行（标题）= 温度 · 功率（充电=充电功率，未充电=耗电功率）
        // - 第二行 = 状态 · 电量
        // - 第三行 = 预计充满/耗尽时间 · 推测速度 · 准确度
        String statusLabel;
        if (!charging) statusLabel = "未充电";
        else if (full) statusLabel = "已充满";
        else statusLabel = "充电中";

        String powerPart;
        if (!full && status == BatteryManager.BATTERY_STATUS_CHARGING && watts >= 0) {
            powerPart = String.format("%.1fW", watts);   // 充电功率
        } else if (plugged == 0 && watts >= 0) {
            powerPart = String.format("%.1fW", watts);   // 耗电功率
        } else {
            powerPart = "—";
        }
        // v3.1：图标位已是温度（31°），第一行只留功率；温度移到第二行开头
        String notifTitle = powerPart;
        String line2 = String.format("%.0f℃", tempDeci / 10f) + " · " + statusLabel + " · " + pct + "%";

        String line3;
        if (status == BatteryManager.BATTERY_STATUS_CHARGING && !full) {
            ChargeDataStore.RateEstimate r =
                    ChargeDataStore.chargeRate(System.currentTimeMillis());
            if (r.usable() && r.slopePerMin > 0) {
                int remain = (int) Math.ceil((100 - pct) / r.slopePerMin);
                String eta = remain <= 0 ? "即将充满"
                        : remain > 24 * 60 ? "超过 24 小时"
                        : formatDuration(remain);
                line3 = "预计充满时间" + eta + " · " + formatRate(r) + " · 准确度" + r.accuracyLabel();
            } else {
                line3 = "正在计算预计充满时间…";
            }
        } else if (plugged == 0) {
            ChargeDataStore.RateEstimate r =
                    ChargeDataStore.dischargeRate(System.currentTimeMillis());
            if (r.usable() && r.slopePerMin < 0) {
                int remain = (int) Math.ceil(pct / -r.slopePerMin);
                String eta = remain <= 0 ? "即将耗尽"
                        : remain > 7 * 24 * 60 ? "超过 7 天"
                        : formatDuration(remain);
                line3 = "预计耗尽时间" + eta + " · " + formatRate(r) + " · 准确度" + r.accuracyLabel();
            } else {
                line3 = "正在计算预计耗尽时间…";
            }
        } else if (full) {
            line3 = "已充满";
        } else {
            line3 = plugLabel; // 插电但未在充电
        }

        // 每次轮询都刷新通知（对齐 VideoSplitter：高频 notify，不因内容未变而跳过，
        // 让系统持续重新评估 Promoted 提升资格）
        Notification notification = buildNotification(notifTitle, line2, line3, pct, tempDeci);
        nm.notify(NOTIFY_ID, notification);
        updatePromotedState(notification);
    }

    /** 分钟数 → "X小时Y分钟" / "X分钟"。 */
    private static String formatDuration(int minutes) {
        return minutes >= 60
                ? String.format("%d小时%d分钟", minutes / 60, minutes % 60)
                : minutes + "分钟";
    }

    /** 速率 → "4.2%/h"（取绝对值，方向由充满/耗尽语义表达）。 */
    private static String formatRate(ChargeDataStore.RateEstimate r) {
        return String.format("%.1f%%/h", Math.abs(r.slopePerMin) * 60);
    }

    /**
     * 实时动态（Promoted Notifications）状态跟踪：
     * hasPromotableCharacteristics() 校验通知资格，FLAG_PROMOTED_ONGOING + getActiveNotifications
     * 读取系统实际提升结果，canPostPromotedNotifications() 读取用户开关；全部反射调用以兼容低版本系统。
     */
    private void updatePromotedState(Notification notification) {
        if (notification != null) {
            try {
                java.lang.reflect.Method qual = Notification.class.getMethod("hasPromotableCharacteristics");
                ChargeDataStore.promotable = (Boolean) qual.invoke(notification);
                ChargeDataStore.promotedApiAvailable = true;
            } catch (Exception e) {
                ChargeDataStore.promotedApiAvailable = false;
                return;
            }
        }
        try {
            java.lang.reflect.Method cm = NotificationManager.class.getMethod("canPostPromotedNotifications");
            ChargeDataStore.canPostPromoted = (Boolean) cm.invoke(nm);
        } catch (Exception ignored) {
        }
        try {
            int flag = Notification.class.getField("FLAG_PROMOTED_ONGOING").getInt(null);
            android.service.notification.StatusBarNotification[] active = nm.getActiveNotifications();
            boolean promoted = false;
            if (active != null) {
                for (android.service.notification.StatusBarNotification sb : active) {
                    if (sb.getId() == NOTIFY_ID && (sb.getNotification().flags & flag) != 0) {
                        promoted = true;
                        break;
                    }
                }
            }
            ChargeDataStore.promotedActive = promoted;
        } catch (Exception ignored) {
        }
    }

    /**
     * 温度 → 预制资源型图标 id（ic_temp_0..60 / ic_temp_unknown）。
     * v2.9：澎湃普通通知渲染只认资源型 smallIcon（setSmallIcon(int)→ResourceIcon），
     * 位图对象型（Icon.createWithBitmap）会被回退为应用图标——v2.7/2.8 实测：
     * 提升态认位图，普通态回退；资源型是 2.3 时代普通态正常显示闪电的同路径。
     * 找不到资源返回 0（调用方回退透明资源）。
     */
    private int resolveTempRes(int tempTenth) {
        int rounded = tempTenth <= 0 ? -1 : Math.round(tempTenth / 10f);
        String name = rounded < 0 ? "ic_temp_unknown"
                : rounded > 60 ? "ic_temp_60" : "ic_temp_" + rounded;
        return getResources().getIdentifier(name, "drawable", getPackageName());
    }

    private Notification buildNotification(String title, String line2, String line3,
                                           int pct, int tempTenth) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 参考图样式：单行摘要 = "第二行 · 说明"，展开态 = 两行（功率行 + 说明行）
        String summary = (line3 == null || line3.isEmpty()) ? line2 : line2 + " · " + line3;
        String big = (line3 == null || line3.isEmpty()) ? line2 : line2 + "\n" + line3;

        // 字段对齐 VideoSplitter StatusBarProgress.build：category/ticker/onlyAlertOnce/PUBLIC 全套
        // smallIcon = 资源型温度图标（ic_temp_N），普通通知态不被系统回退为应用图标
        int tempRes = resolveTempRes(tempTenth);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(tempRes != 0 ? tempRes : R.drawable.ic_stat_transparent)
                .setContentTitle(title)
                .setContentText(summary)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                // 实时动态（Live Updates）资格要求：BigTextStyle + contentTitle + ongoing
                .setStyle(new Notification.BigTextStyle().bigText(big))
                .setProgress(100, Math.max(0, Math.min(100, pct)), false)
                .setColor(0xFFFFC400)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setContentIntent(pi)
                // v3.0：恒定 ongoing——实时动态（Live Updates）资格要求 ongoing，
                // 未充电时 setOngoing(false) 会让通知永远无法提升上岛，澎湃对
                // 实时动态应用的"未提升通知"固定用应用图标锚点（改 smallIcon 类型无效，
                // v2.7/2.8/2.9 三轮实测），未充电也上岛后图标位才显示温度。
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setTicker(title)
                .setShowWhen(true);
        // Android 16 Promoted Notifications：framework Builder 没有 setRequestPromotedOngoing 方法
        //（反射调用会静默失败导致提升请求从未写入），官方要求写入 extras key，字面值同
        // androidx NotificationCompat.EXTRA_REQUEST_PROMOTED_ONGOING。
        // v3.0：无条件写入（回滚 v2.8 的"仅充电"）——配合恒定 ongoing，未充电时同样
        // 具备提升资格，上岛后胶囊图标位渲染 smallIcon（温度）。
        if (Build.VERSION.SDK_INT >= 36) {
            builder.getExtras().putBoolean("android.requestPromotedOngoing", true);
        }
        return builder.build();
    }
}
