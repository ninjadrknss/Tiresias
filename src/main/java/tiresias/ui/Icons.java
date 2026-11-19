package tiresias.ui;

import static tiresias.Tiresias.*;
import static tiresias.spec.Families.*;
import static tiresias.spec.Spec.*;
import static tiresias.traffic.Can.*;
import static tiresias.ui.Look.*;
import static tiresias.ui.Ui.*;
import static tiresias.ui.Widgets.*;

import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;
import java.util.prefs.Preferences;
import java.util.regex.*;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import javax.swing.plaf.*;
import javax.swing.table.*;
import javax.swing.tree.*;
import tiresias.*;
import tiresias.editor.*;
import tiresias.spec.*;
import tiresias.traffic.*;
import tiresias.traffic.dashboards.*;

// ======================================================================================
// App icon: drawn in code so it works everywhere (even run from source). A custom icon.png bundled into the
// jar by the Gradle build replaces it, and --export-icons writes the .png/.icns/.ico files the packages need.
// ======================================================================================

public final class Icons {

    private static java.awt.image.BufferedImage custom;
    private static boolean customChecked;

    /** The team's own icon, if the build bundled an icon.png into the jar. */
    public static synchronized java.awt.image.BufferedImage custom() {
        if (!customChecked) {
            customChecked = true;
            try (var in = Tiresias.class.getResourceAsStream("/icon.png")) {
                if (in != null) custom = javax.imageio.ImageIO.read(in);
            } catch (IOException ignored) {}
        }
        return custom;
    }

    /**
     * Apple's continuous-corner rounded square (the macOS "squircle"): unlike a plain rounded rectangle, the curve
     * starts about 1.53 radii along each edge and eases in, which is what makes macOS icons look softer.
     */
    public static java.awt.geom.Path2D squircle(double x, double y, double size, double r) {
        r = Math.min(r, size / (2 * 1.52866483));
        // one corner's control points, as distances (along, across) from the corner, in units of r
        double[] a = { 1.52866483, 1.08849323, 0.86840689, 0.66993427, 0.37282392, 0.16905899, 0.06549600, 0, 0, 0 };
        double[] b = { 0, 0, 0, 0.06549600, 0.16905899, 0.37282392, 0.66993427, 0.86840689, 1.08849323, 1.52866483 };
        var p = new java.awt.geom.Path2D.Double();
        double[][] corners = {
            { x + size, y, -1, 1, 0 },
            { x + size, y + size, -1, -1, 1 },
            { x, y + size, 1, -1, 0 },
            { x, y, 1, 1, 1 },
        };
        for (double[] c : corners) {
            double[] px = new double[10],
                py = new double[10];
            for (int k = 0; k < 10; k++) {
                double u = (c[4] == 0 ? a[k] : b[k]) * r,
                    v = (c[4] == 0 ? b[k] : a[k]) * r;
                px[k] = c[0] + c[2] * u;
                py[k] = c[1] + c[3] * v;
            }
            if (p.getCurrentPoint() == null) p.moveTo(px[0], py[0]);
            else p.lineTo(px[0], py[0]);
            for (int k = 1; k < 10; k += 3) p.curveTo(px[k], py[k], px[k + 1], py[k + 1], px[k + 2], py[k + 2]);
        }
        p.closePath();
        return p;
    }

    /** The built-in icon, on Apple's macOS template: an 824/1024 continuous-corner tile with a soft drop shadow. */
    public static java.awt.image.BufferedImage draw(int size) {
        var img = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double s = size / 1024.0;
        double inset = size <= 32 ? 0 : 100 * s; // the template's margin, dropped at tiny sizes for legibility
        double tile = size - 2 * inset;
        var shape = squircle(inset, inset, tile, (tile * 185.4) / 824);
        if (size >= 64) {
            // the template's drop shadow: 28px blur, 12px down, at 1024
            var shadow = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D sg = shadow.createGraphics();
            sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            sg.setColor(new Color(0, 0, 0, 110));
            sg.translate(0, 12 * s);
            sg.fill(shape);
            sg.dispose();
            int k = Math.max(3, (int) Math.round(28 * s) | 1);
            float[] w = new float[k];
            Arrays.fill(w, 1f / k);
            for (var kernel : List.of(new java.awt.image.Kernel(k, 1, w), new java.awt.image.Kernel(1, k, w)))
                shadow = new java.awt.image.ConvolveOp(kernel, java.awt.image.ConvolveOp.EDGE_NO_OP, null).filter(
                    shadow,
                    null
                );
            g.drawImage(shadow, 0, 0, null);
        }
        g.setPaint(
            new GradientPaint(0, (float) inset, new Color(0x2C313A), 0, (float) (inset + tile), new Color(0x1E2227))
        );
        g.fill(shape);
        g.setColor(new Color(255, 255, 255, 28));
        g.setStroke(new BasicStroke((float) Math.max(1, 6 * s)));
        g.draw(shape);
        // three rows of blocks; widths are in eighths of a row, like bits in a byte
        int[][] rows = { { 5, 3 }, { 2, 6 }, { 3, 2, 3 } };
        Color[][] colors = {
            { new Color(0x61AFEF), new Color(0x98C379) },
            { new Color(0xC678DD), new Color(0xD19A66) },
            { new Color(0x56B6C2), new Color(0xE5C07B), new Color(0xE06C75) },
        };
        double pad = tile * 0.17,
            gap = tile * 0.045,
            rowH = (tile - 2 * pad - 2 * gap) / 3,
            unit = (tile - 2 * pad) / 8;
        for (int row = 0; row < rows.length; row++) {
            double x = inset + pad,
                y = inset + pad + row * (rowH + gap);
            for (int k = 0; k < rows[row].length; k++) {
                double w = rows[row][k] * unit - (k < rows[row].length - 1 ? gap : 0);
                g.setColor(colors[row][k]);
                g.fill(new java.awt.geom.RoundRectangle2D.Double(x, y, w, rowH, rowH * 0.45, rowH * 0.45));
                x += rows[row][k] * unit;
            }
        }
        g.dispose();
        return img;
    }

