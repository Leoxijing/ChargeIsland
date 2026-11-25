package com.chargeisland.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/**
 * 安装会话结果回调：Android 12+ 提交安装会话后需处理 STATUS_PENDING_USER_ACTION
 * 并拉起系统确认页，否则安装确认窗口不会弹出。
 */
public class InstallResultReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(confirm);
                }
                break;
            }
            case PackageInstaller.STATUS_SUCCESS:
                android.widget.Toast.makeText(context, "更新安装成功",
                        android.widget.Toast.LENGTH_SHORT).show();
                break;
            case PackageInstaller.STATUS_FAILURE:
            case PackageInstaller.STATUS_FAILURE_ABORTED:
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
            case PackageInstaller.STATUS_FAILURE_INVALID:
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
            case PackageInstaller.STATUS_FAILURE_STORAGE:
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
            default:
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                android.widget.Toast.makeText(context,
                        "安装未完成：" + (msg != null ? msg : "状态 " + status),
                        android.widget.Toast.LENGTH_LONG).show();
                break;
        }
    }
}
