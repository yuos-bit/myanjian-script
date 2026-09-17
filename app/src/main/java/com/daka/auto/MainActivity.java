package com.daka.auto;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private Button btnMorning, btnEvening;
    private Switch swEnable, swWorkday;
    private EditText etPkg, etMini;
    private TextView tvLog, tvHoliday;
    private ScrollView svLog;
    private boolean editingPkg = false;
    private final android.os.Handler mLogRefresher = new android.os.Handler();
    private final Runnable mLogRefreshTask = new Runnable() {
        @Override
        public void run() {
            refreshLog();
            mLogRefresher.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("daka", MODE_PRIVATE);

        try {
            String ver = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.tv_version)).setText("v" + ver);
        } catch (Exception ignored) {
        }

        if (!prefs.contains("morning")) {
            // 默认早8:00、晚17:00各一次；仅工作日打卡默认开启
            prefs.edit().putString("morning", "08:00:00")
                    .putString("evening", "17:00:00")
                    .putBoolean("workday_only", true).apply();
        }

        btnMorning = findViewById(R.id.btn_morning);
        btnEvening = findViewById(R.id.btn_evening);
        swEnable = findViewById(R.id.sw_enable);
        swWorkday = findViewById(R.id.sw_workday);
        tvLog = findViewById(R.id.tv_log);
        svLog = findViewById(R.id.sv_log);
        tvHoliday = findViewById(R.id.tv_holiday);

        btnMorning.setOnClickListener(v -> pickTime("morning"));
        btnEvening.setOnClickListener(v -> pickTime("evening"));

        swEnable.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean("enabled", checked).apply();
            AlarmReceiver.scheduleAll(MainActivity.this);
            Toast.makeText(MainActivity.this,
                    checked ? "定时打卡已启用" : "定时打卡已关闭",
                    Toast.LENGTH_SHORT).show();
        });

        // 仅工作日打卡：默认勾选，法定节假日/周末自动跳过，调休补班日照常
        swWorkday.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean("workday_only", checked).apply();
            Toast.makeText(MainActivity.this,
                    checked ? "仅工作日打卡已开启" : "仅工作日打卡已关闭",
                    Toast.LENGTH_SHORT).show();
            loadHolidayLabel();
        });

        findViewById(R.id.btn_run).setOnClickListener(v -> runNow());
        etPkg = findViewById(R.id.et_pkg);
        etPkg.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (editingPkg) return;
                prefs.edit().putString("target_pkg",
                        s.toString().trim().isEmpty() ? "com.tencent.mm" : s.toString().trim()).apply();
            }
        });
        etMini = findViewById(R.id.et_mini);
        etMini.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (editingPkg) return;
                prefs.edit().putString("mini_name", s.toString().trim()).apply();
            }
        });

        findViewById(R.id.btn_acc).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.btn_battery).setOnClickListener(v -> requestIgnoreBattery());

        findViewById(R.id.btn_copy_log).setOnClickListener(v -> copyLog());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUi();
        // 日志在任务执行期间持续增长，定时刷新保证显示与实际日志一致
        mLogRefresher.postDelayed(mLogRefreshTask, 1000);
        loadHolidayLabel();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mLogRefresher.removeCallbacks(mLogRefreshTask);
    }

    /** 后台读取今日节假日状态并展示（允许联网刷新缓存） */
    private void loadHolidayLabel() {
        tvHoliday.setText("今天：检测中…");
        new Thread(() -> {
            final String label = HolidayHelper.todayLabel(this, true);
            runOnUiThread(() -> {
                if (tvHoliday != null) {
                    tvHoliday.setText("今天：" + label);
                }
            });
        }, "holiday-label").start();
    }

    private void updateUi() {
        btnMorning.setText(prefs.getString("morning", "08:00:00"));
        btnEvening.setText(prefs.getString("evening", "17:00:00"));
        swEnable.setChecked(prefs.getBoolean("enabled", false));
        swWorkday.setChecked(prefs.getBoolean("workday_only", true));
        editingPkg = true;
        etPkg.setText(prefs.getString("target_pkg", "com.tencent.mm"));
        etMini.setText(prefs.getString("mini_name", "虾米签"));
        editingPkg = false;
        refreshLog();
    }

    /** 用当前日志快照刷新显示；若正处于日志底部则保持吸底 */
    private void refreshLog() {
        String log = DakaAccessibilityService.getLog();
        String next = log.isEmpty() ? "暂无日志" : log;
        String shown = tvLog.getText().toString();
        boolean atBottom = isLogAtBottom();
        if (!next.equals(shown)) {
            tvLog.setText(next);
            if (atBottom) {
                svLog.post(() -> svLog.fullScroll(View.FOCUS_DOWN));
            }
        } else if (atBottom) {
            svLog.post(() -> svLog.fullScroll(View.FOCUS_DOWN));
        }
    }

    private boolean isLogAtBottom() {
        int bottom = tvLog.getBottom() + svLog.getPaddingBottom();
        int visible = svLog.getHeight() - svLog.getPaddingTop() - svLog.getPaddingBottom();
        return bottom - (svLog.getScrollY() + visible) <= svLog.getHeight() / 10;
    }

    /** 复制全部运行日志到剪贴板：先刷新显示，再复制同一份快照，保证所见即所复 */
    private void copyLog() {
        String log = DakaAccessibilityService.getLog();
        String shown = log.isEmpty() ? "暂无日志" : log;
        tvLog.setText(shown);
        if (log.isEmpty()) {
            Toast.makeText(this, "暂无日志可复制", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) {
            Toast.makeText(this, "剪贴板不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("运行日志", log));
        Toast.makeText(this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show();
    }

    private void pickTime(final String key) {
        String cur = prefs.getString(key, "08:00:00");
        String[] parts = cur.split(":");
        int h = Integer.parseInt(parts[0]);
        int m = Integer.parseInt(parts[1]);
        int s = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;

        View view = getLayoutInflater().inflate(R.layout.dialog_time_picker, null);
        final NumberPicker npH = view.findViewById(R.id.np_hour);
        final NumberPicker npM = view.findViewById(R.id.np_minute);
        final NumberPicker npS = view.findViewById(R.id.np_second);

        setupPicker(npH, h, 23);
        setupPicker(npM, m, 59);
        setupPicker(npS, s, 59);

        new AlertDialog.Builder(this)
                .setTitle("选择时间（24小时制）")
                .setView(view)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String t = String.format(Locale.CHINA, "%02d:%02d:%02d",
                                npH.getValue(), npM.getValue(), npS.getValue());
                        prefs.edit().putString(key, t).apply();
                        AlarmReceiver.scheduleAll(MainActivity.this);
                        updateUi();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void setupPicker(NumberPicker np, int value, int max) {
        np.setMinValue(0);
        np.setMaxValue(max);
        np.setValue(value);
        np.setWrapSelectorWheel(true);
        np.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);
        np.setFormatter(new NumberPicker.Formatter() {
            @Override
            public String format(int i) {
                return String.format(Locale.CHINA, "%02d", i);
            }
        });
    }

    private void runNow() {
        if (!DakaAccessibilityService.isConnected()) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        Intent i = new Intent(this, DakaAccessibilityService.class);
        i.setAction(DakaAccessibilityService.ACTION_RUN);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        Toast.makeText(this, "已开始执行", Toast.LENGTH_SHORT).show();
    }

    private void requestIgnoreBattery() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "当前系统不支持该操作", Toast.LENGTH_SHORT).show();
        }
    }
}
