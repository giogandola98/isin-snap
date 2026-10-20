package isinsnap;

import isinsnap.IsinExtractor.Kind;
import isinsnap.IsinExtractor.Match;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Shows the ISINs found: one row per distinct ISIN (duplicates merged, with a count), a checkbox to choose
 * what to copy, a confidence status, plus the captured image and the raw OCR text.
 */
public final class ResultWindow extends JFrame {

    private static final Color OK = new Color(0x2e, 0x7d, 0x32);
    private static final Color WARN = new Color(0xb2, 0x6a, 0x00);
    private static final Color BAD = new Color(0xc6, 0x28, 0x28);
    private static final Color MUTED = new Color(0x80, 0x80, 0x80);

    /**
     * ISINs already copied during this session (cleared when the app restarts): they show up as
     * "Already copied" and are not pre-checked, so the same ISIN is not pasted twice across scans.
     */
    private static final Set<String> COPIED_THIS_SESSION = ConcurrentHashMap.newKeySet();

    /** One table row: the chosen ISIN (for ambiguous ones the user can pick another variant). */
    private static final class Row {
        final Match match;
        final int count;
        final boolean alreadyCopied;
        boolean selected;
        String chosen;
        Row(Match m, int count) {
            match = m;
            this.count = count;
            chosen = m.isin();
            alreadyCopied = COPIED_THIS_SESSION.contains(m.isin());
            selected = m.kind().confident() && !alreadyCopied;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final JLabel status = new JLabel(" ");

    public ResultWindow(IsinScanner.Result result, Runnable newScan) {
        super("ISIN Snap – " + result.isins().size() + " ISINs");
        for (Match m : result.isins()) rows.add(new Row(m, result.occurrences(m)));

        Model model = new Model();
        JTable table = new JTable(model);
        table.setRowHeight(26);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(1).setPreferredWidth(150);
        table.getColumnModel().getColumn(2).setMaxWidth(60);
        table.getColumnModel().getColumn(3).setPreferredWidth(170);
        table.getColumnModel().getColumn(4).setPreferredWidth(150);
        table.getColumnModel().getColumn(1).setCellRenderer(new IsinRenderer());
        table.getColumnModel().getColumn(2).setCellRenderer(new CountRenderer());
        table.getColumnModel().getColumn(3).setCellRenderer(new StatusRenderer());
        table.setToolTipText("Double-click a row to copy that ISIN");
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int r = table.rowAtPoint(e.getPoint());
                    if (r >= 0 && table.columnAtPoint(e.getPoint()) != 0) copy(List.of(rows.get(r).chosen));
                }
            }
        });
        // Drop-down editor to pick the right variant of an ambiguous ISIN
        table.getColumnModel().getColumn(1).setCellEditor(new javax.swing.DefaultCellEditor(new JComboBox<String>()) {
            @Override public Component getTableCellEditorComponent(JTable t, Object v, boolean s, int row, int col) {
                @SuppressWarnings("unchecked")
                JComboBox<String> cb = (JComboBox<String>) getComponent();
                cb.removeAllItems();
                for (String alt : rows.get(row).match.alternatives()) cb.addItem(alt);
                cb.setSelectedItem(v);
                return cb;
            }
        });

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("ISINs (" + rows.size() + ")", new JScrollPane(table));
        tabs.addTab("Image", imagePanel(result));
        JTextArea raw = new JTextArea(result.ocrText());
        raw.setEditable(false);
        raw.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        tabs.addTab("OCR text", new JScrollPane(raw));

        JButton copySel = new JButton("Copy checked");
        copySel.addActionListener(e -> copy(rows.stream().filter(r -> r.selected).map(r -> r.chosen).toList()));
        JButton copyAll = new JButton("Copy all");
        copyAll.addActionListener(e -> copy(rows.stream().map(r -> r.chosen).toList()));
        JButton again = new JButton("New scan");
        again.addActionListener(e -> { dispose(); newScan.run(); });
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(copySel);
        buttons.add(copyAll);
        buttons.add(again);
        buttons.add(close);

        JLabel header = new JLabel(summary(result));
        header.setBorder(BorderFactory.createEmptyBorder(8, 10, 4, 10));

        JPanel south = new JPanel(new BorderLayout());
        status.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
        south.add(status, BorderLayout.WEST);
        south.add(buttons, BorderLayout.EAST);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(header, BorderLayout.NORTH);
        getContentPane().add(tabs, BorderLayout.CENTER);
        getContentPane().add(south, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(copySel);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(760, 460);
        setLocationRelativeTo(null);
    }

    private String summary(IsinScanner.Result result) {
        if (rows.isEmpty()) return "No ISINs found. Try a tighter selection or zoom in on the chat.";
        long confident = rows.stream().filter(r -> r.match.kind().confident()).count();
        long toCheck = rows.size() - confident;
        long already = rows.stream().filter(r -> r.alreadyCopied).count();
        int duplicates = result.totalMentions() - rows.size();
        List<String> parts = new ArrayList<>();
        parts.add(rows.size() + " unique ISINs");
        if (duplicates > 0) parts.add(duplicates + (duplicates == 1 ? " duplicate" : " duplicates") + " merged");
        parts.add(confident + " confident");
        if (toCheck > 0) parts.add(toCheck + " to review (unchecked)");
        if (already > 0) parts.add(already + " already copied (unchecked)");
        parts.add(result.millis() + " ms");
        return String.join("  ·  ", parts);
    }

    private JScrollPane imagePanel(IsinScanner.Result result) {
        Image img = result.image();
        int w = result.image().getWidth(), h = result.image().getHeight();
        if (w > 1400) img = result.image().getScaledInstance(1400, (int) (h * 1400.0 / w), Image.SCALE_SMOOTH);
        JLabel l = new JLabel(new ImageIcon(img));
        l.setVerticalAlignment(SwingConstants.TOP);
        l.setHorizontalAlignment(SwingConstants.LEFT);
        return new JScrollPane(l);
    }

    /** Copies the ISINs (de-duplicated, order preserved) to the clipboard, one per line. */
    private void copy(List<String> isins) {
        Set<String> unique = new LinkedHashSet<>(isins);
        if (unique.isEmpty()) { status.setText("Nothing to copy"); return; }
        String text = unique.stream().collect(Collectors.joining(System.lineSeparator()));
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        COPIED_THIS_SESSION.addAll(unique);
        status.setText("Copied " + unique.size() + (unique.size() == 1 ? " ISIN" : " ISINs") + " to the clipboard");
    }

    private static String describe(Row r) {
        Match m = r.match;
        String s = switch (m.kind()) {
            case EXACT -> "Read";
            case CORRECTED -> "Corrected (safe fix)";
            case SUGGESTED -> "Corrected: please check";
            case AMBIGUOUS -> m.alternatives().size() + " variants: choose";
        };
        return r.alreadyCopied ? s + " · already copied" : s;
    }

    private final class Model extends AbstractTableModel {
        private final String[] cols = { "", "ISIN", "Count", "Status", "OCR read" };
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int r, int c) {
            return c == 0 || (c == 1 && rows.get(r).match.kind() == Kind.AMBIGUOUS);
        }
        @Override public Object getValueAt(int r, int c) {
            Row row = rows.get(r);
            return switch (c) {
                case 0 -> row.selected;
                case 1 -> row.chosen;
                case 2 -> "×" + row.count;
                case 3 -> describe(row);
                default -> row.match.source();
            };
        }
        @Override public void setValueAt(Object v, int r, int c) {
            Row row = rows.get(r);
            if (c == 0) row.selected = (Boolean) v;
            if (c == 1 && v != null) { row.chosen = v.toString(); row.selected = true; }
            fireTableRowsUpdated(r, r);
        }
    }

    private final class IsinRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, f, r, c);
            setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
            if (rows.get(r).match.kind() == Kind.AMBIGUOUS) setText(v + "  ▾");
            if (!sel) setForeground(rows.get(r).alreadyCopied ? MUTED : t.getForeground());
            return this;
        }
    }

    private final class CountRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, f, r, c);
            setHorizontalAlignment(SwingConstants.CENTER);
            boolean dup = rows.get(r).count > 1;
            setFont(getFont().deriveFont(dup ? Font.BOLD : Font.PLAIN));
            if (!sel) setForeground(dup ? t.getForeground() : MUTED);
            setToolTipText(dup ? "Appears " + rows.get(r).count + " times in the capture, shown once" : null);
            return this;
        }
    }

    private final class StatusRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, f, r, c);
            Row row = rows.get(r);
            Kind k = row.match.kind();
            if (!sel) setForeground(row.alreadyCopied ? MUTED : k.confident() ? OK : k == Kind.SUGGESTED ? WARN : BAD);
            return this;
        }
    }
}
