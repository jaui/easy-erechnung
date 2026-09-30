package de.aronhomberg;

import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.prefs.Preferences;

/**
 * Simple window for the two flows "Excel → e-invoice" and "Excel + existing PDF → e-invoice".
 * All work runs in background threads; results show one traffic light per active validator.
 */
public final class ERechnungApp {
    private static final Preferences PREFS = Preferences.userNodeForPackage(ERechnungApp.class).node("app");
    private static final Color GREEN = new Color(0x2E7D32), AMBER = new Color(0xE0A000), RED = new Color(0xC62828), GREY = new Color(0x9E9E9E);

    private final JFrame frame = new JFrame("easy-e-rechnung – E-Rechnungen erstellen");
    private final JTextField excelField = new JTextField();
    private final JTextField outField = new JTextField();
    private final JCheckBox xrechnungBox = new JCheckBox("XRechnung-XML zusätzlich");
    private final JCheckBox mustangBox = new JCheckBox("Mustang");
    private final JCheckBox kositBox = new JCheckBox("KoSIT");
    private final JCheckBox verapdfBox = new JCheckBox("veraPDF");
    private final SheetModel excelSheets = new SheetModel(false);
    private final SheetModel pdfSheets = new SheetModel(true);
    private final ResultModel results = new ResultModel();
    private final JTable resultTable = new JTable(results);
    private final JTextArea log = new JTextArea(7, 80);
    private final List<JComponent> busyDisabled = new ArrayList<>();

    private final JButton kositDownload = linkButton("herunterladen …", e -> downloadTool(ToolDownloader.Tool.KOSIT));
    private final JButton verapdfDownload = linkButton("herunterladen …", e -> downloadTool(ToolDownloader.Tool.VERAPDF));

    public static void main(String[] args) {
        // macOS: menu in the screen menu bar and proper app name (ignored on other systems); must precede any AWT use
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", "easy-e-rechnung");
        FlatLightLaf.setup();
        SwingUtilities.invokeLater(() -> new ERechnungApp().show());
    }

    /**
     * Default output folder: {@code rechnungen.out} when started from the repository, otherwise the user's
     * documents folder – never the working directory of an installed app (often "/" or a protected folder).
     */
    static Path defaultOutDir(Path workingDir, Path home) {
        if (Files.isRegularFile(workingDir.resolve("build.gradle.kts"))) return workingDir.resolve("rechnungen.out");
        for (String docs : List.of("Documents", "Dokumente")) {
            if (Files.isDirectory(home.resolve(docs))) return home.resolve(docs).resolve("E-Rechnungen");
        }
        return home.resolve("E-Rechnungen");
    }

