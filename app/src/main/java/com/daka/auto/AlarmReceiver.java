package com.daka.auto;

import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;

import java.util.Calendar;

public class AlarmReceiver extends BroadcastReceiver {

    private static KeyguardManager.KeyguardLock sKeyguardLock;

    /** 根据面板设定（重新）安排每天两个时间的闹钟 */
    public static void scheduleAll(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("daka", Context.MODE_PRIVATE);
        boolean enabled = p.getBoolean("enabled", false);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        cancelAll(ctx);
        if (!enabled || am == null) return;

        String[] keys = {"morning", "evening"};
        for (int i = 0; i < keys.length; i++) {
            String t = p.getString(keys[i], i == 0 ? "08:00:00" : "17:00:00");
            String[] parts = t.split(":");
            int hour = Integer.parseInt(parts[0].trim());
            int minute = Integer.parseInt(parts[1].trim());
            int second = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 0;
            long at = nextTrigger(hour, minute, second);
            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at,
                        pending(ctx, i, true));
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, pending(ctx, i, true));
            }
        }
    }

    public static void cancelAll(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(pending(ctx, 0, true));
        am.cancel(pending(ctx, 1, true));
    }

    private static PendingIntent pending(Context ctx, int which, boolean setFlags) {
        Intent i = new Intent(ctx, AlarmReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, which, i, flags);
    }

    private static long nextTrigger(int hour, int minute, int second) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, second);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return c.getTimeInMillis();
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        // 触发后立刻重新安排明天的同一时间
        scheduleAll(ctx);

        // 1. 唤醒屏幕
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    "daka:alarm");
            wl.acquire(20000);
        }

        // 尝试解除非安全（无密码）锁屏；有密码锁时需要用户解锁后自动化才能继续
        try {
            KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && km.inKeyguardRestrictedInputMode()) {
                if (sKeyguardLock == null) {
                    sKeyguardLock = km.newKeyguardLock("daka");
                }
                sKeyguardLock.disableKeyguard();
            }
        } catch (Throwable ignored) {
        }

        // 交给无障碍服务执行完整流程
        Intent svc = new Intent(ctx, DakaAccessibilityService.class);
        svc.setAction(DakaAccessibilityService.ACTION_RUN);
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(svc);
        } else {
            ctx.startService(svc);
        }
    }

    /** 任务结束后由服务调用，恢复锁屏 */
    public static void reenableKeyguard() {
        try {
            if (sKeyguardLock != null) {
                sKeyguardLock.reenableKeyguard();
                sKeyguardLock = null;
            }
        } catch (Throwable ignored) {
        }
    }
}
