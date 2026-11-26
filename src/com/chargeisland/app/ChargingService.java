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
import android.graphics.drawable.Icon;
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
        ChargeDataStore.serviceRunning = true;
        Notification base = buildNotification("— ℃", "正在获取充电状态…", false, 0,
                Icon.createWithResource(this, R.drawable.ic_stat_charge));
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

        // 曲线采样：仅在电量百分比变化时记录
        if (pct != ChargeDataStore.lastPct || !charging && ChargeDataStore.lastCharging) {
            if (charging && !ChargeDataStore.lastCharging) {
                ChargeDataStore.clear(); // 新一轮充电，重新起线
            }
            if (charging) {
                ChargeDataStore.addPoint(System.currentTimeMillis(), pct);
            }
        }
        ChargeDataStore.lastPct = pct;
        ChargeDataStore.lastCharging = charging;

        // 功率估算：P(W) = 电压(V) × 电流(A)
        double watts = -1;
        if (charging && currentValid && voltageMv > 0) {
            watts = (voltageMv / 1000.0) * (currentUa / 1_000_000.0);
        }

        String plugLabel;
        if (plugged == BatteryManager.BATTERY_PLUGGED_AC) plugLabel = "交流快充";
        else if (plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) plugLabel = "无线充电";
        else if (plugged == BatteryManager.BATTERY_PLUGGED_USB) plugLabel = "USB 充电";
        else plugLabel = "未插入充电器";

        // 预计充满时间
        String etaText = "—";
        if (charging && !full) {
            double rate = ChargeDataStore.recentRatePerMin(System.currentTimeMillis());
            if (rate > 0.001) {
                int remain = (int) Math.ceil((100 - pct) / rate);
                if (remain > 0 && remain < 24 * 60) {
                    etaText = remain >= 60
                            ? String.format("约 %d 小时 %d 分钟充满", remain / 60, remain % 60)
                            : String.format("约 %d 分钟充满", remain);
                } else if (remain <= 0) {
                    etaText = "即将充满";
                }
            } else {
                etaText = "计算中…";
            }
        } else if (full) {
            etaText = "已充满";
        }

        String tempText = String.format("%.1f ℃", tempDeci / 10f);

        // 通知样式（v1.9，用户指定）：
        // - 顶部标题 = 电池温度
        // - 黄色闪电图标位 = 充电功率（动态文字图标；未充电/已充满/功率未知时回退闪电图标）
        // - 详情行不再重复温度（已移至顶部）
        String notifTitle = tempText;
        StringBuilder detail = new StringBuilder();
        detail.append(pct).append("%");
        if (charging && !full) {
            detail.append(" · ").append(etaText);
        } else if (full) {
            detail.append(" · 已充满");
        } else {
            detail.append(" · ").append(plugLabel);
        }

        Icon notifIcon;
        if (charging && !full && watts >= 0) {
            notifIcon = buildPowerIcon(String.format("%.1fW", watts));
        } else {
            notifIcon = Icon.createWithResource(this, R.drawable.ic_stat_charge);
        }

        // 每次轮询都刷新通知（对齐 VideoSplitter：高频 notify，不因内容未变而跳过，
        // 让系统持续重新评估 Promoted 提升资格）
        Notification notification = buildNotification(notifTitle, detail.toString(), charging, pct, notifIcon);
        nm.notify(NOTIFY_ID, notification);
        updatePromotedState(notification);
    }

    /**
     * 功率文字小图标（如 "22.5W"）：通知 smallIcon 按 alpha mask 渲染，
     * 实时动态模板的黄圆底上会显示为深色文字，替代静态闪电图标。
     */
    private Icon buildPowerIcon(String text) {
        try {
            int size = 192;
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    size, size, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
            android.graphics.Paint paint = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            paint.setColor(0xFF000000);
            paint.setTextAlign(android.graphics.Paint.Align.CENTER);
            paint.setFakeBoldText(true);
            float textSize = size * 0.40f;
            paint.setTextSize(textSize);
            float maxWidth = size * 0.90f;
            float width = paint.measureText(text);
            if (width > maxWidth) textSize = textSize * maxWidth / width;
            paint.setTextSize(textSize);
            android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
            float baseline = (size - (fm.ascent + fm.descent)) / 2f;
            canvas.drawText(text, size / 2f, baseline, paint);
            return Icon.createWithBitmap(bmp);
        } catch (Exception e) {
            return Icon.createWithResource(this, R.drawable.ic_stat_charge);
        }
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

    private Notification buildNotification(String title, String text, boolean ongoing, int pct,
                                           Icon smallIcon) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 字段对齐 VideoSplitter StatusBarProgress.build：category/ticker/onlyAlertOnce/PUBLIC 全套
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(smallIcon)
                .setContentTitle(title)
                .setContentText(text)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                // 实时动态（Live Updates）资格要求：BigTextStyle + contentTitle + ongoing
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setProgress(100, Math.max(0, Math.min(100, pct)), false)
                .setColor(0xFFFFC400)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setContentIntent(pi)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setTicker(title)
                .setShowWhen(true);
        // Android 16 Promoted Notifications：framework Builder 没有 setRequestPromotedOngoing 方法
        //（反射调用会静默失败导致提升请求从未写入），官方要求写入 extras key，字面值同
        // androidx NotificationCompat.EXTRA_REQUEST_PROMOTED_ONGOING；API 36+ 无条件写入
        //（对齐 VideoSplitter：不附加 ongoing 条件，首条前台通知即携带提升请求）。
        if (Build.VERSION.SDK_INT >= 36) {
            builder.getExtras().putBoolean("android.requestPromotedOngoing", true);
        }
        return builder.build();
    }
}
