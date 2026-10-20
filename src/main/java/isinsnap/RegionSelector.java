package isinsnap;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.util.function.Consumer;

/**
 * "Snipping tool" style selection of a screen region.
 *
 * It first captures the screen the mouse is on, then shows the frozen image full-screen and
 * lets the user drag a rectangle. Working on a frozen image avoids issues with translucent windows
 * (not supported everywhere on Linux) and captures exactly what the user saw at click time.
 *
 * HiDPI/Retina: the capture uses physical pixels, the selection happens in logical coordinates
 * and is mapped back to physical pixels before cropping.
 */
public final class RegionSelector {
    private RegionSelector() {}

    public static void select(Consumer<BufferedImage> onSelected, Runnable onCancel) {
        try {
            GraphicsDevice device = deviceUnderMouse();
            Rectangle bounds = device.getDefaultConfiguration().getBounds();
            BufferedImage shot = capture(bounds);
            show(device, bounds, shot, onSelected, onCancel);
        } catch (Exception e) {
            e.printStackTrace();
            onCancel.run();
        }
    }

    private static GraphicsDevice deviceUnderMouse() {
        PointerInfo pi = MouseInfo.getPointerInfo();
        if (pi != null && pi.getDevice() != null) return pi.getDevice();
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
    }

    /** Capture in physical pixels (the highest-resolution variant). */
    static BufferedImage capture(Rectangle bounds) throws Exception {
        Robot robot = new Robot();
        MultiResolutionImage mri = robot.createMultiResolutionScreenCapture(bounds);
        Image best = null;
        for (Image v : mri.getResolutionVariants()) {
            if (best == null || v.getWidth(null) > best.getWidth(null)) best = v;
        }
        BufferedImage out = new BufferedImage(best.getWidth(null), best.getHeight(null), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(best, 0, 0, null);
        g.dispose();
        return out;
    }

    private static void show(GraphicsDevice device, Rectangle bounds, BufferedImage shot,
                             Consumer<BufferedImage> onSelected, Runnable onCancel) {
        JFrame frame = new JFrame("Select area", device.getDefaultConfiguration());
        frame.setUndecorated(true);
        frame.setAlwaysOnTop(true);
        frame.setBounds(bounds);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        Selector panel = new Selector(bounds, shot);
        frame.setContentPane(panel);

        boolean[] finished = { false };
        Runnable cancel = () -> {
            if (finished[0]) return;
            finished[0] = true;
            frame.dispose();
            onCancel.run();
        };

        MouseAdapter mouse = new MouseAdapter() {
            Point start;
            @Override public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) { cancel.run(); return; }
                start = e.getLocationOnScreen();
                panel.selection = new Rectangle(start);
                panel.repaint();
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (start == null) return;
                Point p = e.getLocationOnScreen();
                panel.selection = new Rectangle(Math.min(start.x, p.x), Math.min(start.y, p.y),
                                                Math.abs(p.x - start.x), Math.abs(p.y - start.y));
                panel.repaint();
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (start == null || finished[0]) return;
                Rectangle sel = panel.selection;
                start = null;
                if (sel == null || sel.width < 5 || sel.height < 5) { panel.selection = null; panel.repaint(); return; }
                finished[0] = true;
                frame.dispose();
                onSelected.accept(crop(shot, bounds, sel));
            }
        };
        panel.addMouseListener(mouse);
        panel.addMouseMotionListener(mouse);
        panel.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));

        panel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "cancel");
        panel.getActionMap().put("cancel", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { cancel.run(); }
        });
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { cancel.run(); }
        });

        frame.setVisible(true);
        frame.toFront();
        frame.requestFocus();
    }

    /** Crops the physical-pixel image, starting from a selection in logical screen coordinates. */
    static BufferedImage crop(BufferedImage shot, Rectangle bounds, Rectangle sel) {
        double sx = shot.getWidth() / (double) bounds.width;
        double sy = shot.getHeight() / (double) bounds.height;
        int x = (int) Math.floor((sel.x - bounds.x) * sx);
        int y = (int) Math.floor((sel.y - bounds.y) * sy);
        int w = (int) Math.ceil(sel.width * sx);
        int h = (int) Math.ceil(sel.height * sy);
        x = Math.max(0, Math.min(x, shot.getWidth() - 1));
        y = Math.max(0, Math.min(y, shot.getHeight() - 1));
        w = Math.max(1, Math.min(w, shot.getWidth() - x));
        h = Math.max(1, Math.min(h, shot.getHeight() - y));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(shot.getSubimage(x, y, w, h), 0, 0, null);
        g.dispose();
        return out;
    }

    /** Paints the frozen screenshot aligned with the real screen, dimmed outside the selection. */
    private static final class Selector extends JComponent {
        final Rectangle bounds;
        final BufferedImage shot;
        Rectangle selection; // logical screen coordinates

        Selector(Rectangle bounds, BufferedImage shot) {
            this.bounds = bounds;
            this.shot = shot;
            setOpaque(true);
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            // The window may not cover the screen exactly (e.g. the macOS menu bar):
            // align the image using the component's real position.
            Point o = isShowing() ? getLocationOnScreen() : new Point(bounds.x, bounds.y);
            int dx = bounds.x - o.x, dy = bounds.y - o.y;
            g.drawImage(shot, dx, dy, bounds.width, bounds.height, null);

            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
            g.setColor(Color.BLACK);
            if (selection == null || selection.isEmpty()) {
                g.fillRect(0, 0, getWidth(), getHeight());
            } else {
                Rectangle r = new Rectangle(selection.x - o.x, selection.y - o.y, selection.width, selection.height);
                java.awt.geom.Area dim = new java.awt.geom.Area(new Rectangle(0, 0, getWidth(), getHeight()));
                dim.subtract(new java.awt.geom.Area(r));
                g.fill(dim);
                g.setComposite(AlphaComposite.SrcOver);
                g.setColor(new Color(0x3d, 0x8b, 0xfd));
                g.setStroke(new BasicStroke(2f));
                g.drawRect(r.x, r.y, r.width, r.height);
            }

            g.setComposite(AlphaComposite.SrcOver);
            String hint = "Drag to select the area with the ISINs  ·  Esc or right-click to cancel";
            g.setFont(getFont() != null ? getFont().deriveFont(Font.BOLD, 14f) : new Font(Font.SANS_SERIF, Font.BOLD, 14));
            int tw = g.getFontMetrics().stringWidth(hint);
            int tx = (getWidth() - tw) / 2, ty = 40;
            g.setColor(new Color(0, 0, 0, 190));
            g.fillRoundRect(tx - 14, ty - 22, tw + 28, 34, 12, 12);
            g.setColor(Color.WHITE);
            g.drawString(hint, tx, ty);
            g.dispose();
        }
    }
}
