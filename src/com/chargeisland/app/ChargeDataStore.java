package com.chargeisland.app;

import java.util.ArrayList;

/** 进程内共享的电量采样数据：前台服务写入，主界面读取绘制曲线。 */
public final class ChargeDataStore {
    public static volatile boolean serviceRunning = false;
    public static volatile int lastPct = -1;
    public static volatile boolean lastCharging = false;

    // 实时动态（Promoted/Live Updates）状态：服务写入，主界面展示
    public static volatile boolean promotedApiAvailable = false; // 系统支持（Android 16+）
    public static volatile boolean canPostPromoted = false;      // 用户设置允许发送
    public static volatile boolean promotable = false;           // 当前通知具备提升资格
    public static volatile boolean promotedActive = false;       // 通知当前已被系统提升

    /** 每个元素为 {timestampMs, batteryPercent}。 */
    public static final ArrayList<long[]> points = new ArrayList<>();

    private ChargeDataStore() {}

    public static synchronized void addPoint(long timeMs, int pct) {
        if (!points.isEmpty()) {
            long[] last = points.get(points.size() - 1);
            if (last[1] == pct) {
                last[0] = timeMs; // 同百分比只保留最新时间点
                return;
            }
        }
        points.add(new long[]{timeMs, pct});
        // 只保留最近 2 小时样本
        long cutoff = timeMs - 2L * 60 * 60 * 1000;
        while (!points.isEmpty() && points.get(0)[0] < cutoff) {
            points.remove(0);
        }
    }

    public static synchronized void clear() {
        points.clear();
    }

    public static synchronized ArrayList<long[]> snapshot() {
        return new ArrayList<>(points);
    }

    /** 基于最近样本估算每分钟电量增速（%/min），无有效数据返回 0。 */
    public static synchronized double recentRatePerMin(long nowMs) {
        if (points.size() < 2) return 0;
        long windowStart = nowMs - 30L * 60 * 1000;
        long[] first = null, last = null;
        for (long[] p : points) {
            if (p[0] < windowStart) continue;
            if (first == null) first = p;
            last = p;
        }
        if (first == null || last == null || last == first) return 0;
        double minutes = (last[0] - first[0]) / 60000.0;
        if (minutes < 0.5) return 0;
        double delta = last[1] - first[1];
        if (delta <= 0) return 0;
        return delta / minutes;
    }
}
