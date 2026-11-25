package com.chargeisland.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 更新下载进度与结果的进程内共享状态（服务 → 主界面），机制对齐 VideoSplitter */
final class UpdateState {
    static volatile boolean running = false;
    static volatile java.util.function.Consumer<Integer> onProgress;
    static volatile java.util.function.Consumer<File> onDone;
    static volatile java.util.function.Consumer<String> onFail;

    private UpdateState() {}
}

/**
 * 更新包后台下载服务：前台通知实时显示进度，切后台/锁屏继续下载；
 * 完成后发「点击安装」通知，APP 在前台时通过 onDone 直接拉起安装窗口。
 * 通知在 API 36+ 同步请求 Promoted（实时动态）资格，与主通知一致。
 */
public class UpdateService extends Service {

    private static final int NOTI_ID = 2001;
    private static final String CHANNEL_ID = "update";
    /** 两次通知刷新的最小间隔：过密会被系统限流 */
    private static final long NOTIFY_MIN_INTERVAL = 300L;

    private volatile boolean downloading = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String url = intent != null ? intent.getStringExtra("url") : null;
        if (url == null || url.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        final int code = intent.getIntExtra("code", 0);
        final String name = intent.getStringExtra("name") != null
                ? intent.getStringExtra("name") : String.valueOf(code);
        if (downloading) return START_NOT_STICKY;   // 防重复启动
        downloading = true;
        UpdateState.running = true;

        final NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "应用更新",
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);

        final PendingIntent openApp = PendingIntent.getActivity(this, 2001,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // 首帧就显示进度（不确定态）
        startForeground(NOTI_ID, progressNotification(openApp, 0, name, true));

        new Thread(new Runnable() {
            @Override
            public void run() {
                String failMsg = null;
                File apk = null;
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(60000);
                    int total = conn.getContentLength();
                    File dst = UpdateService.this.updateApk();
                    long copied = 0;
                    int lastPct = -1;
                    long lastNotifyAt = 0;
                    try (InputStream in = conn.getInputStream();
                         FileOutputStream out = new FileOutputStream(dst)) {
                        byte[] buf = new byte[512 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            copied += n;
                            if (total > 0) {
                                int pct = (int) (copied * 100 / total);
                                long now = System.currentTimeMillis();
                                // 双重节流：百分比变化 + 最小时间间隔；100% 必刷
                                if (pct != lastPct
                                        && (now - lastNotifyAt >= NOTIFY_MIN_INTERVAL || pct >= 100)) {
                                    lastPct = pct;
                                    lastNotifyAt = now;
                                    nm.notify(NOTI_ID, progressNotification(openApp, pct, name, false));
                                    java.util.function.Consumer<Integer> cb = UpdateState.onProgress;
                                    if (cb != null) cb.accept(pct);
                                }
                            }
                        }
                    }
                    if (total > 0 && copied != total) {
                        throw new java.io.IOException("下载不完整（" + copied + "/" + total + "）");
                    }
                    conn.disconnect();
                    if (!UpdateChecker.validApk(UpdateService.this, dst, code)) {
                        throw new java.io.IOException("安装包校验失败");
                    }
                    try (FileOutputStream m = new FileOutputStream(
                            new File(UpdateService.this.updateDir(), "update_meta.txt"))) {
                        m.write(String.valueOf(code).getBytes("UTF-8"));
                    } catch (Exception ignored) {
                    }
                    apk = dst;
                } catch (Exception e) {
                    failMsg = e.getMessage() != null ? e.getMessage() : "未知错误";
                }

                if (failMsg == null && apk != null) {
                    // 完成通知：点击回 APP 并由 APP 发起系统安装会话
                    Intent install = new Intent(UpdateService.this, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            .putExtra("install_update", true);
                    PendingIntent installPi = PendingIntent.getActivity(UpdateService.this, 2002,
                            install, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    Notification.Builder b = new Notification.Builder(UpdateService.this, CHANNEL_ID)
                            .setContentTitle("更新包已就绪")
                            .setContentText("点击安装 " + name)
                            .setSmallIcon(android.R.drawable.stat_sys_download_done)
                            .setContentIntent(installPi)
                            .setAutoCancel(true);
                    if (Build.VERSION.SDK_INT >= 36) {
                        b.getExtras().putBoolean("android.requestPromotedOngoing", true);
                    }
                    nm.notify(NOTI_ID, b.build());
                    java.util.function.Consumer<File> cb = UpdateState.onDone;
                    if (cb != null) cb.accept(apk);
                } else {
                    Notification.Builder b = new Notification.Builder(UpdateService.this, CHANNEL_ID)
                            .setContentTitle("更新下载失败")
                            .setContentText(failMsg)
                            .setSmallIcon(android.R.drawable.stat_notify_error)
                            .setContentIntent(openApp)
                            .setAutoCancel(true);
                    nm.notify(NOTI_ID, b.build());
                    java.util.function.Consumer<String> cb = UpdateState.onFail;
                    if (cb != null) cb.accept(failMsg != null ? failMsg : "未知错误");
                }
                UpdateState.running = false;
                downloading = false;
                stopForeground(STOP_FOREGROUND_DETACH);   // 保留完成/失败通知
                stopSelf();
            }
        }, "update-download").start();
        return START_NOT_STICKY;
    }

    private File updateDir() {
        return UpdateChecker.updateDir(this);
    }

    private File updateApk() {
        File dir = updateDir();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return UpdateChecker.updateApkFile(this);
    }

    /** 构建标准状态栏下载进度通知；进度刷新时必须重建，保证折叠态与展开态同步 */
    private Notification progressNotification(PendingIntent openApp, int pct,
                                              String name, boolean indeterminate) {
        int p = Math.max(0, Math.min(100, pct));
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(indeterminate ? "正在下载新版本" : "正在下载新版本 " + p + "%")
                .setContentText(name)
                .setSubText(indeterminate ? "下载中" : p + "%")
                .setContentIntent(openApp)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, p, indeterminate);
        // API 36+ 请求 Promoted（实时动态）：下载进度与主通知同通道展示
        if (Build.VERSION.SDK_INT >= 36) {
            b.getExtras().putBoolean("android.requestPromotedOngoing", true);
        }
        return b.build();
    }
}
