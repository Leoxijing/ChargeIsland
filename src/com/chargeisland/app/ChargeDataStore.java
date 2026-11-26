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

    // v4.0：未充电时是否保持常驻通知（默认开；关=未充电时服务自动退出，插电自动恢复）
    public static volatile boolean keepIdle = true;

    /** 充电采样点：每个元素为 {timestampMs, batteryPercent}，用于充电曲线与充满预估。 */
    public static final ArrayList<long[]> points = new ArrayList<>();
    /** 放电采样点：仅用于耗尽预估，不进充电曲线。 */
    public static final ArrayList<long[]> dischargePoints = new ArrayList<>();

    private ChargeDataStore() {}

    /** v4.0：读取用户设置（服务与主界面启动时各调一次）。 */
    public static void loadSettings(android.content.Context c) {
        keepIdle = c.getSharedPreferences("ci_settings", android.content.Context.MODE_PRIVATE)
                .getBoolean("keep_idle_notification", true);
    }

    /** v4.0：保存"未充电时保持常驻"开关。 */
    public static void saveKeepIdle(android.content.Context c, boolean v) {
        keepIdle = v;
        c.getSharedPreferences("ci_settings", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("keep_idle_notification", v).apply();
    }

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

    public static synchronized void addDischargePoint(long timeMs, int pct) {
        if (!dischargePoints.isEmpty()) {
            long[] last = dischargePoints.get(dischargePoints.size() - 1);
            if (last[1] == pct) {
                last[0] = timeMs;
                return;
            }
        }
        dischargePoints.add(new long[]{timeMs, pct});
        long cutoff = timeMs - 2L * 60 * 60 * 1000;
        while (!dischargePoints.isEmpty() && dischargePoints.get(0)[0] < cutoff) {
            dischargePoints.remove(0);
        }
    }

    public static synchronized void clear() {
        points.clear();
    }

    public static synchronized void clearDischarge() {
        dischargePoints.clear();
    }

    public static synchronized ArrayList<long[]> snapshot() {
        return new ArrayList<>(points);
    }

    public static synchronized ArrayList<long[]> dischargeSnapshot() {
        return new ArrayList<>(dischargePoints);
    }

    /**
     * 速率估算结果：对窗口内采样点做最小二乘线性回归得到。
     */
    public static final class RateEstimate {
        /** 斜率（%/min）：充电为正、放电为负。 */
        public final double slopePerMin;
        /** 拟合优度 R²（0..1），越接近 1 说明速率越稳定。 */
        public final double r2;
        public final int samples;
        /** 参与回归的样本时间跨度（分钟，向下取整）。 */
        public final long spanMinutes;

        RateEstimate(double slopePerMin, double r2, int samples, long spanMinutes) {
            this.slopePerMin = slopePerMin;
            this.r2 = r2;
            this.samples = samples;
            this.spanMinutes = spanMinutes;
        }

        /** 估算是否可用于预估：斜率显著、样本与跨度足够。 */
        public boolean usable() {
            return samples >= 2 && spanMinutes >= 1 && Math.abs(slopePerMin) >= 0.0005;
        }

        /** 准确度分级：拟合优度 + 样本量 + 时间跨度。 */
        public String accuracyLabel() {
            if (samples >= 5 && spanMinutes >= 10 && r2 >= 0.85) return "高";
            if (samples >= 3 && spanMinutes >= 5 && r2 >= 0.60) return "中";
            return "低";
        }
    }

    /**
     * 对窗口内采样点做最小二乘线性回归，估算电量变化速率。
     * 相比首尾两点差值，回归利用全部样本、对单点抖动不敏感，并给出 R² 供准确度评估。
     */
    public static synchronized RateEstimate estimateRate(ArrayList<long[]> src, long nowMs, long windowMs) {
        long windowStart = nowMs - windowMs;
        int n = 0;
        long t0 = 0, t1 = 0;
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;
        for (long[] p : src) {
            if (p[0] < windowStart || p[0] > nowMs) continue;
            double x = (p[0] - windowStart) / 60000.0; // 分钟
            double y = p[1];
            if (n == 0) t0 = p[0];
            t1 = p[0];
            n++;
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
            sumY2 += y * y;
        }
        if (n < 2) return new RateEstimate(0, 0, n, 0);
        long spanMinutes = (t1 - t0) / 60000;

        double denX = n * sumX2 - sumX * sumX;
        double denY = n * sumY2 - sumY * sumY;
        double num = n * sumXY - sumX * sumY;
        double slope = denX != 0 ? num / denX : 0;
        double r2 = (denX != 0 && denY != 0) ? (num * num) / (denX * denY) : 0;
        return new RateEstimate(slope, Math.max(0, Math.min(1, r2)), n, spanMinutes);
    }

    /** 充电速率（30 分钟窗口，贴近当前充电阶段）。 */
    public static RateEstimate chargeRate(long nowMs) {
        return estimateRate(points, nowMs, 30L * 60 * 1000);
    }

    /** 放电速率（60 分钟窗口：放电更慢，需要更长窗口保证样本量）。 */
    public static RateEstimate dischargeRate(long nowMs) {
        return estimateRate(dischargePoints, nowMs, 60L * 60 * 1000);
    }
}
