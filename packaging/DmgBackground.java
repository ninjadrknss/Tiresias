// Draws the background of the macOS disk image: dmg-background.png (660x400) and dmg-background@2x.png
// (1320x800). `gradle dmg` runs it into build/dmg-art/:
//
//   java packaging/DmgBackground.java [output-folder]     (default: the folder this file is in)
//
// The look follows the app icon: the One Dark tile colors, with rows of colored bit blocks. A title sits at the top,
// each icon stands on a card, a trail of bit blocks leads from the app to Applications, and a caption sits below.
//
// The positions must match packaging/dmg-settings.py: window 660x400 points, Tiresias.app centred at
// (165, 190), Applications at (495, 190), icons 128 points. Finder draws the icon labels itself, black in light
// mode and white in dark mode, so the cards under the icons are a mid grey where the labels sit (about #747880,
// roughly 4.6:1 contrast for both), while the rest of the window can be dark.

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class DmgBackground {
    static final int W = 660, H = 400; // window size in points
    static final int APP_X = 165, LINK_X = 495, ICON_Y = 190, ICON = 128;
    // The card under each icon and its label (Finder puts the label about 6-24 points below the icon)
    static final float CARD_W = 184, CARD_TOP = ICON_Y - ICON / 2f - 22, CARD_BOTTOM = ICON_Y + ICON / 2f + 38;

    // The app icon's palette (One Dark)
    static final Color BG_TOP = new Color(0x2C313A), BG_BOTTOM = new Color(0x1E2227);
    static final Color[] BITS = {
        new Color(0x61AFEF), new Color(0x98C379), new Color(0xC678DD), new Color(0xD19A66),
        new Color(0x56B6C2), new Color(0xE5C07B), new Color(0xE06C75),
    };

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        File src = new File(DmgBackground.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        File dir = args.length > 0 ? new File(args[0]) : src.isDirectory() ? src : src.getParentFile();
        dir.mkdirs();
        write(1, new File(dir, "dmg-background.png"));
        write(2, new File(dir, "dmg-background@2x.png"));
    }

    static void write(int scale, File out) throws Exception {
        BufferedImage img = new BufferedImage(W * scale, H * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        g.scale(scale, scale); // draw in points from here on

        background(g);
        bitRows(g);
        title(g);
        card(g, APP_X);
        card(g, LINK_X);
        trail(g);
        caption(g);

        g.dispose();
        if (!ImageIO.write(img, "png", out)) throw new IllegalStateException("No PNG writer");
        System.out.println("Wrote " + out + " (" + img.getWidth() + "x" + img.getHeight() + ")");
    }

    // The icon tile's dark gradient, with a soft glow behind the middle row
    static void background(Graphics2D g) {
        g.setPaint(new GradientPaint(0, 0, BG_TOP, 0, H, BG_BOTTOM));
        g.fillRect(0, 0, W, H);
        g.setPaint(
            new RadialGradientPaint(
                new Point2D.Float(W / 2f, ICON_Y),
                W * 0.55f,
                new float[] { 0f, 1f },
                new Color[] { new Color(255, 255, 255, 18), new Color(255, 255, 255, 0) }
            )
        );
        g.fillRect(0, 0, W, H);
    }

    // Faint rows of bit blocks along the top and bottom edges, like a CAN frame's fields
    static void bitRows(Graphics2D g) {
        int[][] rows = {
            { 3, 2, 5, 1, 4, 2, 3, 6, 2, 1, 3, 4, 2, 5, 3, 1, 2, 4, 3, 2, 5, 1, 3 },
            { 2, 4, 1, 3, 6, 2, 2, 3, 5, 1, 4, 2, 3, 1, 5, 2, 4, 3, 1, 2, 3, 4, 2 },
        };
        float unit = 9.5f, gap = 3, h = 9;
        float[] ys = { 10, H - 10 - h };
        for (int r = 0; r < 2; r++) {
            float x = r == 0 ? -14 : -31;
            int c = r * 3;
            for (int w : rows[r]) {
                float bw = w * unit + (w - 1) * gap;
                Color col = BITS[c++ % BITS.length];
                g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 46));
                g.fill(new RoundRectangle2D.Float(x, ys[r], bw, h, 4, 4));
                x += bw + gap;
                if (x > W) break;
            }
        }
    }

    static void title(Graphics2D g) {
        String name = "Tiresias", sub = "CAN spec editor and traffic decoder";
        g.setFont(font(Font.BOLD, 24));
        float nw = width(g, name);
        // a small bit-block mark before the name, from the icon
        float mark = 18, gapMark = 10, total = mark + gapMark + nw, x = (W - total) / 2f, base = 52;
        float mx = x, my = base - 17;
        g.setColor(BITS[0]);
        g.fill(new RoundRectangle2D.Float(mx, my, 11, 7.5f, 3, 3));
        g.setColor(BITS[1]);
        g.fill(new RoundRectangle2D.Float(mx + 12.5f, my, 5.5f, 7.5f, 3, 3));
        g.setColor(BITS[3]);
        g.fill(new RoundRectangle2D.Float(mx, my + 9.5f, 7, 7.5f, 3, 3));
        g.setColor(BITS[6]);
        g.fill(new RoundRectangle2D.Float(mx + 8.5f, my + 9.5f, 9.5f, 7.5f, 3, 3));
        g.setColor(new Color(0xE6E9EF));
        g.drawString(name, x + mark + gapMark, base);
        g.setFont(font(Font.PLAIN, 12.5f));
        g.setColor(new Color(0x9DA5B4));
        g.drawString(sub, (W - width(g, sub)) / 2f, base + 21);
    }

    // A mid-grey card under an icon and its label: lighter at the top, #747880 behind the label
    static void card(Graphics2D g, float cx) {
        RoundRectangle2D card = new RoundRectangle2D.Float(
            cx - CARD_W / 2,
            CARD_TOP,
            CARD_W,
            CARD_BOTTOM - CARD_TOP,
            26,
            26
        );
        // shadow
        for (int i = 6; i >= 1; i--) {
            g.setColor(new Color(0, 0, 0, 9));
            g.fill(
                new RoundRectangle2D.Float(
                    (float) card.getX() - i * 0.6f,
                    (float) card.getY() + 3 - i * 0.3f,
                    (float) card.getWidth() + i * 1.2f,
                    (float) card.getHeight() + i * 1.2f,
                    26 + i,
                    26 + i
                )
            );
        }
        g.setPaint(
            new LinearGradientPaint(
                0,
                CARD_TOP,
                0,
                CARD_BOTTOM,
                new float[] { 0f, 0.62f, 1f },
                new Color[] { new Color(0x858A93), new Color(0x777B84), new Color(0x72767E) }
            )
        );
        g.fill(card);
        // hairline edge, brighter along the top
        g.setStroke(new BasicStroke(1f));
        g.setPaint(
            new GradientPaint(
                0,
                CARD_TOP,
                new Color(255, 255, 255, 70),
                0,
                CARD_BOTTOM,
                new Color(255, 255, 255, 14)
            )
        );
        g.draw(
            new RoundRectangle2D.Float(
                (float) card.getX() + 0.5f,
                (float) card.getY() + 0.5f,
                (float) card.getWidth() - 1,
                (float) card.getHeight() - 1,
                25,
                25
            )
        );
    }

    // Bit blocks that grow brighter towards Applications, ending in a chevron
    static void trail(Graphics2D g) {
        float x0 = APP_X + CARD_W / 2 + 18, x1 = LINK_X - CARD_W / 2 - 18, y = ICON_Y;
        int n = 5;
        float chevron = 16, gap = 6, avail = x1 - x0 - chevron - gap, bw = (avail - (n - 1) * gap) / n, bh = 10;
        int[] colors = { 0, 1, 2, 3, 4 };
        for (int i = 0; i < n; i++) {
            float t = (i + 1) / (float) n;
            Color c = BITS[colors[i]];
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (70 + 185 * t)));
            g.fill(new RoundRectangle2D.Float(x0 + i * (bw + gap), y - bh / 2, bw, bh, 5, 5));
        }
        Path2D chev = new Path2D.Float();
        float cx = x1 - 3;
        chev.moveTo(cx - chevron * 0.7f, y - chevron * 0.75f);
        chev.lineTo(cx, y);
        chev.lineTo(cx - chevron * 0.7f, y + chevron * 0.75f);
        g.setStroke(new BasicStroke(4.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0, 0, 0, 60));
        g.translate(0, 1.2);
        g.draw(chev);
        g.translate(0, -1.2);
        g.setColor(new Color(0xE6E9EF));
        g.draw(chev);
    }

    static void caption(Graphics2D g) {
        String text = "Drag Tiresias onto Applications to install it";
        g.setFont(font(Font.PLAIN, 14));
        g.setColor(new Color(0xC8CDD6));
        g.drawString(text, (W - width(g, text)) / 2f, 336);
    }

    static float width(Graphics2D g, String s) {
        return (float) g.getFontMetrics().getStringBounds(s, g).getWidth();
    }

    // The macOS system font if Java can see it, else Java's standard sans-serif
    static Font font(int style, float size) {
        String[] wanted = { "SF Pro Display", "SF Pro Text", "Helvetica Neue", "Helvetica", "Arial" };
        String[] have = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        for (String w : wanted) for (String h : have) if (h.equals(w)) return new Font(w, style, 1).deriveFont(size);
        return new Font(Font.SANS_SERIF, style, 1).deriveFont(size);
    }
}
