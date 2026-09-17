package com.daka.auto;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import com.googlecode.tesseract.android.ResultIterator;
import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * 离线 OCR（Tesseract 中文简体 fast 模型）。
 * 用于微信小程序等对无障碍不透明的页面：截图后识别文字及坐标。
 * 模型随 APK 打包在 assets/tessdata/，首次使用时复制到应用私有目录。
 */
public final class OcrHelper {

    private static TessBaseAPI sTess;
    private static boolean sInitFailed = false;

    private OcrHelper() {
    }

    /** 初始化（幂等）。失败后不再重试，避免每次任务都卡在初始化上。 */
    public static synchronized boolean init(Context ctx) {
        if (sTess != null) {
            return true;
        }
        if (sInitFailed) {
            return false;
        }
        try {
            File dir = new File(ctx.getFilesDir(), "tessdata");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File data = new File(dir, "chi_sim.traineddata");
            if (!data.exists() || data.length() < 1000000) {
                copy(ctx.getAssets().open("tessdata/chi_sim.traineddata"),
                        new FileOutputStream(data));
            }
            TessBaseAPI tess = new TessBaseAPI();
            tess.setVariable("user_defined_dpi", "300");
            boolean ok = tess.init(ctx.getFilesDir().getAbsolutePath(), "chi_sim");
            if (!ok) {
                tess.end();
                sInitFailed = true;
                return false;
            }
            sTess = tess;
            return true;
        } catch (Throwable t) {
            sInitFailed = true;
            return false;
        }
    }

    /**
     * 整屏识别。outTexts/outRects 返回每一行文字及其在位图中的包围盒（可为 null）。
     * 返回整页拼接文字。
     */
    public static synchronized String recognize(Bitmap bmp,
                                                List<String> outTexts,
                                                List<Rect> outRects) {
        if (sTess == null || bmp == null) {
            return "";
        }
        try {
            sTess.setImage(bmp);
            String full = sTess.getUTF8Text();
            if (outTexts != null && outRects != null) {
                outTexts.clear();
                outRects.clear();
                ResultIterator it = sTess.getResultIterator();
                if (it != null) {
                    it.begin();
                    do {
                        String line = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE);
                        Rect r = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE);
                        if (line != null && line.trim().length() > 0 && r != null) {
                            outTexts.add(line.replace(" ", ""));
                            outRects.add(new Rect(r));
                        }
                    } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE));
                    it.delete();
                }
            }
            return full == null ? "" : full;
        } catch (Throwable t) {
            return "";
        }
    }

    public static synchronized void release() {
        if (sTess != null) {
            try {
                sTess.end();
            } catch (Throwable ignored) {
            }
            sTess = null;
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
            try {
                out.close();
            } catch (Exception ignored) {
            }
        }
    }
}
