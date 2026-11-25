package com.chargeisland.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 应用内更新（机制对齐 VideoSplitter）：
 * - 检查源为 GitHub Release 上的 version.json 固定下载地址（每次发版同步上传）
 * - 启动静默自动检查 + 手动检查；发现新版弹窗展示更新日志摘要
 * - 前台服务后台下载（UpdateService）→ 体积/版本/可解析三重校验 → 缓存复用
 * - PackageInstaller 官方安装会话（不经过「打开方式」选择器）
 * 仅用系统自带 HttpURLConnection + org.json，无第三方依赖。
 */
public final class UpdateChecker {

    /** 更新源：GitHub Release 上的 version.json（每次发版同步更新） */
    static final String UPDATE_URL =
            "https://github.com/Leoxijing/ChargeIsland/releases/latest/download/version.json";

    private static volatile boolean checking = false;
    /** 安装授权跳设置后暂存的待装包，返回 APP 时自动继续 */
    static volatile File pendingInstall = null;

    private UpdateChecker() {}

    public static void check(final Activity ctx, final boolean manual) {
        if (checking) return;
        checking = true;
        if (manual) {
            new AlertDialog.Builder(ctx)
                    .setTitle("检查更新")
                    .setMessage("正在获取最新版本信息…")
                    .setNegativeButton("取消", (d, w) -> checking = false)
                    .setCancelable(false)
                    .show();
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                int latestCode = -1;
                String latestName = null, url = null, notes = null, error = null;
                try {
                    URL u = new URL(UPDATE_URL);
                    HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    conn.setRequestProperty("Cache-Control", "no-cache");
                    int code = conn.getResponseCode();
                    if (code == 200) {
                        BufferedReader br = new BufferedReader(
                                new InputStreamReader(conn.getInputStream(), "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                        br.close();
                        JSONObject obj = new JSONObject(sb.toString());
                        latestCode = obj.getInt("versionCode");
                        latestName = obj.optString("versionName", String.valueOf(latestCode));
                        url = obj.optString("url", "");
                        notes = obj.optString("notes", "");
                    } else {
                        error = "HTTP " + code;
                    }
                    conn.disconnect();
                } catch (Exception e) {
                    error = e.getMessage() != null ? e.getMessage() : "网络异常";
                }

                final int fCode = latestCode;
                final String fName = latestName;
                final String fUrl = url;
                final String fNotes = notes;
                final String fError = error;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        if (!checking) return;   // 用户已取消，丢弃结果
                        checking = false;
                        dismissManualDialog(ctx);
                        showResult(ctx, manual, fCode, fName, fUrl, fNotes, fError);
                    }
                });
            }
        }, "update-check").start();
    }

    private static void dismissManualDialog(Context ctx) {
        // 手动检查的等待窗由 showResult 的结果窗替换；此处仅兜底防泄漏
        try {
            if (ctx instanceof Activity && ((Activity) ctx).isFinishing()) return;
        } catch (Exception ignored) {
        }
    }

    private static void showResult(final Activity ctx, boolean manual,
                                   int latestCode, String latestName, String url,
                                   String notes, String error) {
        long current = getCurrentCode(ctx);
        if (error != null || latestCode < 0) {
            if (manual) {
                new AlertDialog.Builder(ctx)
                        .setTitle("检查更新失败")
                        .setMessage("无法获取版本信息：" + (error != null ? error : "数据异常")
                                + "\n请检查网络后重试。")
                        .setPositiveButton("知道了", null)
                        .show();
            }
            return;
        }
        if (latestCode > current && url != null && !url.isEmpty()) {
            final String fUrl = url;
            final String fName = latestName != null ? latestName : String.valueOf(latestCode);
            final int fCode = latestCode;
            final String fNotes = notes != null ? notes : "";
            if (isUpdateCached(ctx, fCode)) {
                new AlertDialog.Builder(ctx)
                        .setTitle("新版本 " + fName + " 已下载")
                        .setMessage("安装包已就绪，可直接安装。\n\n" + fNotes)
                        .setPositiveButton("安装", (d, w) ->
                                installApk(ctx, updateApkFile(ctx)))
                        .setNegativeButton("稍后", null)
                        .show();
            } else {
                new AlertDialog.Builder(ctx)
                        .setTitle("发现新版本 " + fName)
                        .setMessage("当前版本：" + getVersionName(ctx)
                                + "\n最新版本：" + fName + "\n\n" + fNotes)
                        .setPositiveButton("下载更新", (d, w) ->
                                downloadUpdate(ctx, fUrl, fName, fCode))
                        .setNegativeButton("稍后", null)
                        .show();
            }
        } else if (manual) {
            new AlertDialog.Builder(ctx)
                    .setTitle("已是最新版本")
                    .setMessage("当前已是最新版本（" + getVersionName(ctx) + "）。")
                    .setPositiveButton("知道了", null)
                    .show();
        }
    }

    /** 启动前台服务后台下载，通知栏实时进度；完成后前台直接拉起安装 */
    static void downloadUpdate(Activity ctx, String url, String name, int code) {
        if (UpdateState.running) return;
        UpdateState.onDone = apk -> ctx.runOnUiThread(() -> installApk(ctx, apk));
        UpdateState.onFail = msg -> ctx.runOnUiThread(() ->
                android.widget.Toast.makeText(ctx, "更新下载失败：" + msg,
                        android.widget.Toast.LENGTH_LONG).show());
        ctx.startForegroundService(new Intent(ctx, UpdateService.class)
                .putExtra("url", url)
                .putExtra("code", code)
                .putExtra("name", name));
    }

    // ---------- 缓存与校验 ----------

    static File updateDir(Context ctx) {
        return new File(ctx.getExternalFilesDir(null), "update");
    }

    static File updateApkFile(Context ctx) {
        return new File(updateDir(ctx), "update.apk");
    }

    /** 本地是否已缓存该版本的完整安装包（下载/安装失败后直接复用，不重新下载） */
    static boolean isUpdateCached(Context ctx, int latestCode) {
        try {
            File apk = updateApkFile(ctx);
            File meta = new File(updateDir(ctx), "update_meta.txt");
            if (!apk.exists() || !meta.exists()) return false;
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(meta), "UTF-8"));
            String s = br.readLine();
            br.close();
            int cached = Integer.parseInt(s != null ? s.trim() : "");
            return cached == latestCode && validApk(ctx, apk, latestCode);
        } catch (Exception e) {
            return false;
        }
    }

    /** 校验 APK：体积 + 版本号匹配 + 包管理器可解析（防下载截断） */
    static boolean validApk(Context ctx, File apk, int expectCode) {
        try {
            if (apk.length() < 1024 * 1024) return false;
            android.content.pm.PackageInfo info =
                    ctx.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            return info != null && info.versionCode == expectCode;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- 安装 ----------

    /** 安装授权跳设置返回后自动继续 */
    static void resumePendingInstall(Activity ctx) {
        File apk = pendingInstall;
        if (apk == null) return;
        if (ctx.getPackageManager().canRequestPackageInstalls()) {
            pendingInstall = null;
            installApk(ctx, apk);
        }
    }

    static void installApk(final Activity ctx, final File apk) {
        if (!ctx.getPackageManager().canRequestPackageInstalls()) {
            pendingInstall = apk;
            new AlertDialog.Builder(ctx)
                    .setTitle("需要授权")
                    .setMessage("安装新版本需要允许本应用安装未知应用（一次性）。\n去设置开启后返回，会自动继续安装。")
                    .setPositiveButton("去设置", (d, w) -> {
                        try {
                            ctx.startActivity(new Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + ctx.getPackageName())));
                        } catch (Exception ignored) {
                        }
                    })
                    .setNegativeButton("取消", (d, w) -> pendingInstall = null)
                    .show();
            return;
        }
        // 首选：官方安装会话（系统安装确认页，不依赖「打开方式」选择器）
        try {
            installViaSession(ctx, apk);
            return;
        } catch (Exception e) {
            // 会话失败时无 FileProvider 兜底，直接提示手动安装路径
        }
        new AlertDialog.Builder(ctx)
                .setTitle("无法自动安装")
                .setMessage("请手动安装已下载的安装包：\n" + apk.getAbsolutePath()
                        + "\n\n（文件管理器中打开该路径即可，无需重新下载）")
                .setPositiveButton("知道了", null)
                .show();
    }

    private static void installViaSession(Context ctx, File apk) throws Exception {
        PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        try (PackageInstaller.Session s = session;
             FileInputStream in = new FileInputStream(apk)) {
            try (java.io.OutputStream out = s.openWrite("update", 0, apk.length())) {
                byte[] buf = new byte[512 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                s.fsync(out);
            }
            Intent done = new Intent(ctx, InstallResultReceiver.class)
                    .setAction("com.chargeisland.app.INSTALL_COMPLETE");
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                    ctx, sessionId, done,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_MUTABLE);
            s.commit(pi.getIntentSender());
        }
    }

    static String getVersionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    static long getCurrentCode(Context ctx) {
        try {
            android.content.pm.PackageInfo pi =
                    ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            if (android.os.Build.VERSION.SDK_INT >= 28) return pi.getLongVersionCode();
            return pi.versionCode;
        } catch (Exception e) {
            return -1;
        }
    }
}
