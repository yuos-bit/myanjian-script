package com.daka.auto;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.Executor;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class DakaAccessibilityService extends AccessibilityService {

    public static final String ACTION_RUN = "com.daka.auto.ACTION_RUN";
    private static final String CHANNEL_ID = "daka_run";
    private static final String NOTIF_TEXT = "自动打卡任务执行中…";

    private static volatile DakaAccessibilityService sInstance;
    private static final StringBuilder sLog = new StringBuilder();

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private volatile boolean mRunning = false;
    private TextView mOverlay;
    private String mOverlayText;
    private PowerManager.WakeLock mWakeLock;

    public static boolean isConnected() {
        return sInstance != null;
    }

    public static String getLog() {
        synchronized (sLog) {
            return sLog.toString();
        }
    }

    /** 供其他组件（如闹钟接收器）写入运行日志 */
    public static void appendLog(String msg) {
        log(msg);
    }

    private static void log(String msg) {
        String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date()) + "  " + msg + "\n";
        synchronized (sLog) {
            sLog.append(line);
            if (sLog.length() > 8000) {
                sLog.delete(0, 4000);
            }
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        log("无障碍服务已连接");
    }

    @Override
    public void onDestroy() {
        if (sInstance == this) sInstance = null;
        OcrHelper.release();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    private volatile boolean mScheduledRun = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_RUN.equals(intent.getAction())) {
            startForeground(1, buildNotification());
            mScheduledRun = intent.getBooleanExtra("scheduled", false);
            if (mRunning) {
                log("已有任务在运行，忽略本次触发");
            } else {
                mRunning = true;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        runTask();
                    }
                }, "daka-task").start();
            }
        }
        return START_NOT_STICKY;
    }

    // ---------------- 任务主流程 ----------------

    private void runTask() {
        try {
            // 仅工作日打卡（定时触发时）：联网复核今日是否为法定节假日/周末
            // （闹钟接收器已用本地缓存预判过，这里兜底处理缓存缺失或过期的情况）
            if (mScheduledRun && getSharedPreferences("daka", MODE_PRIVATE)
                    .getBoolean("workday_only", true)) {
                String label = HolidayHelper.todayLabel(this, true);
                if (!HolidayHelper.isWorkdayToday(this)) {
                    log("今天是" + label + "，仅工作日打卡已启用，本次任务跳过");
                    showToast("今天是" + label + "，已跳过打卡");
                    return;
                }
                log("今天是" + label + "，工作日打卡正常执行");
            }

            // 0. 读取屏幕分辨率
            int[] res = getResolution();
            log("屏幕分辨率: " + res[0] + "x" + res[1]);

            acquireWake();

            // 1. 唤醒屏幕，等待1S
            ensureAwake();
            waitSeconds(1, "唤醒屏幕");

            // 2. 仅在锁屏状态下上滑解锁（未锁屏时上滑会误触"回到桌面"手势，把页面滑没）
            if (isKeyguardLocked()) {
                swipeUp(res);
                waitSeconds(6, "已上滑解锁，等待页面加载");
            } else {
                log("屏幕未锁定，跳过上滑");
                waitSeconds(2, "等待页面就绪");
            }

            // 3. 读取屏幕文字，判断是否已在签到页面（文字含"签到" 或 窗口标题含小程序名）
            String miniName = getSharedPreferences("daka", MODE_PRIVATE)
                    .getString("mini_name", "虾米签");
            String page1 = readScreenWithRetry();
            boolean onPage = onSignInPage(page1, miniName);
            if (!onPage) {
                // 无障碍读不到内容时，用 OCR 兜底识别（安卓11+）
                String ocr = ocrScreenText();
                if (ocr.contains("刷新签到") || ocr.contains("开始签到")
                        || ocr.contains("签到未开始")) {
                    onPage = true;
                    log("OCR 识别到签到页内容，判定已在签到页面");
                    page1 = page1 + " " + ocr;
                }
            }
            boolean restored = false;
            if (onPage) {
                log("判定: 已在签到页面，跳过最近任务，直接继续");
            } else {
                log("判定: 不在签到页面 (文字含签到=" + page1.contains("签到")
                        + ", 窗口标题匹配=" + windowTitleMatches(miniName) + ")");
                if (miniName != null && !miniName.trim().isEmpty()) {
                    log("打开最近任务查找窗口: " + miniName.trim());
                    if (openFromRecents(miniName.trim())) {
                        restored = true;
                        // 等待 8 秒（含小程序开屏广告），倒计时显示 7s~0s
                        waitCountdownFromZero(8, "等待小程序恢复");
                    }
                }
            }
            if (!restored && !onPage) {
                // 仅当最近任务恢复失败时，才启动目标应用兜底
                String target = getSharedPreferences("daka", MODE_PRIVATE)
                        .getString("target_pkg", "com.tencent.mm");
                if (target != null && !target.trim().isEmpty()) {
                    log("最近任务恢复失败，启动目标应用兜底: " + target.trim());
                    launchApp(target.trim());
                    waitSeconds(5, "等待打卡页面加载");
                }
            }
            page1 = readScreenWithRetry();
            log("第一次读取页面文字: " + summarize(page1));
            waitSeconds(3, "读取页面文字");

            // 4. 等待2S，点击【刷新签到】，再等1S整页 OCR 判断状态
            waitSeconds(2, "准备点击【刷新签到】");
            clickTextWithRetry("刷新签到", 5);
            waitSeconds(1, "刷新完成，读取页面状态");

            // 先按北京时间判断当前处于哪个签到时段（08:51~15:59 之间无时段，返回 null）
            String chip = currentSignInWindow();
            String state = ocrScreenText() + " " + ocrBottomBarText();
            log("页面状态: 当前时间段=" + (chip == null ? "无" : chip)
                    + ", 含未开始=" + state.contains("未开始")
                    + ", 含点击开始签到=" + state.contains("开始签到"));

            if (chip == null) {
                // 不在任何签到时段：出现"未开始"才结束
                if (state.contains("未开始")) {
                    log("检测到【签到未开始】且当前不在签到时段，本次任务结束");
                    showToast("签到未开始，请稍后重试");
                    return;
                }
            } else if (state.contains("未开始")) {
                // 处于签到时段内：中键文字可能尚未刷新或被误读，以时间为准继续执行
                log("页面含【未开始】字样，但当前处于签到时段 " + chip + " 内，继续执行");
            }

            // 5. 点击当前时间段芯片 -> 等待3S -> 点击【点击开始签到】
            if (chip != null) {
                log("当前北京时间属于时间段 " + chip + "，点击对应时间段");
                clickTimeChip(chip);
                waitSeconds(3, "等待时间段生效");
                clickTextWithRetry("点击开始签到", 5);
            } else if (state.contains("开始签到")) {
                // 时段外但页面出现了开始按钮（时间段配置可能已调整），以页面为准
                clickTextWithRetry("点击开始签到", 5);
            } else {
                log("未检测到【点击开始签到】，尝试直接继续");
            }
            waitSeconds(3, "进入签到页");

            // 6. 整页 OCR 记录三个目标坐标，按顺序点击：获取定位 -> 等10S -> 我已阅读并同意 -> 等1S -> 提交
            int[] pLoc = ocrFindCoords("获取定位");
            int[] pRead = ocrFindCoords("我已阅读");
            int[] pSubmit = ocrFindCoords("提交");
            if (pSubmit == null) {
                // "✓ 提交"是青绿底白字且字距大，整页 OCR 常读不出，用品牌色按钮颜色定位兜底
                pSubmit = findTealButton();
                if (pSubmit != null) {
                    log("OCR 未识别【提交】，颜色定位到提交按钮 @ " + pSubmit[0] + "," + pSubmit[1]);
                }
            }
            log("记录坐标: 获取定位=" + fmtCoords(pLoc)
                    + ", 我已阅读并同意=" + fmtCoords(pRead)
                    + ", 提交=" + fmtCoords(pSubmit));

            if (pLoc != null) {
                tapOnMain(pLoc[0], pLoc[1]);
                log("已点击【获取定位】@ " + pLoc[0] + "," + pLoc[1]);
            } else {
                clickTextWithRetry("获取定位", 5);
            }
            waitCountdownFromZero(10, "定位中，请稍候");

            if (pRead != null) {
                tapOnMain(pRead[0], pRead[1]);
                log("已点击【我已阅读并同意】@ " + pRead[0] + "," + pRead[1]);
            } else {
                clickTextWithRetry("我已阅读", 8);
            }
            waitSeconds(1, "准备提交");

            if (pSubmit != null) {
                tapOnMain(pSubmit[0], pSubmit[1]);
                log("已点击【提交】@ " + pSubmit[0] + "," + pSubmit[1]);
            } else if (!clickTextWithRetry("提交", 8)) {
                // 提交前最后再尝试一次颜色定位（弹窗出现后页面布局可能变化）
                int[] p = findTealButton();
                if (p != null) {
                    tapOnMain(p[0], p[1]);
                    log("已点击【提交】(颜色定位) @ " + p[0] + "," + p[1]);
                } else {
                    log("✘ 未找到提交按钮");
                }
            }

            // 7. 等待5S后 OCR 记录【返回打卡页】坐标，等待2S后点击
            waitSeconds(5, "等待提交结果");
            int[] pBack = ocrFindCoords("返回打卡页");
            if (pBack == null) {
                // 成功页带视频广告时，广告画面噪声会干扰整页 OCR，
                // 白字青绿底的"返回打卡页"易被漏识，用品牌色定位兜底
                // （范围 28% 起：避开顶部青绿勾选圆环，覆盖成功页中部的按钮）
                pBack = findTealButton(0.28f);
                if (pBack != null) {
                    log("OCR 未识别【返回打卡页】，颜色定位到按钮 @ " + pBack[0] + "," + pBack[1]);
                }
            }
            waitSeconds(2, "准备返回打卡页");
            if (pBack != null) {
                tapOnMain(pBack[0], pBack[1]);
                log("已点击【返回打卡页】@ " + pBack[0] + "," + pBack[1]);
            } else {
                log("未找到【返回打卡页】，尝试直接检测签到结果");
            }

            // 8. 整页 OCR 检测【时间段内已签到】；返回失败停留在成功页时，"已成功提交"同样视为成功
            waitSeconds(2, "返回签到页");
            String result = ocrScreenText() + " " + ocrBottomBarText();
            if (result.contains("时间段内已签到") || result.contains("已签到")
                    || result.contains("已成功提交")) {
                String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                        .format(new Date());
                log("✔ 签到成功，签到时间: " + now);
                showToast("签到成功");
            } else {
                log("✘ 未检测到【时间段内已签到】，请检查日志");
            }
        } catch (Throwable t) {
            log("执行异常: " + t);
        } finally {
            releaseWake();
            removeOverlaySync();
            AlarmReceiver.reenableKeyguard();
            stopForeground(true);
            mRunning = false;
        }
    }

    private String summarize(String s) {
        if (s == null) return "(读取失败)";
        s = s.trim();
        if (s.isEmpty()) return "(页面无文字)";
        return s.length() > 600 ? s.substring(0, 600) + "…" : s;
    }

    // ---------------- 步骤实现 ----------------

    private void acquireWake() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                mWakeLock = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                                | PowerManager.ON_AFTER_RELEASE,
                        "daka:task");
                mWakeLock.acquire(120000);
            }
        } catch (Throwable ignored) {
        }
    }

    private void releaseWake() {
        try {
            if (mWakeLock != null && mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        } catch (Throwable ignored) {
        }
        mWakeLock = null;
    }

    private void ensureAwake() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                // 再次尝试点亮屏幕
                PowerManager.WakeLock wl = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "daka:retry");
                wl.acquire(15000);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 0. 读取屏幕分辨率 */
    private int[] getResolution() {
        Integer w = callOnMain(new Callable<Integer>() {
            @Override
            public Integer call() {
                DisplayMetrics dm = new DisplayMetrics();
                WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                wm.getDefaultDisplay().getRealMetrics(dm);
                return (dm.widthPixels << 16) | (dm.heightPixels & 0xFFFF);
            }
        });
        int width = (w >> 16) & 0xFFFF;
        int height = w & 0xFFFF;
        return new int[]{width, height};
    }

    /** 2. 屏幕最下方正中央 -> 屏幕中部中心 */
    private void swipeUp(final int[] res) {
        callOnMain(new Callable<Object>() {
            @Override
            public Object call() {
                float x = res[0] / 2f;
                Path path = new Path();
                path.moveTo(x, res[1] - 60);
                path.lineTo(x, res[1] / 2f);
                GestureDescription.Builder b = new GestureDescription.Builder();
                b.addStroke(new GestureDescription.StrokeDescription(path, 0, 500));
                dispatchGesture(b.build(), null, null);
                return null;
            }
        });
    }

    /** 启动目标应用（把打卡页面调到前台） */
    private boolean launchApp(final String pkg) {
        Boolean r = callOnMain(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                try {
                    Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                    if (i == null) {
                        log("未安装应用: " + pkg);
                        return false;
                    }
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    startActivity(i);
                    log("已启动应用: " + pkg);
                    return true;
                } catch (Throwable t) {
                    log("启动应用失败: " + t);
                    return false;
                }
            }
        });
        return r != null && r;
    }

    /** 读取屏幕，页面还没就绪（无文字）时自动重试 */
    private String readScreenWithRetry() {
        String s = "";
        for (int i = 0; i < 6; i++) {
            s = readScreen();
            if (!s.trim().isEmpty()) {
                return s;
            }
            SystemClock.sleep(1000);
        }
        return s;
    }

    /**
     * 打开系统"最近任务"界面，在所有窗口文字中查找包含目标名称的任务卡片并点击恢复。
     * 适用于：小程序只能扫码进入、用户已锁定窗口保持不被杀的场景。
     */
    private boolean openFromRecents(final String name) {
        Boolean opened = callOnMain(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                try {
                    return performGlobalAction(GLOBAL_ACTION_RECENTS);
                } catch (Throwable t) {
                    return false;
                }
            }
        });
        if (opened == null || !opened) {
            log("无法打开最近任务界面");
            return false;
        }
        waitSeconds(2, "打开最近任务");

        // 在最近任务卡片中找目标窗口标题（自动重试，卡片可能未完全加载）
        for (int i = 0; i < 6; i++) {
            if (clickText(name)) {
                log("已在最近任务中找到并恢复窗口: " + name);
                return true;
            }
            SystemClock.sleep(1500);
        }

        log("最近任务中未找到: " + name + "，退出最近任务");
        callOnMain(new Callable<Object>() {
            @Override
            public Object call() {
                try {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                } catch (Throwable ignored) {
                }
                return null;
            }
        });
        return false;
    }

    /**
     * 判断当前是否已在签到页面：
     * 1) 读取到的文字包含页面特有按钮词"刷新签到"或"开始签到"（页面对无障碍透明时）
     * 2) 或任意可见窗口的标题包含小程序名（虾米签页面对无障碍不透明时，
     *    窗口标题"虾米签 | 签到考勤打卡"依然可读）
     * 注意：不能用宽泛的"签到"二字判断——本 App 自身界面文字含"签到"会误判。
     */
    private boolean onSignInPage(String pageText, String miniName) {
        if (pageText != null && (pageText.contains("刷新签到")
                || pageText.contains("开始签到"))) {
            return true;
        }
        return windowTitleMatches(miniName);
    }

    /** 是否存在标题包含小程序名称的可见窗口 */
    private boolean windowTitleMatches(final String miniName) {
        if (miniName == null || miniName.trim().isEmpty()) {
            return false;
        }
        final String key = miniName.trim();
        Boolean r = callOnMain(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                try {
                    List<AccessibilityWindowInfo> wins = getWindows();
                    if (wins != null) {
                        for (AccessibilityWindowInfo w : wins) {
                            CharSequence t = w.getTitle();
                            if (t != null && t.toString().contains(key)) {
                                return true;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                return false;
            }
        });
        return r != null && r;
    }

    /** 取当前所有窗口的根节点（读不到时退回激活窗口） */
    private List<AccessibilityNodeInfo> getAllRoots() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                for (AccessibilityWindowInfo w : wins) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r != null) roots.add(r);
                }
            }
        } catch (Throwable ignored) {
        }
        if (roots.isEmpty()) {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null) roots.add(r);
        }
        return roots;
    }

    /** 当前是否处于锁屏状态 */
    private boolean isKeyguardLocked() {
        try {
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Throwable t) {
            return false;
        }
    }

    private String windowTitle(AccessibilityWindowInfo w) {        try {
            CharSequence t = w.getTitle();
            return t == null ? "" : t.toString().replace('|', '·');
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 3/5. 读取屏幕当前页面文字信息并记录坐标（遍历所有窗口，含窗口诊断信息） */
    private String readScreen() {
        String result = callOnMain(new Callable<String>() {
            @Override
            public String call() {
                StringBuilder sb = new StringBuilder();
                List<AccessibilityWindowInfo> wins = null;
                try {
                    wins = getWindows();
                } catch (Throwable ignored) {
                }
                if (wins != null && !wins.isEmpty()) {
                    for (AccessibilityWindowInfo w : wins) {
                        AccessibilityNodeInfo r = w.getRoot();
                        if (r == null) continue;
                        int[] counter = new int[1];
                        int before = sb.length();
                        collect(r, sb, counter);
                        sb.insert(before, "[" + r.getPackageName()
                                + "·" + windowTitle(w)
                                + "·文字" + counter[0] + "] ");
                    }
                } else {
                    AccessibilityNodeInfo r = getRootInActiveWindow();
                    if (r != null) {
                        int[] counter = new int[1];
                        int before = sb.length();
                        collect(r, sb, counter);
                        sb.insert(before, "[" + r.getPackageName()
                                + "·文字" + counter[0] + "] ");
                    }
                }
                return sb.toString();
            }
        });
        return result == null ? "" : result;
    }

    private void collect(AccessibilityNodeInfo n, StringBuilder sb, int[] counter) {
        if (n == null) return;
        CharSequence t = n.getText();
        if (t != null && t.length() > 0) {
            sb.append(t).append(" | ");
            counter[0]++;
        }
        CharSequence d = n.getContentDescription();
        if (d != null && d.length() > 0) {
            sb.append(d).append(" | ");
            counter[0]++;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            collect(n.getChild(i), sb, counter);
        }
    }

    private boolean clickTextWithRetry(final String keyword, int tries) {
        for (int i = 0; i < tries; i++) {
            if (clickText(keyword)) {
                return true;
            }
            SystemClock.sleep(1000);
        }
        // OCR 识别定位（安卓11+，页面对无障碍不透明时）
        if (ocrClick(keyword)) {
            return true;
        }
        // 静态比例坐标兜底
        double[] fb = FALLBACK_COORDS.get(keyword);
        if (fb != null) {
            int[] res = getResolution();
            final int x = (int) (res[0] * fb[0]);
            final int y = (int) (res[1] * fb[1]);
            callOnMain(new Callable<Object>() {
                @Override
                public Object call() {
                    tap(x, y);
                    return null;
                }
            });
            log("文字未找到，坐标兜底点击【" + keyword + "】@ " + x + "," + y);
            return true;
        }
        log("未找到: " + keyword);
        return false;
    }

    /** 关键词 -> 屏幕比例坐标兜底（x, y），按 1080x2340 截图实测换算，任意分辨率通用 */
    private static final java.util.HashMap<String, double[]> FALLBACK_COORDS =
            new java.util.HashMap<String, double[]>();

    static {
        // 底部栏：右"刷新签到" / 中"点击开始签到"
        FALLBACK_COORDS.put("刷新签到", new double[]{0.926, 0.938});
        FALLBACK_COORDS.put("点击开始签到", new double[]{0.560, 0.938});
        // 时间段芯片（按 1080x2340 截图 png/1.jpg 实测换算）
        FALLBACK_COORDS.put("00:00~08:50", new double[]{0.401, 0.527});
        FALLBACK_COORDS.put("16:00~23:59", new double[]{0.626, 0.527});
    }

    /** 按文字查找节点并点击其中心坐标（记录的文字坐标备用），遍历所有窗口 */
    private boolean clickText(final String keyword) {
        Boolean r = callOnMain(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                List<AccessibilityNodeInfo> nodes = new ArrayList<>();
                for (AccessibilityNodeInfo root : getAllRoots()) {
                    findNodes(root, keyword, nodes);
                }
                for (AccessibilityNodeInfo n : nodes) {
                    Rect rect = new Rect();
                    n.getBoundsInScreen(rect);
                    if (rect.isEmpty()) continue;

                    if (n.isClickable() && n.performAction(
                            AccessibilityNodeInfo.ACTION_CLICK)) {
                        log("已点击【" + keyword + "】(节点) @ " + rect.centerX() + "," + rect.centerY());
                        return true;
                    }
                    // 节点本身不可点击时，向上找可点击父节点
                    AccessibilityNodeInfo p = n.getParent();
                    boolean clicked = false;
                    while (p != null) {
                        if (p.isClickable() && p.performAction(
                                AccessibilityNodeInfo.ACTION_CLICK)) {
                            clicked = true;
                            break;
                        }
                        p = p.getParent();
                    }
                    if (clicked) {
                        log("已点击【" + keyword + "】(父节点) @ " + rect.centerX() + "," + rect.centerY());
                        return true;
                    }
                    // 兜底：直接按中心坐标手势点击
                    tap(rect.centerX(), rect.centerY());
                    log("已点击【" + keyword + "】(坐标手势) @ " + rect.centerX() + "," + rect.centerY());
                    return true;
                }
                return false;
            }
        });
        return r != null && r;
    }

    private void findNodes(AccessibilityNodeInfo n, String keyword, List<AccessibilityNodeInfo> out) {
        if (n == null) return;
        CharSequence t = n.getText();
        CharSequence d = n.getContentDescription();
        if ((t != null && t.toString().contains(keyword))
                || (d != null && d.toString().contains(keyword))) {
            out.add(n);
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            findNodes(n.getChild(i), keyword, out);
        }
    }

    private void tap(final int x, final int y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(path, 0, 50));
        dispatchGesture(b.build(), null, null);
    }

    // ---------------- OCR（离线识别，安卓11+ 截图） ----------------

    /** 截取当前屏幕（软件位图），失败返回 null */
    private Bitmap takeScreenshotSync() {
        if (Build.VERSION.SDK_INT < 30) {
            return null;
        }
        final Object[] out = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            Executor executor = new Executor() {
                @Override
                public void execute(Runnable r) {
                    mMain.post(r);
                }
            };
            takeScreenshot(0 /*Display.DEFAULT_DISPLAY*/, executor,
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(
                                final AccessibilityService.ScreenshotResult screenshot) {
                            try {
                                Bitmap hw = Bitmap.wrapHardwareBuffer(
                                        screenshot.getHardwareBuffer(),
                                        (ColorSpace) screenshot.getColorSpace());
                                if (hw != null) {
                                    out[0] = hw.copy(Bitmap.Config.ARGB_8888, false);
                                }
                                screenshot.getHardwareBuffer().close();
                            } catch (Throwable ignored) {
                            }
                            latch.countDown();
                        }

                        @Override
                        public void onFailure(final int errorCode) {
                            log("截图失败 errorCode=" + errorCode);
                            latch.countDown();
                        }
                    });
            latch.await(10, TimeUnit.SECONDS);
        } catch (Throwable t) {
            log("截图异常: " + t);
            return null;
        }
        Bitmap bmp = (Bitmap) out[0];
        if (bmp == null) {
            log("截图失败（超时或回调为空）");
        }
        return bmp;
    }

    /** OCR 识别整屏文字（无障碍读不到时的兜底），失败返回空串 */
    private String ocrScreenText() {
        if (Build.VERSION.SDK_INT < 30) {
            return "";
        }
        if (!OcrHelper.init(this)) {
            log("OCR 引擎初始化失败");
            return "";
        }
        // 先隐藏倒计时悬浮窗，避免 OCR 匹配到悬浮窗自身文案后误点/污染识别结果
        String prevOverlay = hideOverlaySync();
        Bitmap bmp = takeScreenshotSync();
        String s = "";
        if (bmp != null) {
            s = OcrHelper.recognize(bmp, null, null);
            bmp.recycle();
        }
        // 部分系统合成延迟更长，悬浮窗文案仍可能残留在截图中，检测到则重截一次
        if (prevOverlay != null && !prevOverlay.isEmpty()
                && s.replace(" ", "").contains(prevOverlay.replace(" ", ""))) {
            log("检测到悬浮窗文案残留，重新截图识别");
            SystemClock.sleep(500);
            bmp = takeScreenshotSync();
            if (bmp != null) {
                String s2 = OcrHelper.recognize(bmp, null, null);
                bmp.recycle();
                if (!s2.isEmpty()) {
                    s = s2;
                }
            }
        }
        if (prevOverlay != null) {
            showOverlayOnMain(prevOverlay);
        }
        if (s != null && !s.isEmpty()) {
            log("OCR 识别: " + summarize(s));
        } else {
            log("OCR 未识别到文字");
        }
        return s == null ? "" : s;
    }

    /** OCR 定位关键词并点击中心，成功返回 true */
    private boolean ocrClick(String keyword) {
        int[] hit = ocrLocate(keyword, null, 0, 0);
        if (hit == null) {
            return false;
        }
        tapOnMain(hit[0], hit[1]);
        log("OCR 定位并点击【" + keyword + "】@ " + hit[0] + "," + hit[1]);
        return true;
    }

    /** OCR 定位符合正则的文本并点击中心（用于时间段芯片等写法不稳定的文字），成功返回 true */
    private boolean ocrClickPattern(final Pattern p, final String desc,
                                    float minYRatio, float maxYRatio) {
        int[] hit = ocrLocate(null, p, minYRatio, maxYRatio);
        if (hit == null) {
            return false;
        }
        tapOnMain(hit[0], hit[1]);
        log("OCR 定位并点击【" + desc + "】@ " + hit[0] + "," + hit[1]);
        return true;
    }

    /**
     * OCR 整页识别关键词并记录其中心坐标（不点击）。
     * 页面加载/动画期间可能一帧识别不到，首次未找到时等待 5S 再识别一次，仍未找到返回 null。
     */
    private int[] ocrFindCoords(String keyword) {
        int[] hit = ocrLocate(keyword, null, 0, 0);
        if (hit == null) {
            log("OCR 未找到【" + keyword + "】，等待 5S 后重新识别");
            SystemClock.sleep(5000);
            hit = ocrLocate(keyword, null, 0, 0);
        }
        if (hit == null) {
            log("OCR 重试仍未找到【" + keyword + "】");
            return null;
        }
        return hit;
    }

    /**
     * OCR 定位核心：截图 -> 词级匹配 -> 行级匹配，返回中心坐标 {x, y}；未找到返回 null。
     * keyword 与 pattern 至少传一个；两者都传时优先按关键词匹配。
     * minYRatio/maxYRatio 限定候选纵屏比例范围（<=0 表示不限制），
     * 例如时间段芯片用 (0.42, 0.78) 排除上方信息卡里出现过的同样文字。
     */
    private int[] ocrLocate(final String keyword, final Pattern pattern,
                            float minYRatio, float maxYRatio) {
        if (Build.VERSION.SDK_INT < 30) {
            return null;
        }
        if (!OcrHelper.init(this)) {
            log("OCR 引擎初始化失败，跳过 OCR 定位");
            return null;
        }
        // 先隐藏倒计时悬浮窗，避免 OCR 匹配到悬浮窗自身文案后点击悬浮窗而不是真实按钮
        String prevOverlay = hideOverlaySync();
        Bitmap bmp = takeScreenshotSync();
        int[] hit = null;
        if (bmp != null) {
            // 词级定位：包围盒更精确，直接点词的中心
            List<String> texts = new ArrayList<>();
            List<Rect> rects = new ArrayList<>();
            OcrHelper.recognizeWords(bmp, texts, rects);
            hit = findMatch(texts, rects, keyword, pattern, prevOverlay,
                    bmp.getWidth(), bmp.getHeight(), minYRatio, maxYRatio);
            if (hit == null) {
                // 词级未命中（关键词被拆词时），退回行级 + 行内字符位置估算
                texts.clear();
                rects.clear();
                OcrHelper.recognize(bmp, texts, rects);
                hit = findMatch(texts, rects, keyword, pattern, prevOverlay,
                        bmp.getWidth(), bmp.getHeight(), minYRatio, maxYRatio);
            }
            bmp.recycle();
        }
        if (prevOverlay != null) {
            showOverlayOnMain(prevOverlay);
        }
        return hit;
    }

    /**
     * 在词/行文本列表中查找匹配项，返回点击中心坐标 {x, y}；未找到返回 null。
     * keyword 非空时按 contains 匹配，否则按 pattern 匹配。
     * 匹配短词（如"提交"）时按关键词在词内的位置修正横向中心，避免误点同行相邻词。
     * suppress 为截图前悬浮窗显示过的文字：悬浮窗文案常包含关键词（如"准备点击【刷新签到】"），
     * 且其位于屏幕顶部中央，为防御残留误点，跳过顶部中央区域及与悬浮窗文案高度重合的候选。
     */
    private static int[] findMatch(List<String> texts, List<Rect> rects, String keyword,
                                   Pattern pattern, String suppress, int screenW, int screenH,
                                   float minYRatio, float maxYRatio) {
        String sup = suppress == null ? "" : suppress.replace(" ", "");
        for (int i = 0; i < texts.size(); i++) {
            String s = texts.get(i);
            int idx;
            int mlen;
            if (keyword != null) {
                idx = s.indexOf(keyword);
                mlen = keyword.length();
            } else {
                Matcher m = pattern.matcher(s);
                if (!m.find()) {
                    continue;
                }
                idx = m.start();
                mlen = m.end() - m.start();
            }
            if (idx < 0) {
                continue;
            }
            Rect r = rects.get(i);
            if (minYRatio > 0 && r.centerY() < screenH * minYRatio) {
                continue;
            }
            if (maxYRatio > 0 && r.centerY() > screenH * maxYRatio) {
                continue;
            }
            boolean overlayZone = screenH > 0 && r.top < screenH * 0.13
                    && r.centerX() > screenW * 0.10 && r.centerX() < screenW * 0.90;
            boolean looksLikeOverlay = s.length() > mlen
                    && sup.length() > 0
                    && (s.contains(sup) || sup.contains(s));
            if (overlayZone || looksLikeOverlay) {
                continue;
            }
            int cx;
            if (s.length() <= mlen + 2) {
                // 文本基本就是关键词本身，直接取包围盒中心
                cx = r.centerX();
            } else {
                int len = Math.max(1, s.length());
                cx = r.left + r.width() * (idx * 2 + mlen) / (2 * len);
            }
            return new int[]{cx, r.centerY()};
        }
        return null;
    }

    /**
     * 底部栏专项检测：裁剪屏幕底部中央区域（浅色按钮所在位置），
     * 二值化后按单行识别。用于识别 OCR 整页识别不到的浅灰色"签到未开始"按钮。
     * 返回识别出的文字（小写比较由调用方处理），失败返回空串。
     */
    private String ocrBottomBarText() {
        if (Build.VERSION.SDK_INT < 30) {
            return "";
        }
        if (!OcrHelper.init(this)) {
            return "";
        }
        String prevOverlay = hideOverlaySync();
        Bitmap bmp = takeScreenshotSync();
        String result = "";
        if (bmp != null) {
            int x0 = (int) (bmp.getWidth() * 0.23);
            int x1 = (int) (bmp.getWidth() * 0.77);
            int y0 = (int) (bmp.getHeight() * 0.915);
            int y1 = bmp.getHeight();
            if (x1 > x0 && y1 > y0) {
                Bitmap region = Bitmap.createBitmap(bmp, x0, y0, x1 - x0, y1 - y0);
                Bitmap bin = OcrHelper.binarize(region, 220);
                if (bin != null) {
                    result = OcrHelper.recognizeSingleLine(bin);
                    bin.recycle();
                }
                region.recycle();
            }
            bmp.recycle();
        }
        if (prevOverlay != null) {
            showOverlayOnMain(prevOverlay);
        }
        return result == null ? "" : result;
    }

    /**
     * 颜色定位提交按钮：截屏后在下半屏寻找最大的青绿色实心色块
     * （虾米签主题按钮如"✓ 提交"为青绿底白字，字距大时整页 OCR 常读不出）。
     * 返回色块中心坐标 {x, y}；未找到返回 null。
     */
    private int[] findTealButton() {
        // 下限 0.60：排除时间段选中芯片（y≈0.53，同为青绿底白字）
        return findTealButton(0.60f);
    }

    /** 颜色定位青绿实心按钮，fromRatio 为扫描起始高度比例（0~1） */
    private int[] findTealButton(float fromRatio) {
        if (Build.VERSION.SDK_INT < 30) {
            return null;
        }
        if (!OcrHelper.init(this)) {
            return null;
        }
        String prevOverlay = hideOverlaySync();
        Bitmap bmp = takeScreenshotSync();
        int[] out = null;
        if (bmp != null) {
            out = findTealButtonIn(bmp, fromRatio, 0.97f);
            bmp.recycle();
        }
        if (prevOverlay != null) {
            showOverlayOnMain(prevOverlay);
        }
        return out;
    }

    /** 在位图 fromRatio~toRatio 高度范围内寻找最大青绿色连通色块，返回中心坐标 */
    private static int[] findTealButtonIn(Bitmap bmp, float fromRatio, float toRatio) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int y0 = (int) (h * fromRatio), y1 = (int) (h * toRatio);
        final int step = 8, bs = 36;
        int gw = w / bs, gh = (y1 - y0) / bs;
        if (gw <= 0 || gh <= 0) {
            return null;
        }
        int[] score = new int[gw * gh];
        int[] total = new int[gw * gh];
        for (int y = y0; y < y1; y += step) {
            int row = (y - y0) / bs;
            if (row >= gh) {
                row = gh - 1; // 扫描高度不是块尺寸整数倍时，末行采样归入最后一块
            }
            for (int x = 0; x < w; x += step) {
                int col = x / bs;
                if (col >= gw) {
                    col = gw - 1;
                }
                int cell = row * gw + col;
                total[cell]++;
                if (isTeal(bmp.getPixel(x, y))) {
                    score[cell]++;
                }
            }
        }
        // 过半采样点为品牌青绿的块视为按钮色块
        int need = (int) ((bs / step) * (bs / step) * 0.5);
        int best = -1, bestIdx = -1;
        for (int i = 0; i < score.length; i++) {
            if (score[i] >= need && score[i] > best) {
                best = score[i];
                bestIdx = i;
            }
        }
        if (bestIdx < 0) {
            return null;
        }
        // 从最密集块出发做连通扩展，得到整个按钮的包围盒
        boolean[] used = new boolean[score.length];
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        queue.add(bestIdx);
        used[bestIdx] = true;
        int minR = bestIdx / gw, maxR = minR, minC = bestIdx % gw, maxC = minC;
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            int r = cur / gw, c = cur % gw;
            int[][] dirs = {{r - 1, c}, {r + 1, c}, {r, c - 1}, {r, c + 1}};
            for (int[] d : dirs) {
                int nr = d[0], nc = d[1];
                if (nr < 0 || nr >= gh || nc < 0 || nc >= gw) {
                    continue;
                }
                int nxt = nr * gw + nc;
                if (used[nxt] || score[nxt] < need) {
                    continue;
                }
                used[nxt] = true;
                queue.add(nxt);
                minR = Math.min(minR, nr);
                maxR = Math.max(maxR, nr);
                minC = Math.min(minC, nc);
                maxC = Math.max(maxC, nc);
            }
        }
        int cx = (minC * bs + (maxC + 1) * bs) / 2;
        int cy = y0 + (minR * bs + (maxR + 1) * bs) / 2;
        return new int[]{cx, cy};
    }

    /** 虾米签品牌青绿色判定（如 #2BC0AB）：绿色显著高于红色，蓝绿接近 */
    private static boolean isTeal(int p) {
        int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
        return r < 110 && g > 140 && b > 120 && g - r > 50 && Math.abs(g - b) < 60;
    }

    /** 按当前北京时间判断属于哪个签到时间段，返回芯片文字；不在任何时段返回 null */
    private String currentSignInWindow() {
        java.util.Calendar cal = java.util.Calendar.getInstance(
                TimeZone.getTimeZone("Asia/Shanghai"));
        int minutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60
                + cal.get(java.util.Calendar.MINUTE);
        // 00:00~08:50 / 16:00~23:59；08:51~15:59 之间不属于任何时段
        if (minutes <= 8 * 60 + 50) {
            return "00:00~08:50";
        }
        if (minutes >= 16 * 60) {
            return "16:00~23:59";
        }
        return null;
    }

    /** 点击时间段芯片：先无障碍查找，再 OCR 正则定位（OCR 对 ~ 的写法不稳定），最后按比例坐标兜底 */
    private boolean clickTimeChip(String chip) {
        if (clickText(chip)) {
            return true;
        }
        // OCR 文本中 ~ 和 : 可能识别为 - ～ 5 等其他字符（如 "16:00~235:59"），用正则放宽；
        // 限定纵屏范围 42%~78%，排除上方信息卡"签到时间"行里同样的文字
        String[] parts = chip.split("~");
        Pattern p = Pattern.compile(parts[0].replace(":", ".{0,2}")
                + ".{0,3}" + parts[1].replace(":", ".{0,2}"));
        if (ocrClickPattern(p, chip, 0.42f, 0.78f)) {
            return true;
        }
        double[] fb = FALLBACK_COORDS.get(chip);
        if (fb != null) {
            int[] res = getResolution();
            final int x = (int) (res[0] * fb[0]);
            final int y = (int) (res[1] * fb[1]);
            tapOnMain(x, y);
            log("文字未找到，坐标兜底点击【" + chip + "】@ " + x + "," + y);
            return true;
        }
        log("未找到时间段【" + chip + "】");
        return false;
    }

    /** 在主线程执行点击手势 */
    private void tapOnMain(final int x, final int y) {
        callOnMain(new Callable<Object>() {
            @Override
            public Object call() {
                tap(x, y);
                return null;
            }
        });
    }

    /** 从服务弹 Toast 提示 */
    private void showToast(final String msg) {
        runOnMain(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(DakaAccessibilityService.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** 坐标日志格式化 */
    private static String fmtCoords(int[] p) {
        return p == null ? "未找到" : p[0] + "," + p[1];
    }

    // ---------------- 倒计时悬浮提示 ----------------

    /** 等待 total 秒，倒计时从 total-1 显示到 0（如 7 秒显示 6s~0s） */
    private void waitCountdownFromZero(int total, final String label) {
        for (int remain = total - 1; remain >= 0; remain--) {
            final String text = label + "，剩余 " + remain + "s";
            runOnMain(new Runnable() {
                @Override
                public void run() {
                    showOverlay(text);
                }
            });
            SystemClock.sleep(1000);
        }
    }

    /** 带弹窗倒计时的等待，每秒刷新一次剩余时间 */
    private void waitSeconds(int total, final String label) {        for (int remain = total; remain > 0; remain--) {
            final String text = label + "，剩余 " + remain + "s";
            runOnMain(new Runnable() {
                @Override
                public void run() {
                    showOverlay(text);
                }
            });
            SystemClock.sleep(1000);
        }
        runOnMain(new Runnable() {
            @Override
            public void run() {
                showOverlay(label);
            }
        });
    }

    private void showOverlay(String text) {
        try {
            mOverlayText = text;
            if (mOverlay == null) {
                mOverlay = new TextView(this);
                mOverlay.setTextColor(0xFFFFFFFF);
                mOverlay.setTextSize(16);
                mOverlay.setBackgroundColor(0xCC000000);
                mOverlay.setPadding(28, 20, 28, 20);

                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                lp.y = 80;
                WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                wm.addView(mOverlay, lp);
            }
            mOverlay.setText(text);
        } catch (Throwable ignored) {
        }
    }

    private void removeOverlaySync() {
        callOnMain(new Callable<Object>() {
            @Override
            public Object call() {
                if (mOverlay != null) {
                    try {
                        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                        wm.removeView(mOverlay);
                    } catch (Throwable ignored) {
                    }
                    mOverlay = null;
                }
                mOverlayText = null;
                return null;
            }
        });
    }

    /** 临时隐藏悬浮窗（OCR 截图前调用），返回之前显示的文字；无悬浮窗返回 null */
    private String hideOverlaySync() {
        Boolean had = callOnMain(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                if (mOverlay != null) {
                    try {
                        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                        wm.removeView(mOverlay);
                    } catch (Throwable ignored) {
                    }
                    mOverlay = null;
                    return true;
                }
                return false;
            }
        });
        String prev = mOverlayText;
        if (had != null && had) {
            mOverlayText = null;
            // removeView 是异步的：窗口 surface 不会立刻从合成层移除，
            // 立即截图仍会拍到悬浮窗；MIUI 合成延迟较长，等待 600ms 再返回
            SystemClock.sleep(600);
            return prev != null ? prev : "";
        }
        return null;
    }

    /** 在主线程恢复悬浮窗显示 */
    private void showOverlayOnMain(final String text) {
        runOnMain(new Runnable() {
            @Override
            public void run() {
                showOverlay(text);
            }
        });
    }

    // ---------------- 通知 / 线程工具 ----------------

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "自动打卡",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(android.R.drawable.ic_menu_today)
                .setContentTitle("自动打卡")
                .setContentText(NOTIF_TEXT)
                .setOngoing(true);
        Intent i = new Intent(this, MainActivity.class);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        b.setContentIntent(PendingIntent.getActivity(this, 0, i, piFlags));
        return b.build();
    }

    private void runOnMain(Runnable r) {
        mMain.post(r);
    }

    /** 把操作切回主线程执行并等待结果（无障碍 API 需在主线程调用） */
    @SuppressWarnings("unchecked")
    private <T> T callOnMain(final Callable<T> c) {
        final Object[] out = new Object[1];
        final Throwable[] err = new Throwable[1];
        final CountDownLatch latch = new CountDownLatch(1);
        mMain.post(new Runnable() {
            @Override
            public void run() {
                try {
                    out[0] = c.call();
                } catch (Throwable t) {
                    err[0] = t;
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        if (err[0] instanceof RuntimeException) {
            throw (RuntimeException) err[0];
        }
        if (err[0] instanceof Error) {
            throw (Error) err[0];
        }
        return (T) out[0];
    }
}
