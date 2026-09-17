package com.daka.auto;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 中国大陆法定节假日判断（数据源 NateScarlet/holiday-cn，引用国务院公文，随公告自动更新）。
 *
 * 判定优先级：
 * 1. 当年假期 JSON（本地缓存，网络缺失时自动下载，含节假日休息日与调休补班日）
 * 2. 缓存不可用时按周末规则兜底（周六日休息，周一至周五工作日）
 *
 * 缓存文件：filesDir/holiday_cache/{year}.json，超过 30 天自动重新下载
 */
public final class HolidayHelper {

    private static final String[] SOURCES = {
            // jsDelivr CDN 国内可达性更好，raw.githubusercontent 兜底
            "https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/%d.json",
            "https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/%d.json"
    };
    private static final long REFRESH_INTERVAL_MS = 30L * 24 * 3600 * 1000;

    private HolidayHelper() {
    }

    /** 今天是否为工作日（需要打卡）。含联网刷新，必须在后台线程调用 */
    public static boolean isWorkdayToday(Context ctx) {
        return isWorkday(ctx, System.currentTimeMillis(), true);
    }

    /** 今天是否为工作日，只读本地缓存（未知时按工作日处理，绝不联网） */
    public static boolean isWorkdayCached(Context ctx) {
        return isWorkday(ctx, System.currentTimeMillis(), false);
    }

    /** 今天的状态标签，如"工作日"、"周末"、"节假日·国庆节"、"调休补班日"。allowNet 时可联网刷新 */
    public static String todayLabel(Context ctx, boolean allowNet) {
        Calendar c = Calendar.getInstance();
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(c.getTime());
        JSONObject day = readEntry(ctx, date, allowNet);
        if (day != null) {
            String name = day.optString("name", "");
            boolean off = day.optBoolean("isOffDay", false);
            return (off ? "节假日·" : "调休补班日·") + name;
        }
        int dow = c.get(Calendar.DAY_OF_WEEK);
        boolean weekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY;
        return weekend ? "周末" : "工作日";
    }

    private static boolean isWorkday(Context ctx, long timeMillis, boolean allowNet) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(timeMillis);
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(c.getTime());
        JSONObject day = readEntry(ctx, date, allowNet);
        if (day != null) {
            return !day.optBoolean("isOffDay", false);
        }
        int dow = c.get(Calendar.DAY_OF_WEEK);
        return dow != Calendar.SATURDAY && dow != Calendar.SUNDAY;
    }

    /** 读取某日期在假期安排中的条目；allowNet 且缓存缺失/过期时联网刷新。找不到返回 null */
    private static JSONObject readEntry(Context ctx, String date, boolean allowNet) {
        int year;
        try {
            year = Integer.parseInt(date.substring(0, 4));
        } catch (Exception e) {
            return null;
        }
        File cache = cacheFile(ctx, year);
        if (allowNet && needRefresh(cache)) {
            download(ctx, year);
        }
        JSONObject root = loadCache(ctx, year);
        if (root == null && allowNet) {
            download(ctx, year);
            root = loadCache(ctx, year);
        }
        if (root == null) {
            return null;
        }
        try {
            org.json.JSONArray days = root.optJSONArray("days");
            if (days != null) {
                for (int i = 0; i < days.length(); i++) {
                    JSONObject d = days.optJSONObject(i);
                    if (d != null && date.equals(d.optString("date", ""))) {
                        return d;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean needRefresh(File cache) {
        if (!cache.exists()) {
            return true;
        }
        return System.currentTimeMillis() - cache.lastModified() > REFRESH_INTERVAL_MS;
    }

    private static File cacheFile(Context ctx, int year) {
        File dir = new File(ctx.getFilesDir(), "holiday_cache");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, year + ".json");
    }

    private static JSONObject loadCache(Context ctx, int year) {
        File f = cacheFile(ctx, year);
        if (!f.exists()) {
            return null;
        }
        try {
            FileInputStream in = new FileInputStream(f);
            String s = readAll(in);
            in.close();
            return new JSONObject(s);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 依次尝试各数据源下载当年假期 JSON 并写入缓存，全部失败返回 false */
    private static boolean download(Context ctx, int year) {
        for (String tpl : SOURCES) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(String.format(Locale.US, tpl, year))
                        .openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("User-Agent", "daka-auto/1.0");
                int code = conn.getResponseCode();
                if (code != 200) {
                    continue;
                }
                InputStream in = conn.getInputStream();
                String s = readAll(in);
                in.close();
                JSONObject root = new JSONObject(s);
                if (root.optJSONArray("days") == null) {
                    continue;
                }
                FileOutputStream out = new FileOutputStream(cacheFile(ctx, year));
                out.write(s.getBytes("UTF-8"));
                out.close();
                return true;
            } catch (Throwable t) {
                // 尝试下一个源
            } finally {
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return false;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), "UTF-8");
    }
}
