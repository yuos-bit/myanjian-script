import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Generate Android launcher icons (all densities) + adaptive icon from logo/logo.png.
 * Output to app/src/main/res/:
 *  - mipmap-mdpi..xxxhdpi/ic_launcher.png            (48..192px)
 *  - mipmap-mdpi..xxxhdpi/ic_launcher_round.png      (circle cropped)
 *  - mipmap-mdpi..xxxhdpi/ic_launcher_foreground.png (108..432px canvas, safe zone)
 *  - mipmap-anydpi-v26/ic_launcher.xml + ic_launcher_round.xml
 */
public class GenIcons {
    public static void main(String[] args) throws Exception {
        File root = new File(".").getAbsoluteFile().getParentFile();
        if (args.length > 0) {
            root = new File(args[0]);
        }
        File logoFile = new File(root, "logo/logo.png");
        File resDir = new File(root, "app/src/main/res");
        BufferedImage logo = ImageIO.read(logoFile);
        System.out.println("logo: " + logo.getWidth() + "x" + logo.getHeight());

        String[][] dirs = {
                {"mipmap-mdpi", "48"}, {"mipmap-hdpi", "72"}, {"mipmap-xhdpi", "96"},
                {"mipmap-xxhdpi", "144"}, {"mipmap-xxxhdpi", "192"}};
        String[][] fgDirs = {
                {"mipmap-mdpi", "108"}, {"mipmap-hdpi", "162"}, {"mipmap-xhdpi", "216"},
                {"mipmap-xxhdpi", "324"}, {"mipmap-xxxhdpi", "432"}};

        for (String[] d : dirs) {
            int s = Integer.parseInt(d[1]);
            File dir = new File(resDir, d[0]);
            dir.mkdirs();
            write(scale(logo, s, s), new File(dir, "ic_launcher.png"));
            write(circle(scale(logo, s, s)), new File(dir, "ic_launcher_round.png"));
        }

        // adaptive icon: 108dp canvas, content within ~66dp safe zone (~61 percent)
        for (String[] d : fgDirs) {
            int s = Integer.parseInt(d[1]);
            int inner = Math.round(s * 0.61f);
            File dir = new File(resDir, d[0]);
            dir.mkdirs();
            BufferedImage canvas = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(scale(logo, inner, inner), (s - inner) / 2, (s - inner) / 2, null);
            g.dispose();
            write(canvas, new File(dir, "ic_launcher_foreground.png"));
        }

        File anydpi = new File(resDir, "mipmap-anydpi-v26");
        anydpi.mkdirs();
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
                + "    <background android:drawable=\"@color/ic_launcher_background\"/>\n"
                + "    <foreground android:drawable=\"@mipmap/ic_launcher_foreground\"/>\n"
                + "</adaptive-icon>\n";
        writeFile(new File(anydpi, "ic_launcher.xml"), xml);
        writeFile(new File(anydpi, "ic_launcher_round.xml"), xml);

        File values = new File(resDir, "values");
        values.mkdirs();
        String colors = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<resources>\n"
                + "    <color name=\"ic_launcher_background\">#FFFFFF</color>\n"
                + "</resources>\n";
        // append color if colors.xml already exists
        File colorsFile = new File(values, "colors.xml");
        if (colorsFile.exists()) {
            String old = new String(java.nio.file.Files.readAllBytes(colorsFile.toPath()),
                    "UTF-8");
            if (!old.contains("ic_launcher_background")) {
                old = old.replace("</resources>",
                        "    <color name=\"ic_launcher_background\">#FFFFFF</color>\n</resources>\n");
                writeFile(colorsFile, old);
            }
        } else {
            writeFile(colorsFile, colors);
        }
        System.out.println("ICONS_DONE");
    }

    static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    /** circle crop, transparent outside */
    static BufferedImage circle(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int cx = w / 2, cy = h / 2, r = Math.min(w, h) / 2;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int dx = x - cx, dy = y - cy;
                if ((long) dx * dx + (long) dy * dy <= (long) r * r) {
                    dst.setRGB(x, y, src.getRGB(x, y));
                }
            }
        }
        return dst;
    }

    static void write(BufferedImage img, File out) throws Exception {
        ImageIO.write(img, "png", out);
    }

    static void writeFile(File f, String s) throws Exception {
        java.nio.file.Files.write(f.toPath(), s.getBytes("UTF-8"));
    }
}
