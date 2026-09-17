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

import java.util.concurrent.Executor;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
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
    private PowerManager.WakeLock mWakeLock;

    public static boolean isConnected() {
        return sInstance != null;
    }

    public static String getLog() {
        synchronized (sLog) {
            return sLog.toString();
        }
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

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_RUN.equals(intent.getAction())) {
            startForeground(1, buildNotification());
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
                        // 等待 7 秒，倒计时显示 6s~0s
                        waitCountdownFromZero(7, "等待小程序恢复");
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

            // 4. 点击 刷新签到 -> 点击开始签到
            waitSeconds(2, "准备点击【刷新签到】");
            boolean s1 = clickTextWithRetry("刷新签到", 5);
            waitSeconds(1, "等待【点击开始签到】出现");
            boolean s2 = clickTextWithRetry("点击开始签到", 5);
            waitSeconds(2, "进入签到页");

            // 5. 重新读取屏幕文字（弹窗倒计时提示），无障碍读不到时 OCR 兜底
            String page2 = readScreenWithRetry();
            if (!page2.contains("签到")) {
                String ocr2 = ocrScreenText();
                if (!ocr2.isEmpty()) {
                    page2 = page2 + " " + ocr2;
                }
            }
            log("第二次读取页面文字: " + summarize(page2));

            // 点击刷新签到后如果仍未开始，直接结束本次任务
            if (page2.contains("签到未开始")) {
                log("检测到【签到未开始】，本次任务结束");
                return;
            }
            waitSeconds(3, "重新读取页面文字");

            // 6. 点击获取定位 -> 我已阅读
            boolean s3 = clickTextWithRetry("获取定位", 5);
            waitSeconds(2, "等待定位弹窗");
            boolean s4 = clickTextWithRetry("我已阅读", 8);

            // 7. 等待2S -> 提交
            waitSeconds(2, "准备提交");
            boolean s5 = clickTextWithRetry("提交", 8);

            boolean ok = s1 && s2 && s3 && s4 && s5;
            log(ok ? "✔ 打卡流程执行完毕" : "✘ 部分步骤未找到对应按钮，请检查日志");
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
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
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
        Bitmap bmp = takeScreenshotSync();
        if (bmp == null) {
            return "";
        }
        String s = OcrHelper.recognize(bmp, null, null);
        bmp.recycle();
        if (s != null && !s.isEmpty()) {
            log("OCR 识别: " + summarize(s));
        } else {
            log("OCR 未识别到文字");
        }
        return s == null ? "" : s;
    }

    /** OCR 定位关键词并点击中心，成功返回 true */
    private boolean ocrClick(String keyword) {
        if (Build.VERSION.SDK_INT < 30) {
            return false;
        }
        if (!OcrHelper.init(this)) {
            log("OCR 引擎初始化失败，跳过 OCR 定位");
            return false;
        }
        Bitmap bmp = takeScreenshotSync();
        if (bmp == null) {
            return false;
        }
        List<String> texts = new ArrayList<>();
        List<Rect> rects = new ArrayList<>();
        OcrHelper.recognize(bmp, texts, rects);
        bmp.recycle();

        for (int i = 0; i < texts.size(); i++) {
            String line = texts.get(i);
            int idx = line.indexOf(keyword);
            if (idx < 0) {
                continue;
            }
            Rect r = rects.get(i);
            // 行内等宽近似：按关键字在行内的字符位置估算横向中心
            int len = Math.max(1, line.length());
            int cx = r.left + r.width() * (idx * 2 + keyword.length()) / (2 * len);
            int cy = r.centerY();
            final int fx = cx;
            final int fy = cy;
            callOnMain(new Callable<Object>() {
                @Override
                public Object call() {
                    tap(fx, fy);
                    return null;
                }
            });
            log("OCR 定位并点击【" + keyword + "】@ " + cx + "," + cy);
            return true;
        }
        return false;
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
                return null;
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
