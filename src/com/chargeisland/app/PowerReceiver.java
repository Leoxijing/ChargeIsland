package com.chargeisland.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 保活接收器：插电（ACTION_POWER_CONNECTED）与开机（BOOT_COMPLETED / LOCKED_BOOT_COMPLETED）
 * 时自动拉起充电监控前台服务，无需用户手动确认。
 * Android 12+ 后台启动前台服务受限时静默降级，等待下次前台机会（如用户打开 APP）。
 */
public class PowerReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_POWER_CONNECTED.equals(action)
                || Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.LOCKED_BOOT_COMPLETED".equals(action)) {
            try {
                context.startForegroundService(new Intent(context, ChargingService.class));
                Log.i("ChargeIsland", "auto-start charging service: " + action);
            } catch (Exception e) {
                Log.i("ChargeIsland", "auto-start blocked by system: " + e);
            }
        }
    }
}