    private void show() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setJMenuBar(menu());
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));
        root.add(settingsPanel(), BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Excel → Rechnung", excelTab());
        tabs.addTab("Excel + PDF → Rechnung", pdfTab());

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, resultPanel());
        split.setResizeWeight(0.5);
        root.add(split, BorderLayout.CENTER);
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        root.add(new JScrollPane(log), BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.setTransferHandler(new FileDrop());
        frame.setSize(1100, 820);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        if (!excelField.getText().isBlank()) loadExcel();
    }

    // ---------------------------------------------------------------- layout

    private JMenuBar menu() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("Datei");
        JMenuItem template = new JMenuItem("Excel-Vorlage speichern …");
        template.addActionListener(e -> saveTemplate());
        JMenuItem exit = new JMenuItem("Beenden");
        exit.addActionListener(e -> System.exit(0));
        file.add(template);
        file.addSeparator();
        file.add(exit);
        JMenu extras = new JMenu("Extras");
        JMenuItem tools = new JMenuItem("Prüfprogramme (KoSIT, veraPDF) …");
        tools.addActionListener(e -> toolsDialog());
        JMenuItem old = new JMenuItem("Alte OCR-Oberfläche starten");
        old.addActionListener(e -> startOldApp());
        extras.add(tools);
        extras.addSeparator();
        extras.add(old);
        bar.add(file);
        bar.add(extras);
        return bar;
    }

    private JPanel settingsPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.fill = GridBagConstraints.HORIZONTAL;

        excelField.setText(PREFS.get("excel", ""));
        excelField.setEditable(false);
        outField.setText(PREFS.get("out", defaultOutDir(Path.of(System.getProperty("user.dir")).toAbsolutePath(),
                Path.of(System.getProperty("user.home"))).toString()));
        outField.setEditable(false);

        JButton chooseExcel = button("Excel wählen …", e -> chooseExcel());
        JButton reload = button("Neu laden", e -> loadExcel());
        reload.setToolTipText("Änderungen aus Excel übernehmen (Datei vorher in Excel speichern)");
        JButton chooseOut = button("Ordner …", e -> chooseOut());

        row(p, c, 0, new JLabel("Excel-Datei:"), excelField, chooseExcel, reload);
        row(p, c, 1, new JLabel("Ausgabeordner:"), outField, chooseOut, null);

        xrechnungBox.setSelected(PREFS.getBoolean("xrechnung", true));
        xrechnungBox.setToolTipText("Zusätzliche XRechnung (CII) für Rechnungen mit Käuferreferenz (BT-10), z. B. für Behörden");
        mustangBox.setSelected(PREFS.getBoolean("mustang", true));
        kositBox.setSelected(PREFS.getBoolean("kosit", false));
        verapdfBox.setSelected(PREFS.getBoolean("verapdf", false));
        mustangBox.setToolTipText("XML-Schema, EN16931-Regeln und PDF/A (im Programm enthalten)");
        refreshTools();
        for (JCheckBox b : List.of(xrechnungBox, mustangBox, kositBox, verapdfBox)) {
            b.addActionListener(e -> savePrefs());
        }
        JPanel opts = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        opts.add(xrechnungBox);
        opts.add(new JLabel("   Prüfen mit:"));
        opts.add(mustangBox);
        opts.add(kositBox);
        opts.add(kositDownload);
        opts.add(verapdfBox);
        opts.add(verapdfDownload);
        c.gridx = 1;
        c.gridy = 2;
        c.gridwidth = 3;
        p.add(opts, c);
        return p;
    }

    private JPanel excelTab() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(new EmptyBorder(8, 8, 8, 8));
        p.add(hint("Die sichtbare Rechnung wird aus den Excel-Daten erstellt (eigenes Layout)."), BorderLayout.NORTH);
        p.add(new JScrollPane(sheetTable(excelSheets)), BorderLayout.CENTER);
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.add(button("Rechnungen erzeugen", e -> generate(false)));
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    private JPanel pdfTab() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(new EmptyBorder(8, 8, 8, 8));
        p.add(hint("Das vorhandene PDF bleibt optisch erhalten; die Rechnungsdaten kommen aus dem Excel. "
                + "PDF per Doppelklick, Drag & Drop auf die Zeile oder automatisch aus einem Ordner zuordnen."), BorderLayout.NORTH);
        JTable table = sheetTable(pdfSheets);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                if (e.getClickCount() == 2 && row >= 0) choosePdf(table.convertRowIndexToModel(row));
            }
        });
        table.setTransferHandler(new PdfRowDrop(table));
        p.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.add(button("PDF-Ordner wählen (automatisch zuordnen) …", e -> autoAssign()));
        south.add(button("Zuordnung entfernen", e -> {
            int r = table.getSelectedRow();
            if (r >= 0) pdfSheets.setPdf(table.convertRowIndexToModel(r), null);
        }));
        south.add(button("Rechnungen erzeugen", e -> generate(true)));
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    private JPanel resultPanel() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createTitledBorder("Ergebnis"));
        resultTable.setRowHeight(24);
        resultTable.setDefaultRenderer(Validators.Check.class, new CheckRenderer());
        resultTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) openSelected("pdf");
            }
        });
        p.add(new JScrollPane(resultTable), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(button("PDF öffnen", e -> openSelected("pdf")));
        buttons.add(button("XML öffnen", e -> openSelected("xml")));
        buttons.add(button("Ordner öffnen", e -> openSelected("dir")));
        buttons.add(button("Prüfbericht öffnen", e -> openSelected("checks")));
        p.add(buttons, BorderLayout.SOUTH);
        return p;
    }

    private JTable sheetTable(SheetModel model) {
        JTable t = new JTable(model);
        t.setRowHeight(24);
        t.setAutoCreateRowSorter(true);
        t.getColumnModel().getColumn(0).setMaxWidth(40);
        t.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean sel, boolean focus, int row, int col) {
                Component c = super.getTableCellRendererComponent(table, value, sel, focus, row, col);
                SheetRow r = model.rows.get(table.convertRowIndexToModel(row));
                if (!sel) c.setForeground(r.error != null ? RED : Color.BLACK);
                setToolTipText(r.error != null ? r.error : value == null ? null : value.toString());
                return c;
            }
        });
        return t;
    }

    // ---------------------------------------------------------------- actions

    private void chooseExcel() {
        JFileChooser fc = new JFileChooser(excelField.getText().isBlank() ? null : new File(excelField.getText()).getParentFile());
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Excel-Arbeitsmappe (*.xlsx)", "xlsx"));
        if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            excelField.setText(fc.getSelectedFile().getAbsolutePath());
            savePrefs();
            loadExcel();
        }
    }

    private void chooseOut() {
        JFileChooser fc = new JFileChooser(outField.getText());
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            outField.setText(fc.getSelectedFile().getAbsolutePath());
            savePrefs();
        }
    }

    private void loadExcel() {
        String path = excelField.getText();
        if (path.isBlank()) return;
        Map<String, Path> keepPdfs = pdfSheets.assignments();
        runInBackground("Excel lesen", () -> {
            List<ExcelInvoiceReader.ExcelInvoice> list = ExcelInvoiceReader.read(Path.of(path));
            SwingUtilities.invokeLater(() -> {
                excelSheets.set(list, Map.of());
                pdfSheets.set(list, keepPdfs);
                long bad = list.stream().filter(i -> i.error() != null).count();
                log(list.size() + " Rechnungsblätter gelesen" + (bad > 0 ? ", davon " + bad + " mit Fehlern (rot)" : "") + ": " + path);
            });
        });
    }

    private void autoAssign() {
        JFileChooser fc = new JFileChooser(PREFS.get("pdfDir", null));
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        Path dir = fc.getSelectedFile().toPath();
        PREFS.put("pdfDir", dir.toString());
        List<Path> pdfs = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, "*.{pdf,PDF}")) {
            s.forEach(pdfs::add);
        } catch (IOException ex) {
            error("PDF-Ordner lesen", ex);
            return;
        }
        Map<String, InvoiceResponse.Invoice> sheets = new LinkedHashMap<>();
        for (SheetRow r : pdfSheets.rows) if (r.data != null && r.pdf == null) sheets.put(r.sheet, r.data);
        Map<String, Path> suggestion = PdfMatcher.suggest(sheets, pdfs);
        suggestion.forEach(pdfSheets::setPdf);
        log(suggestion.size() + " von " + sheets.size() + " offenen Blättern automatisch zugeordnet (" + pdfs.size() + " PDFs in " + dir + ")");
    }

    private void choosePdf(int modelRow) {
        JFileChooser fc = new JFileChooser(PREFS.get("pdfDir", null));
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("PDF (*.pdf)", "pdf"));
        if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            PREFS.put("pdfDir", fc.getSelectedFile().getParent());
            pdfSheets.setPdf(modelRow, fc.getSelectedFile().toPath());
        }
    }

    private void generate(boolean withPdf) {
        SheetModel model = withPdf ? pdfSheets : excelSheets;
        List<SheetRow> todo = new ArrayList<>();
        for (SheetRow r : model.rows) if (r.selected && r.data != null && (!withPdf || r.pdf != null)) todo.add(r);
        if (todo.isEmpty()) {
            JOptionPane.showMessageDialog(frame, withPdf
                    ? "Keine ausgewählte Zeile mit zugeordnetem PDF." : "Keine ausgewählten Rechnungsblätter.");
            return;
        }
        Validators.Settings vs = new Validators.Settings(mustangBox.isSelected(), kositBox.isSelected() && kositBox.isEnabled(),
                verapdfBox.isSelected() && verapdfBox.isEnabled());
        ERechnungService.Options opt = new ERechnungService.Options(xrechnungBox.isSelected(), vs);
        Path out = Path.of(outField.getText());
        String excel = excelField.getText();
        results.start(vs);
        runInBackground("Rechnungen erzeugen", () -> {
            // re-read the workbook so that the data used is exactly what is saved on disk right now
            Map<String, ExcelInvoiceReader.ExcelInvoice> fresh = new LinkedHashMap<>();
            for (ExcelInvoiceReader.ExcelInvoice ei : ExcelInvoiceReader.read(Path.of(excel))) fresh.put(ei.sheet(), ei);
            for (SheetRow r : todo) {
                ExcelInvoiceReader.ExcelInvoice ei = fresh.get(r.sheet);
                String way = withPdf ? "Excel + " + r.pdf.getFileName() : "Excel";
                try {
                    if (ei == null) throw new IllegalArgumentException("Blatt „" + r.sheet + "“ nicht mehr in der Excel-Datei");
                    if (ei.error() != null) throw new IllegalArgumentException(ei.error());
                    ERechnungService.Progress progress = msg -> SwingUtilities.invokeLater(() -> log(msg));
                    ERechnungService.Result res = withPdf
                            ? ERechnungService.fromPdf(ei.data(), r.pdf, out, opt, progress)
                            : ERechnungService.fromData(ei.data(), out, opt, progress);
                    SwingUtilities.invokeLater(() -> {
                        results.add(r.sheet, way, res, null);
                        res.warnings().forEach(w -> log("  Hinweis " + res.name() + ": " + w));
                        log((res.ok() ? "  fertig: " : "  FEHLER bei Prüfung: ") + res.pdf());
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        results.add(r.sheet, way, null, ex.getMessage());
                        log("  FEHLER Blatt " + r.sheet + ": " + ex.getMessage());
                    });
                }
            }
        });
    }

    private void openSelected(String what) {
        int row = resultTable.getSelectedRow();
        if (row < 0) return;
        ResultRow r = results.rows.get(resultTable.convertRowIndexToModel(row));
        if (r.result == null) return;
        Path target = switch (what) {
            case "pdf" -> r.result.pdf();
            case "xml" -> r.result.facturX();
            case "checks" -> r.result.dir().resolve(OutputLayout.CHECKS).resolve("zusammenfassung.txt");
            default -> r.result.dir();
        };
        try {
            Desktop.getDesktop().open(target.toFile());
        } catch (Exception ex) {
            error("Öffnen", ex);
        }
    }

    /** Enables KoSIT/veraPDF only when installed; the "herunterladen …" link is shown for missing tools. */
    private void refreshTools() {
        Validators.ToolLocation kosit = Validators.kosit(), vera = Validators.verapdf();
        tool(kositBox, kosit, "KoSIT-Prüftool (EN16931 / XRechnung)", Validators.KOSIT);
        tool(verapdfBox, vera, "veraPDF (PDF/A-3b)", Validators.VERAPDF);
        kositDownload.setVisible(kosit == null);
        verapdfDownload.setVisible(vera == null);
    }

    /** Asks for confirmation (source, target folder, size, license), then downloads in the background. */
    private void downloadTool(ToolDownloader.Tool tool) {
        Path target = AppDirs.downloadTarget();
        Path program = AppDirs.programTools();
        String where = target.equals(program) ? "Programmordner"
                : "Benutzerordner" + (program != null ? " (der Programmordner ist schreibgeschützt)" : "");
        String msg = "<html><b>" + tool.title + "</b> aus der offiziellen Quelle laden?<br><br>"
                + "Quelle: " + tool.source() + "<br>"
                + "Ziel: " + target.resolve(tool.folder) + " – " + where + "<br>"
                + "Größe: ca. " + tool.approxMb + " MB<br>"
                + "Lizenz: " + tool.license + "<br><br>"
                + "Die Dateien werden per HTTPS geladen und, soweit angeboten, per SHA-256 geprüft.<br>"
                + "Sonst greift das Programm nicht auf das Internet zu.</html>";
        if (JOptionPane.showConfirmDialog(frame, msg, "Prüfprogramm herunterladen", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) return;
        log("Download " + tool.title + " nach " + target + " …");
        runInBackground("Download " + tool.title, () -> {
            Path dir = ToolDownloader.install(tool, target, msg1 -> SwingUtilities.invokeLater(() -> log(msg1)));
            SwingUtilities.invokeLater(() -> {
                refreshTools();
                JCheckBox box = tool == ToolDownloader.Tool.KOSIT ? kositBox : verapdfBox;
                if (box.isEnabled()) box.setSelected(true);
                savePrefs();
                log(tool.title + " installiert: " + dir + " (" + ToolDownloader.installedVersion(dir) + ")");
            });
        });
    }

    /** Where the validators are installed (and which version), with download/update actions. */
    private void toolsDialog() {
        StringBuilder sb = new StringBuilder("<html><b>Mustang</b>: im Programm enthalten<br><br>");
        for (ToolDownloader.Tool t : List.of(ToolDownloader.Tool.KOSIT, ToolDownloader.Tool.VERAPDF)) {
            Validators.ToolLocation loc = t == ToolDownloader.Tool.KOSIT ? Validators.kosit() : Validators.verapdf();
            sb.append("<b>").append(t.title).append("</b>: ");
            if (loc == null) sb.append("nicht installiert");
            else sb.append(ToolDownloader.installedVersion(loc.dir())).append("<br>&nbsp;&nbsp;").append(loc.dir())
                    .append(" (").append(loc.origin()).append(")");
            sb.append("<br><br>");
        }
        sb.append("Download-Ziel: ").append(AppDirs.downloadTarget()).append("<br>")
          .append("„Laden / aktualisieren“ holt die jeweils neueste Version aus der offiziellen Quelle.</html>");
        Object[] options = {"KoSIT laden / aktualisieren", "veraPDF laden / aktualisieren", "Schließen"};
        int choice = JOptionPane.showOptionDialog(frame, sb.toString(), "Prüfprogramme", JOptionPane.DEFAULT_OPTION,
                JOptionPane.INFORMATION_MESSAGE, null, options, options[2]);
        if (choice == 0) downloadTool(ToolDownloader.Tool.KOSIT);
        if (choice == 1) downloadTool(ToolDownloader.Tool.VERAPDF);
    }

    private JButton linkButton(String text, java.awt.event.ActionListener action) {
        JButton b = button(text, action);
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setForeground(new Color(0x1565C0));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setToolTipText("Aus der offiziellen Quelle laden (mit Rückfrage)");
        return b;
    }

    private void saveTemplate() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("rechnungen-vorlage.xlsx"));
        if (fc.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        Path target = fc.getSelectedFile().toPath();
        runInBackground("Vorlage speichern", () -> {
            ExcelTemplateWriter.main(new String[]{target.toString()});
            SwingUtilities.invokeLater(() -> log("Vorlage gespeichert: " + target));
        });
    }

    /** The old window uses EXIT_ON_CLOSE, so it runs in its own JVM and cannot close this one. */
    private void startOldApp() {
        try {
            new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Dfile.encoding=UTF-8", "-cp", System.getProperty("java.class.path"), "de.aronhomberg.Main")
                    .inheritIO().start();
            log("Alte OCR-Oberfläche gestartet");
        } catch (IOException ex) {
            error("Alte Oberfläche starten", ex);
        }
    }

    // ---------------------------------------------------------------- helpers

    private interface Work {
        void run() throws Exception;
    }

    private void runInBackground(String title, Work work) {
        busyDisabled.forEach(b -> b.setEnabled(false));
        frame.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                work.run();
                return null;
            }

            @Override
            protected void done() {
                busyDisabled.forEach(b -> b.setEnabled(true));
                frame.setCursor(Cursor.getDefaultCursor());
                try {
                    get();
                } catch (Exception ex) {
                    error(title, ex.getCause() != null ? ex.getCause() : ex);
                }
            }
        }.execute();
    }

    private void error(String title, Throwable ex) {
        log("FEHLER (" + title + "): " + ex.getMessage());
        JOptionPane.showMessageDialog(frame, ex.getMessage(), title, JOptionPane.ERROR_MESSAGE);
    }

    private void log(String line) {
        log.append(line + "\n");
        log.setCaretPosition(log.getDocument().getLength());
    }

    private void savePrefs() {
        PREFS.put("excel", excelField.getText());
        PREFS.put("out", outField.getText());
        PREFS.putBoolean("xrechnung", xrechnungBox.isSelected());
        PREFS.putBoolean("mustang", mustangBox.isSelected());
        PREFS.putBoolean("kosit", kositBox.isSelected());
        PREFS.putBoolean("verapdf", verapdfBox.isSelected());
    }

    private JButton button(String text, java.awt.event.ActionListener action) {
        JButton b = new JButton(text);
        b.addActionListener(action);
        busyDisabled.add(b);
        return b;
    }

    /** Enabled only if the tool is installed; otherwise greyed out with a hint that it can be downloaded. */
    private static void tool(JCheckBox box, Validators.ToolLocation loc, String what, String toolFolder) {
        boolean installed = loc != null;
        box.setEnabled(installed);
        if (!installed) box.setSelected(false);
        box.setToolTipText(installed
                ? "<html>" + what + "<br>" + loc.dir() + " (" + loc.origin() + ")</html>"
                : "<html>" + what + " ist nicht installiert – über „herunterladen …“ daneben laden.<br>Gesucht in:"
                  + Validators.searchedFolders(toolFolder).replace("\n", "<br>") + "</html>");
    }

    private static JLabel hint(String text) {
        JLabel l = new JLabel("<html>" + text + "</html>");
        l.setForeground(Color.DARK_GRAY);
        return l;
    }

    private static void row(JPanel p, GridBagConstraints c, int y, JComponent label, JComponent field, JComponent b1, JComponent b2) {
        c.gridy = y;
        c.gridwidth = 1;
        c.gridx = 0;
        c.weightx = 0;
        p.add(label, c);
        c.gridx = 1;
        c.weightx = 1;
        p.add(field, c);
        c.weightx = 0;
        c.gridx = 2;
        p.add(b1, c);
        if (b2 != null) {
            c.gridx = 3;
            p.add(b2, c);
        }
    }

    private static String money(double v) {
        NumberFormat nf = NumberFormat.getCurrencyInstance(Locale.GERMANY);
        return nf.format(BigDecimal.valueOf(v));
    }

    // ---------------------------------------------------------------- drag & drop

    /** Dropping an .xlsx anywhere on the window loads it. */
    private final class FileDrop extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport s) {
            return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean importData(TransferSupport s) {
            try {
                for (File f : (List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor)) {
                    if (f.getName().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
                        excelField.setText(f.getAbsolutePath());
                        savePrefs();
                        loadExcel();
                        return true;
                    }
                }
            } catch (Exception ex) {
                error("Datei ablegen", ex);
            }
            return false;
        }
    }

    /** Dropping a PDF on a row of the "Excel + PDF" table assigns it to that sheet. */
    private final class PdfRowDrop extends TransferHandler {
        private final JTable table;

        PdfRowDrop(JTable table) {
            this.table = table;
        }

        @Override
        public boolean canImport(TransferSupport s) {
            return s.isDrop() && s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean importData(TransferSupport s) {
            try {
                int row = table.rowAtPoint(s.getDropLocation().getDropPoint());
                if (row < 0) return false;
                for (File f : (List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor)) {
                    if (f.getName().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                        pdfSheets.setPdf(table.convertRowIndexToModel(row), f.toPath());
                        return true;
                    }
                }
            } catch (Exception ex) {
                error("PDF ablegen", ex);
            }
            return false;
        }
    }

    // ---------------------------------------------------------------- table models

    private static final class SheetRow {
        final String sheet;
        final InvoiceResponse.Invoice data;
        final String error;
        boolean selected;
        Path pdf;

        SheetRow(ExcelInvoiceReader.ExcelInvoice ei) {
            sheet = ei.sheet();
            data = ei.data();
            error = ei.error();
            selected = error == null;
        }
    }

    private static final class SheetModel extends AbstractTableModel {
        final boolean withPdf;
        final List<SheetRow> rows = new ArrayList<>();

        SheetModel(boolean withPdf) {
            this.withPdf = withPdf;
        }

        void set(List<ExcelInvoiceReader.ExcelInvoice> list, Map<String, Path> pdfs) {
            rows.clear();
            for (ExcelInvoiceReader.ExcelInvoice ei : list) {
                SheetRow r = new SheetRow(ei);
                r.pdf = pdfs.get(r.sheet);
                rows.add(r);
            }
            fireTableDataChanged();
        }

        Map<String, Path> assignments() {
            Map<String, Path> m = new LinkedHashMap<>();
            for (SheetRow r : rows) if (r.pdf != null) m.put(r.sheet, r.pdf);
            return m;
        }

        void setPdf(int row, Path pdf) {
            rows.get(row).pdf = pdf;
            fireTableRowsUpdated(row, row);
        }

        void setPdf(String sheet, Path pdf) {
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).sheet.equals(sheet)) setPdf(i, pdf);
        }

        @Override
        public int getRowCount() { return rows.size(); }

        @Override
        public int getColumnCount() { return withPdf ? 7 : 6; }

        @Override
        public String getColumnName(int c) {
            return new String[]{"", "Blatt", "Rechnungs-Nr.", "Kunde", "Betrag", "Status", "Original-PDF"}[c];
        }

        @Override
        public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : Object.class; }

        @Override
        public boolean isCellEditable(int r, int c) { return c == 0 && rows.get(r).error == null; }

        @Override
        public void setValueAt(Object v, int r, int c) {
            rows.get(r).selected = Boolean.TRUE.equals(v);
            fireTableCellUpdated(r, c);
        }

        @Override
        public Object getValueAt(int r, int c) {
            SheetRow row = rows.get(r);
            InvoiceResponse.Invoice d = row.data;
            return switch (c) {
                case 0 -> row.selected;
                case 1 -> row.sheet;
                case 2 -> d == null ? "" : d.InvoiceNumber;
                case 3 -> d == null ? "" : d.Buyer.Name;
                case 4 -> d == null ? "" : money(d.MonetarySummation.PayableAmount);
                case 5 -> row.error != null ? "Fehler: " + row.error
                        : withPdf && row.pdf == null ? "PDF fehlt (Doppelklick oder Ordner wählen)" : "bereit";
                default -> row.pdf == null ? "" : row.pdf.getFileName().toString();
            };
        }
    }

    private record ResultRow(String sheet, String way, ERechnungService.Result result, String error) {}

    /** Columns: sheet, way, one column per active validator, notes. */
    private static final class ResultModel extends AbstractTableModel {
        final List<ResultRow> rows = new ArrayList<>();
        final List<String> validators = new ArrayList<>();

        void start(Validators.Settings s) {
            rows.clear();
            validators.clear();
            if (s.mustang()) validators.add("Mustang");
            if (s.kosit()) validators.add("KoSIT");
            if (s.verapdf()) validators.add("veraPDF");
            fireTableStructureChanged();
        }

        void add(String sheet, String way, ERechnungService.Result result, String error) {
            rows.add(new ResultRow(sheet, way, result, error));
            fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
        }

        @Override
        public int getRowCount() { return rows.size(); }

        @Override
        public int getColumnCount() { return 3 + validators.size() + 1; }

        @Override
        public String getColumnName(int c) {
            if (c == 0) return "Rechnung";
            if (c == 1) return "Blatt";
            if (c == 2) return "Weg";
            if (c < 3 + validators.size()) return validators.get(c - 3);
            return "Hinweise";
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c >= 3 && c < 3 + validators.size() ? Validators.Check.class : Object.class;
        }

        @Override
        public Object getValueAt(int r, int c) {
            ResultRow row = rows.get(r);
            if (c == 0) return row.result == null ? "–" : row.result.name();
            if (c == 1) return row.sheet;
            if (c == 2) return row.way;
            if (c < 3 + validators.size()) {
                if (row.result == null) return null;
                return worst(row.result.checks(), validators.get(c - 3));
            }
            if (row.error != null) return "Fehler: " + row.error;
            List<String> notes = new ArrayList<>(row.result.warnings());
            if (row.result.xrechnung() != null) notes.add(0, "mit XRechnung");
            return String.join(" · ", notes);
        }

        /** Combined status of all checks of one validator (PDF + XML files). */
        private static Validators.Check worst(List<Validators.Check> checks, String validator) {
            Validators.Check worst = null;
            List<String> parts = new ArrayList<>();
            for (Validators.Check c : checks) {
                if (!c.validator().equals(validator)) continue;
                parts.add(c.target() + ": " + c.summary());
                if (worst == null || c.status().ordinal() > worst.status().ordinal()) worst = c;
            }
            return worst == null ? null
                    : new Validators.Check(validator, "", worst.status(), String.join("\n", parts), worst.report());
        }
    }

    /** Traffic light: coloured dot + short text, details as tooltip. */
    private static final class CheckRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean sel, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(table, "", sel, focus, row, col);
            if (!(value instanceof Validators.Check c)) {
                setIcon(null);
                setText("–");
                setToolTipText(null);
                return this;
            }
            Color color = switch (c.status()) {
                case OK -> GREEN;
                case WARN -> AMBER;
                case FAIL -> RED;
                case MISSING -> GREY;
            };
            setIcon(new Dot(color));
            setText(switch (c.status()) {
                case OK -> "gültig";
                case WARN -> "Warnungen";
                case FAIL -> "Fehler";
                case MISSING -> "nicht installiert";
            });
            setToolTipText("<html>" + c.summary().replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>") + "</html>");
            return this;
        }
    }

    private record Dot(Color color) implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillOval(x, y + 1, 12, 12);
            g2.dispose();
        }

        @Override
        public int getIconWidth() { return 16; }

        @Override
        public int getIconHeight() { return 14; }
    }
}
