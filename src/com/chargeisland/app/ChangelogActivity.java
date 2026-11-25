package com.chargeisland.app;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * 更新日志页（机制对齐 VideoSplitter）。
 * 数据来源分三层，按可用性依次降级：
 * 1. 本地缓存（上次联网成功拉取的全量记录）——冷启动即可显示完整历史；
 * 2. 内置 assets（随包分发的全量副本）——首次安装、无网络时兜底；
 * 3. 远端仓库 CHANGELOG.md（raw 直链 → jsDelivr CDN 回退）——进入页面后自动刷新并更新缓存。
 */
public class ChangelogActivity extends Activity {

    private static final String ASSET_FILE = "changelog.md";
    private static final String CACHE_NAME = "changelog_full.md";
    /** 远端地址：优先仓库直链（实时），不可达时回退 CDN（国内网络更易访问） */
    private static final String[] REMOTE_URLS = {
            "https://raw.githubusercontent.com/Leoxijing/ChargeIsland/main/CHANGELOG.md",
            "https://cdn.jsdelivr.net/gh/Leoxijing/ChargeIsland@main/CHANGELOG.md"
    };
    /** 版本条目标题，如 "## v1.5 (versionCode 6)" */
    private static final Pattern VERSION_RE = Pattern.compile("^## v\\d", Pattern.MULTILINE);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private LinearLayout box;
    private TextView statusText;
    private TextView refreshBtn;
    private boolean loading = false;
    private String currentMd = "";
    private boolean fromCache = false;

    private float density;

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        setContentView(R.layout.activity_changelog);

        box = findViewById(R.id.logContent);
        statusText = findViewById(R.id.tvLogStatus);
        refreshBtn = findViewById(R.id.btnLogRefresh);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!loading) fetchRemote();
            }
        });
        findViewById(R.id.btnLogBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        // 首屏：缓存（全量）优先，其次内置副本
        String cached = readCache();
        fromCache = cached != null;
        String first = cached != null ? cached : readAsset();
        render(first);
        setStatus(fromCache
                ? "本地缓存 · " + countVersions(first) + " 个版本"
                : "内置记录 · " + countVersions(first) + " 个版本");
        fetchRemote();
    }

    // ---------- 数据 ----------

    private File cacheFile() {
        File dir = new File(getExternalFilesDir(null), "cache");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, CACHE_NAME);
    }

    private String readCache() {
        try {
            File f = cacheFile();
            if (f.exists() && f.length() > 0) {
                String s = readAll(f);
                if (hasVersions(s)) return s;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void saveCache(String md) {
        try (FileOutputStream out = new FileOutputStream(cacheFile())) {
            out.write(md.getBytes("UTF-8"));
        } catch (Exception ignored) {
            // 缓存写入失败不影响本次展示
        }
    }

    private String readAsset() {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    getAssets().open(ASSET_FILE), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String readAll(File f) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(f), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        br.close();
        return sb.toString();
    }

    private boolean hasVersions(String md) {
        return md != null && VERSION_RE.matcher(md).find();
    }

    private int countVersions(String md) {
        if (md == null) return 0;
        java.util.regex.Matcher m = VERSION_RE.matcher(md);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    /** 按 raw 直链 → CDN 顺序尝试，任一源返回含版本条目的文本即成功 */
    private String tryFetch() {
        for (String u : REMOTE_URLS) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(u).openConnection();
                conn.setConnectTimeout(7000);
                conn.setReadTimeout(7000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("Accept", "text/plain");
                conn.setRequestProperty("Cache-Control", "no-cache");
                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append('\n');
                    br.close();
                    String text = sb.toString();
                    if (hasVersions(text)) return text;
                }
            } catch (Exception e) {
                // 当前源不可用，继续下一个
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return null;
    }

    private void fetchRemote() {
        if (loading) return;
        loading = true;
        setStatus("正在获取完整更新日志…", true);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final String remote = tryFetch();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        loading = false;
                        if (remote == null) {
                            setStatus("联网获取失败 · 显示" + sourceLabel()
                                    + "（" + countVersions(currentMd) + " 个版本）", false);
                        } else {
                            saveCache(remote);
                            fromCache = true;
                            render(remote);
                            setStatus("已获取最新 · 共 " + countVersions(currentMd) + " 个版本", false);
                        }
                    }
                });
            }
        });
    }

    private String sourceLabel() {
        return fromCache ? "缓存" : "内置记录";
    }

    private void setStatus(String text, boolean hideRefresh) {
        if (isFinishing() || isDestroyed()) return;
        statusText.setText(text);
        refreshBtn.setVisibility(hideRefresh ? View.GONE : View.VISIBLE);
    }

    private void setStatus(String text) {
        setStatus(text, false);
    }

    // ---------- 渲染 ----------

    private void render(String md) {
        if (isFinishing() || isDestroyed()) return;
        currentMd = md != null ? md : "";
        box.removeAllViews();
        if (!hasVersions(currentMd)) {
            TextView empty = new TextView(this);
            empty.setText("暂无可显示的更新日志\n可点击刷新重试");
            empty.setTextSize(14);
            empty.setTextColor(0xFF8B949E);
            empty.setGravity(android.view.Gravity.CENTER);
            empty.setPadding(0, dp(48), 0, 0);
            box.addView(empty);
            return;
        }
        for (String raw : currentMd.split("\n", -1)) {
            String t = raw.trim();
            if (t.isEmpty() || t.equals("---")) continue;
            TextView v = new TextView(this);
            if (t.startsWith("## ")) {
                v.setText(bold(t.substring(3)));
                v.setTextSize(16);
                v.setTextColor(0xFF58A6FF);
                v.setPadding(0, dp(14), 0, dp(5));
            } else if (t.startsWith("# ")) {
                v.setText(bold(t.substring(2)));
                v.setTextSize(20);
                v.setTextColor(0xFFE6EDF3);
                v.setPadding(0, 0, 0, dp(6));
            } else if (t.startsWith("- ")) {
                v.setText(bold("· " + t.substring(2)));
                v.setTextSize(13);
                v.setTextColor(0xFFC9D1D9);
                v.setPadding(dp(10), dp(2), 0, dp(2));
            } else if (t.startsWith("> ")) {
                v.setText(bold(t.substring(2)));
                v.setTextSize(12);
                v.setTextColor(0xFF8B949E);
                v.setPadding(0, dp(5), 0, dp(5));
            } else {
                v.setText(bold(t));
                v.setTextSize(13);
                v.setTextColor(0xFFADBAC7);
                v.setPadding(0, dp(2), 0, dp(2));
            }
            box.addView(v);
        }
    }

    /** 行内 **粗体** 转为加粗文本，其余字符原样保留 */
    private CharSequence bold(String s) {
        if (s == null || !s.contains("**")) return s;
        SpannableStringBuilder out = new SpannableStringBuilder();
        int i = 0;
        while (i < s.length()) {
            int start = s.indexOf("**", i);
            if (start < 0) {
                out.append(s.substring(i));
                break;
            }
            int end = s.indexOf("**", start + 2);
            if (end < 0) {
                out.append(s.substring(i));
                break;
            }
            out.append(s.substring(i, start));
            String seg = s.substring(start + 2, end);
            if (!seg.isEmpty()) {
                SpannableString ss = new SpannableString(seg);
                ss.setSpan(new StyleSpan(Typeface.BOLD), 0, seg.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append(ss);
            }
            i = end + 2;
        }
        return out;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }
}