    public static java.awt.image.BufferedImage image(int size) {
        var c = custom();
        if (c == null) return draw(size);
        var img = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(c, 0, 0, size, size, null);
        g.dispose();
        return img;
    }

    /** Window/taskbar icons at the sizes each OS asks for, plus the macOS Dock icon. */
    public static void install(Window w) {
        w.setIconImages(
            List.of(16, 20, 24, 32, 40, 48, 64, 128, 256)
                .stream()
                .map(n -> (Image) image(n))
                .toList()
        );
        setDockIcon();
        // On macOS, Java resets the Dock to its default icon once its own startup finishes, which can come after
        // the call above; setting it again when the window opens makes it stick.
        w.addWindowListener(
            new WindowAdapter() {
                @Override
                public void windowOpened(WindowEvent e) {
                    setDockIcon();
                }
            }
        );
    }

    public static void setDockIcon() {
        try {
            if (
                Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)
            ) Taskbar.getTaskbar().setIconImage(image(512));
        } catch (RuntimeException ignored) {} // some desktops refuse; the window icon still applies
    }

    public static byte[] png(int size) throws IOException {
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image(size), "png", out);
        return out.toByteArray();
    }

    /** {@code --export-icons <dir> [custom.png]}: writes icon.png (1024), icon-256.png, icon.icns (macOS) and icon.ico (Windows). */
    public static int export(List<String> args) {
        if (args.isEmpty()) {
            System.err.println("Usage: --export-icons <output-dir> [source.png]");
            return 2;
        }
        try {
            Path dir = Files.createDirectories(Paths.get(args.getFirst()));
            if (args.size() > 1) {
                custom = javax.imageio.ImageIO.read(Paths.get(args.get(1)).toFile());
                customChecked = true;
                if (custom == null) {
                    System.err.println("Not a readable image: " + args.get(1));
                    return 2;
                }
            }
            Files.write(dir.resolve("icon.png"), png(1024));
            Files.write(dir.resolve("icon-256.png"), png(256));
            // ICNS: big-endian "icns" + total length, then (type, length, PNG data) entries
            String[][] icns = {
                { "icp4", "16" },
                { "icp5", "32" },
                { "icp6", "64" },
                { "ic07", "128" },
                { "ic08", "256" },
                { "ic09", "512" },
                { "ic10", "1024" },
                { "ic11", "32" },
                { "ic12", "64" },
                { "ic13", "256" },
                { "ic14", "512" },
            };
            var body = new java.io.ByteArrayOutputStream();
            for (String[] e : icns) {
                byte[] data = png(Integer.parseInt(e[1]));
                body.write(e[0].getBytes(StandardCharsets.US_ASCII));
                body.write(
                    java.nio.ByteBuffer.allocate(4)
                        .putInt(8 + data.length)
                        .array()
                );
                body.write(data);
            }
            var icnsFile = java.nio.ByteBuffer.allocate(8 + body.size())
                .put("icns".getBytes(StandardCharsets.US_ASCII))
                .putInt(8 + body.size())
                .put(body.toByteArray());
            Files.write(dir.resolve("icon.icns"), icnsFile.array());
            // ICO: little-endian header and directory, PNG-compressed images (Windows Vista and later)
            int[] sizes = { 16, 24, 32, 48, 64, 128, 256 };
            List<byte[]> images = new ArrayList<>();
            for (int n : sizes) images.add(png(n));
            int offset = 6 + 16 * sizes.length,
                total =
                    offset +
                    images
                        .stream()
                        .mapToInt(b -> b.length)
                        .sum();
            var ico = java.nio.ByteBuffer.allocate(total).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            ico.putShort((short) 0)
                .putShort((short) 1)
                .putShort((short) sizes.length);
            for (int i = 0; i < sizes.length; i++) {
                int n = sizes[i] >= 256 ? 0 : sizes[i];
                ico.put((byte) n)
                    .put((byte) n)
                    .put((byte) 0)
                    .put((byte) 0)
                    .putShort((short) 1)
                    .putShort((short) 32)
                    .putInt(images.get(i).length)
                    .putInt(offset);
                offset += images.get(i).length;
            }
            for (byte[] b : images) ico.put(b);
            Files.write(dir.resolve("icon.ico"), ico.array());
            System.out.println("Wrote icon.png, icon-256.png, icon.icns and icon.ico to " + dir.toAbsolutePath());
            return 0;
        } catch (IOException e) {
            System.err.println("Couldn't export icons: " + e.getMessage());
            return 2;
        }
    }
}
