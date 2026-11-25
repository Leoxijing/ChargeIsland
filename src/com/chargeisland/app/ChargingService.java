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
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONObject;

/** 前台服务：轮询电池状态，刷新通知并携带小米超级岛（miui.focus.param）参数。 */
public class ChargingService extends android.app.Service {

    private static final int NOTIFY_ID = 1001;
    // v1.3 换新渠道：通知渠道属性创建后不可修改，新渠道用于应用锁屏/背屏公开可见性
    private static final String CHANNEL_ID = "charging_island_v2";
    private static final long POLL_INTERVAL_MS = 3000;

    private NotificationManager nm;
    private Handler handler;
    private Runnable pollRunnable;
    private long sequence = 0;
    private boolean registered = false;
    private String lastNotifyKey = "";

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
                CHANNEL_ID, "充电进度", NotificationManager.IMPORTANCE_DEFAULT);
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
        Notification base = buildNotification("正在获取充电状态…", "请稍候", false);
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

        String statusText;
        if (!charging) statusText = "未充电";
        else if (full) statusText = "充电完成";
        else statusText = "充电中";

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

        String powerText = watts >= 0 ? String.format("%.1f W", watts) : "—";
        String tempText = String.format("%.1f ℃", tempDeci / 10f);

        // 通知正文
        StringBuilder detail = new StringBuilder();
        if (charging) {
            detail.append(powerText);
            if (watts >= 0) detail.append(" · ");
            detail.append(etaText);
        } else {
            detail.append(plugLabel);
        }
        detail.append(" · 电池 ").append(tempText);

        // 超级岛摘要态/展开态文案
        String islandFront = charging ? (full ? "已充满" : plugLabel) : "未充电";
        String islandContent = charging
                ? (full ? "100%" : (watts >= 0 ? powerText + " · " + etaText : etaText))
                : "剩余 " + pct + "%";

        // 展示内容无变化时不重复刷新通知（但提升状态仍需跟踪）
        String notifyKey = pct + "|" + statusText + "|" + detail;
        if (notifyKey.equals(lastNotifyKey)) {
            updatePromotedState(null);
            return;
        }
        lastNotifyKey = notifyKey;

        String islandJson = buildIslandJson(pct, statusText, islandFront, islandContent, detail.toString());

        Notification notification = buildNotification(statusText + " " + pct + "%", detail.toString(), charging);
        notification.extras.putString("miui.focus.param", islandJson);
        Bundle pics = new Bundle();
        pics.putParcelable("miui.focus.pic_imageText",
                Icon.createWithResource(this, R.drawable.ic_stat_charge));
        notification.extras.putBundle("miui.focus.pics", pics);

        nm.notify(NOTIFY_ID, notification);
        updatePromotedState(notification);
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

    private Notification buildNotification(String title, String text, boolean ongoing) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_charge)
                .setContentTitle(title)
                .setContentText(text)
                // 锁屏/息屏/背屏公开展示完整内容（配合新通知渠道的 VISIBILITY_PUBLIC）
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                // 实时动态（Live Updates）资格要求：BigTextStyle + contentTitle + ongoing
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setShowWhen(true);
        // Android 16 Promoted Notifications：请求提升为实时动态；低版本系统无此方法时静默跳过
        if (ongoing) {
            try {
                java.lang.reflect.Method m =
                        Notification.Builder.class.getMethod("setRequestPromotedOngoing", boolean.class);
                m.invoke(builder, true);
            } catch (Exception ignored) {
            }
        }
        return builder.build();
    }

    /** 构建小米超级岛参数（miui.focus.param），字段结构依据小米超级岛开发指南/EMAS 接入示例。 */
    private String buildIslandJson(int pct, String statusText, String frontTitle,
                                   String islandContent, String detailText) {
        try {
            sequence++;
            JSONObject p2 = new JSONObject();
            p2.put("protocol", 1);
            p2.put("business", "charging");
            p2.put("updatable", true);
            p2.put("enableFloat", false);
            p2.put("sequence", sequence);
            p2.put("timeout", 720);                 // 焦点通知消失时间（分钟）
            p2.put("ticker", statusText + " " + pct + "%");
            p2.put("aodTitle", statusText + " " + pct + "%");

            JSONObject picInfo = new JSONObject();
            picInfo.put("type", 1);
            picInfo.put("pic", "miui.focus.pic_imageText");

            JSONObject textInfo = new JSONObject();
            textInfo.put("frontTitle", frontTitle);
            textInfo.put("title", pct + "%");
            textInfo.put("content", islandContent);
            textInfo.put("useHighLight", false);

            JSONObject left = new JSONObject();
            left.put("type", 1);
            left.put("picInfo", picInfo);
            left.put("miui.focus.paramtextInfo", textInfo);

            JSONObject big = new JSONObject();
            big.put("imageTextInfoLeft", left);
            big.put("picInfo", picInfo);

            JSONObject small = new JSONObject();
            small.put("picInfo", picInfo);

            JSONObject island = new JSONObject();
            island.put("islandProperty", 1);
            island.put("islandTimeout", 3600);      // 摘要态消失时间（秒）
            island.put("bigIslandArea", big);
            island.put("smallIslandArea", small);

            JSONObject base = new JSONObject();
            base.put("title", statusText + " " + pct + "%");
            base.put("content", detailText);
            base.put("colorTitle", "#FFC400");
            base.put("type", 2);

            p2.put("param_island", island);
            p2.put("baseInfo", base);

            JSONObject root = new JSONObject();
            root.put("param_v2", p2);
            return root.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
