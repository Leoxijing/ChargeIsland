package com.chargeisland.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 通过 GitHub Releases API 检查新版本（仅用 Android 系统自带的 HttpURLConnection + org.json，
 * 无第三方依赖；引入第三方库时须同步更新仓库 README 署名区域）。
 */
public final class UpdateChecker {

    private static final String REPO = "Leoxijing/ChargeIsland";
    private static final String API_URL =
            "https://api.github.com/repos/" + REPO + "/releases/latest";

    private UpdateChecker() {}

    public static void check(final Context context, final String localVersion) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String latest = null, htmlUrl = null, apkUrl = null, error = null;
                try {
                    URL url = new URL(API_URL);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    conn.setRequestProperty("Accept", "application/vnd.github+json");
                    conn.setRequestProperty("User-Agent", "ChargeIsland-Updater");
                    int code = conn.getResponseCode();
                    if (code == 200) {
                        BufferedReader br = new BufferedReader(
                                new InputStreamReader(conn.getInputStream(), "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) {
                            sb.append(line);
                        }
                        br.close();
                        JSONObject obj = new JSONObject(sb.toString());
                        latest = obj.optString("tag_name", "").replaceFirst("^[vV]", "");
                        htmlUrl = obj.optString("html_url", "");
                        JSONArray assets = obj.optJSONArray("assets");
                        if (assets != null) {
                            for (int i = 0; i < assets.length(); i++) {
                                JSONObject a = assets.getJSONObject(i);
                                if (a.optString("name", "").endsWith(".apk")) {
                                    apkUrl = a.optString("browser_download_url", "");
                                    break;
                                }
                            }
                        }
                    } else {
                        error = "HTTP " + code;
                    }
                    conn.disconnect();
                } catch (Exception e) {
                    error = e.getMessage() != null ? e.getMessage() : "网络异常";
                }

                final String fLatest = latest;
                final String fHtml = htmlUrl;
                final String fApk = apkUrl;
                final String fError = error;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        showResult(context, localVersion, fLatest, fHtml, fApk, fError);
                    }
                });
            }
        }, "update-check").start();
    }

    private static void showResult(Context ctx, String local, String latest,
                                   String html, String apk, String error) {
        if (error != null) {
            new AlertDialog.Builder(ctx)
                    .setTitle("检查更新失败")
                    .setMessage("无法连接 GitHub：" + error + "\n请检查网络后重试。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        if (latest == null || latest.isEmpty()) {
            new AlertDialog.Builder(ctx)
                    .setTitle("检查更新")
                    .setMessage("未获取到版本信息，请稍后重试。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        if (compareVersion(latest, local) > 0) {
            final String target = (apk != null && !apk.isEmpty()) ? apk : html;
            new AlertDialog.Builder(ctx)
                    .setTitle("发现新版本 v" + latest)
                    .setMessage("当前版本 v" + local + " → 最新版本 v" + latest
                            + "\n\n点击「前往下载」打开发布页获取新版本 APK。")
                    .setPositiveButton("前往下载", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            try {
                                ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)));
                            } catch (Exception ignored) {
                            }
                        }
                    })
                    .setNegativeButton("稍后", null)
                    .show();
        } else {
            new AlertDialog.Builder(ctx)
                    .setTitle("检查更新")
                    .setMessage("当前已是最新版本（v" + local + "）。")
                    .setPositiveButton("知道了", null)
                    .show();
        }
    }

    /** 语义化版本比较：a 比 b 新返回正数。 */
    static int compareVersion(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? parsePart(pa[i]) : 0;
            int vb = i < pb.length ? parsePart(pb[i]) : 0;
            if (va != vb) {
                return va - vb;
            }
        }
        return 0;
    }

    private static int parsePart(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
