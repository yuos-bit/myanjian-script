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
                tess.recycle();
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
        return recognize(bmp, outTexts, outRects,
                TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK);
    }

    /**
     * 整屏识别（指定页面分割模式）。outTexts/outRects 返回每行文字及包围盒。
     * PSM_SINGLE_BLOCK(6)：对整页 UI 截图识别质量最佳（png/1.jpg 实测对比 PSM_AUTO 更准）。
     */
    private static synchronized String recognize(Bitmap bmp,
                                                 List<String> outTexts,
                                                 List<Rect> outRects,
                                                 int psm) {
        if (sTess == null || bmp == null) {
            return "";
        }
        try {
            sTess.setPageSegMode(psm);
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

    /**
     * 整屏识别（词级）。outTexts/outRects 返回每个词及其在位图中的包围盒。
     * 词级包围盒比行级更精确，OCR 定位点击时优先使用。
     */
    public static synchronized String recognizeWords(Bitmap bmp,
                                                     List<String> outTexts,
                                                     List<Rect> outRects) {
        if (sTess == null || bmp == null) {
            return "";
        }
        try {
            sTess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK);
            sTess.setImage(bmp);
            String full = sTess.getUTF8Text();
            if (outTexts != null && outRects != null) {
                outTexts.clear();
                outRects.clear();
                ResultIterator it = sTess.getResultIterator();
                if (it != null) {
                    it.begin();
                    do {
                        String word = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD);
                        Rect r = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD);
                        if (word != null && word.trim().length() > 0 && r != null) {
                            outTexts.add(word.replace(" ", ""));
                            outRects.add(new Rect(r));
                        }
                    } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD));
                    it.delete();
                }
            }
            return full == null ? "" : full;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 单行区域识别（用于底部栏"签到未开始"等浅色按钮的专项检测）。
     * 输入应为已二值化的区域位图，识别失败返回空串。
     */
    public static synchronized String recognizeSingleLine(Bitmap bmp) {
        if (sTess == null || bmp == null) {
            return "";
        }
        try {
            sTess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_LINE);
            sTess.setImage(bmp);
            String s = sTess.getUTF8Text();
            return s == null ? "" : s.replace(" ", "");
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 灰度二值化：灰度值 > threshold 置白，其余置黑。
     * 用于识别浅色背景上的浅色文字（如灰色"签到未开始"按钮）。
     */
    public static Bitmap binarize(Bitmap src, int threshold) {
        try {
            int w = src.getWidth(), h = src.getHeight();
            int[] px = new int[w * h];
            src.getPixels(px, 0, w, 0, 0, w, h);
            for (int i = 0; i < px.length; i++) {
                int p = px[i];
                // ITU-R 601 灰度
                int gray = (int) (0.299 * ((p >> 16) & 0xFF)
                        + 0.587 * ((p >> 8) & 0xFF) + 0.114 * (p & 0xFF));
                int v = gray > threshold ? 0xFFFFFFFF : 0xFF000000;
                px[i] = v;
            }
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            out.setPixels(px, 0, w, 0, 0, w, h);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    public static synchronized void release() {
        if (sTess != null) {
            try {
                sTess.recycle();
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
