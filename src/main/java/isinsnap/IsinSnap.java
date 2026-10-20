package isinsnap;

import isinsnap.IsinExtractor.Match;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.MenuItem;
import java.awt.Point;
import java.awt.PopupMenu;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

/**
 * ISIN Snap: an always-on-top button (plus a system tray icon, where available).
 * Click → select a screen region → OCR → list of the ISINs found.
 *
 * Command line:
 *   java -jar isin-snap.jar                 starts the app
 *   java -jar isin-snap.jar --file img.png  analyses an image and prints the ISINs (handy for testing)
 *   java -jar isin-snap.jar --selftest      checks the ISIN recognition logic
 */
public final class IsinSnap {

    private final IsinScanner scanner;
    private JFrame launcher;
    private JButton button;
    private boolean busy;

    private IsinSnap(IsinScanner scanner) { this.scanner = scanner; }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--selftest")) {
            System.exit(SelfTest.run() == 0 ? 0 : 1);
        }

        Optional<Path> exe = TesseractOcr.locate();

        if (args.length > 1 && args[0].equals("--file")) {
            if (exe.isEmpty()) { System.err.println(missingTesseractMessage()); System.exit(2); }
            cli(new IsinScanner(new TesseractOcr(exe.get())), new File(args[1]));
            return;
        }

        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("No display available. Use --file <image> to analyse a file.");
            System.exit(1);
        }
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }

        if (exe.isEmpty()) {
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(null, missingTesseractMessage(), "ISIN Snap – Tesseract not found",
                    JOptionPane.ERROR_MESSAGE);
                System.exit(2);
            });
            return;
        }
        IsinSnap app = new IsinSnap(new IsinScanner(new TesseractOcr(exe.get())));
        SwingUtilities.invokeLater(app::start);
    }

    private static void cli(IsinScanner scanner, File file) throws Exception {
        BufferedImage img = ImageIO.read(file);
        if (img == null) throw new IllegalArgumentException("Cannot read image: " + file);
        IsinScanner.Result r = scanner.scan(img);
        if (Boolean.getBoolean("isinsnap.debug")) System.out.println(r.ocrText());
        for (Match m : r.isins()) {
            int n = r.occurrences(m);
            System.out.printf("%-12s  %-9s  x%-2d  read: %s%s%n", m.isin(), m.kind(), n, m.source(),
                m.alternatives().isEmpty() ? "" : "  variants: " + m.alternatives());
        }
        int dup = r.totalMentions() - r.isins().size();
        System.out.printf("# %d unique ISINs (%d duplicates merged) in %d ms%n", r.isins().size(), dup, r.millis());
    }

    static String missingTesseractMessage() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String how = os.contains("win")
            ? "Windows: install Tesseract (UB Mannheim build) or copy a \"tesseract\" folder\n"
              + "(with tesseract.exe and tessdata) next to isin-snap.jar."
            : os.contains("mac")
            ? "macOS: brew install tesseract"
            : "Linux: sudo apt install tesseract-ocr";
        return "Cannot find the Tesseract OCR executable.\n\n" + how
            + "\n\nAlternatively, set the path with -Dtesseract.path=... or the TESSERACT_PATH variable.";
    }

    // ───────────────────────────── UI ─────────────────────────────

    private void start() {
        launcher = new JFrame("ISIN Snap");
        launcher.setUndecorated(true);
        launcher.setAlwaysOnTop(true);
        launcher.setType(Window.Type.UTILITY); // no taskbar icon on Windows
        launcher.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        button = new JButton("Scan ISIN");
        button.setFont(button.getFont().deriveFont(Font.BOLD, 13f));
        button.setFocusPainted(false);
        button.setToolTipText("Select a screen area and recognize the ISINs · right-click for the menu");
        button.addActionListener(e -> scan());

        JPanel grip = new JPanel();
        grip.setPreferredSize(new java.awt.Dimension(12, 10));
        grip.setBackground(new Color(0x3d, 0x8b, 0xfd));
        grip.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        grip.setToolTipText("Drag to move");

        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createLineBorder(new Color(0x3d, 0x8b, 0xfd), 1));
        root.add(grip, BorderLayout.WEST);
        root.add(button, BorderLayout.CENTER);
        launcher.setContentPane(root);

        // drag by the handle
        MouseAdapter drag = new MouseAdapter() {
            Point offset;
            @Override public void mousePressed(MouseEvent e) { offset = e.getPoint(); }
            @Override public void mouseDragged(MouseEvent e) {
                Point p = e.getLocationOnScreen();
                launcher.setLocation(p.x - offset.x, p.y - offset.y);
            }
        };
        grip.addMouseListener(drag);
        grip.addMouseMotionListener(drag);

        // right-click menu
        JPopupMenu menu = new JPopupMenu();
        JMenuItem quit = new JMenuItem("Quit");
        quit.addActionListener(e -> System.exit(0));
        menu.add(quit);
        button.setComponentPopupMenu(menu);
        grip.setComponentPopupMenu(menu);

        launcher.pack();
        placeBottomRight(launcher);
        launcher.setVisible(true);

        installTray();
    }

    private static void placeBottomRight(Window w) {
        var gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        Rectangle b = gc.getBounds();
        Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        w.setLocation(b.x + b.width - in.right - w.getWidth() - 24, b.y + b.height - in.bottom - w.getHeight() - 24);
    }

    private void installTray() {
        if (!SystemTray.isSupported()) return;
        PopupMenu menu = new PopupMenu();
        MenuItem scan = new MenuItem("Scan area…");
        scan.addActionListener(e -> SwingUtilities.invokeLater(this::scan));
        MenuItem toggle = new MenuItem("Show/hide button");
        toggle.addActionListener(e -> SwingUtilities.invokeLater(() -> launcher.setVisible(!launcher.isVisible())));
        MenuItem quit = new MenuItem("Quit");
        quit.addActionListener(e -> System.exit(0));
        menu.add(scan);
        menu.add(toggle);
        menu.addSeparator();
        menu.add(quit);
        TrayIcon icon = new TrayIcon(trayImage(), "ISIN Snap", menu);
        icon.setImageAutoSize(true);
        icon.addActionListener(e -> SwingUtilities.invokeLater(this::scan));
        try {
            SystemTray.getSystemTray().add(icon);
        } catch (AWTException ignored) { }
    }

    private static BufferedImage trayImage() {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x3d, 0x8b, 0xfd));
        g.fillRoundRect(0, 0, 32, 32, 8, 8);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        g.drawString("ISIN", 2, 21);
        g.dispose();
        return img;
    }

    /** Hides the button, waits until it is gone from the screen, then opens the selection. */
    private void scan() {
        if (busy) return;
        busy = true;
        boolean wasVisible = launcher.isVisible();
        launcher.setVisible(false);
        Timer t = new Timer(250, e -> RegionSelector.select(
            img -> runOcr(img, wasVisible),
            () -> { busy = false; launcher.setVisible(wasVisible); }));
        t.setRepeats(false);
        t.start();
    }

    private void runOcr(BufferedImage img, boolean showLauncher) {
        button.setText("OCR…");
        button.setEnabled(false);
        launcher.setVisible(showLauncher);
        new SwingWorker<IsinScanner.Result, Void>() {
            @Override protected IsinScanner.Result doInBackground() throws Exception { return scanner.scan(img); }
            @Override protected void done() {
                busy = false;
                button.setText("Scan ISIN");
                button.setEnabled(true);
                try {
                    ResultWindow w = new ResultWindow(get(), IsinSnap.this::scan);
                    w.setVisible(true);
                    w.toFront();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    JOptionPane.showMessageDialog(launcher, "OCR error:\n" + cause.getMessage(),
                        "ISIN Snap", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }
}
