package de.aronhomberg;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Writes the Excel input template (fictitious sample data) for {@link ExcelRechnungenToZugferd}.
 * Usage: gradlew excelVorlage [-PoutFile=templates/excel/rechnungen-vorlage.xlsx]
 */
public final class ExcelTemplateWriter {
    private ExcelTemplateWriter() {}

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "templates/excel/rechnungen-vorlage.xlsx");
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        try (Workbook wb = new XSSFWorkbook(); OutputStream os = Files.newOutputStream(out)) {
            Styles st = new Styles(wb);

            Sheet help = wb.createSheet("_Anleitung");
            String[] lines = {
                    "Rechnungen für easy-erechnung (ZUGFeRD / Factur-X EN16931, optional XRechnung)",
                    "",
                    "Blatt \"Setup\": eigene Daten (Name → Wert). Kleinunternehmer = ja → § 19 UStG, keine Umsatzsteuer.",
                    "Blatt \"Kunden\": ein Kunde pro Zeile; im Rechnungsblatt wird er über das Kürzel ausgewählt.",
                    "Jedes weitere Blatt = eine Rechnung (Blattname frei, z. B. 2026-09). Blätter mit \"_\" am Anfang werden ignoriert.",
                    "  Kopf: Rechnungsnummer, Rechnungsdatum, Kunde, optional Bestellnummer, Käuferreferenz (Leitweg-ID/BT-10),",
                    "        Leistungszeitraum von/bis (sonst Monat der Rechnungsnummer bzw. des Rechnungsdatums), Fälligkeit, Gesamtbetrag (Kontrolle).",
                    "  Positionen: Datum | Typ (Stunde oder Stück) | Beschreibung | Menge | Einzelpreis",
                    "",
                    "Aufruf: gradlew excelRechnungen -Pexcel=<datei.xlsx> [-Psheet=<Blatt>]  →  rechnungen.out/excel/<Rechnung>/",
                    "Mit Käuferreferenz wird zusätzlich eine XRechnung (CII) erzeugt; dafür sind E-Mail und Telefon im Setup Pflicht.",
                    "Fehlende Pflichtangaben führen zu einer Fehlermeldung mit Blatt und Zeile – es wird nichts ergänzt oder geraten.",
            };
            for (int i = 0; i < lines.length; i++) help.createRow(i).createCell(0).setCellValue(lines[i]);
            help.getRow(0).getCell(0).setCellStyle(st.bold);
            help.setColumnWidth(0, 140 * 256);

            Sheet setup = wb.createSheet(ExcelInvoiceReader.SETUP);
            Object[][] setupRows = {
                    {"Name", "Wert"},
                    {"Name", "Max Mustermann"},
                    {"Straße", "Musterweg 1"},
                    {"PLZ", "01067"},
                    {"Ort", "Dresden"},
                    {"Land", "DE"},
                    {"Steuernummer", "201/123/45678"},
                    {"USt-IdNr", ""},
                    {"E-Mail", "max@example.org"},
                    {"Telefon", "+49 351 000000"},
                    {"IBAN", "DE02120300000000202051"},
                    {"BIC", "BYLADEM1001"},
                    {"Kontoinhaber", "Max Mustermann"},
                    {"Kleinunternehmer", "ja"},
                    {"Umsatzsteuer %", 19},
                    {"Zahlungsziel Tage", 14},
                    {"Befreiungsgrund", "Kleinunternehmer gemäß § 19 UStG"},
            };
            table(setup, setupRows, st, 0);
            setup.setColumnWidth(0, 22 * 256);
            setup.setColumnWidth(1, 45 * 256);
            yesNo(setup, 13, 1);

            Sheet customers = wb.createSheet(ExcelInvoiceReader.CUSTOMERS);
            Object[][] customerRows = {
                    {"Kürzel", "Name", "Straße", "PLZ", "Ort", "Land", "Ansprechpartner", "E-Mail", "Käuferreferenz"},
                    {"MUSTER", "Musterfirma GmbH", "Musterstraße 10", "01099", "Dresden", "DE", "Frau Muster", "rechnung@example.com", ""},
            };
            table(customers, customerRows, st, 0);
            int[] widths = {12, 40, 28, 8, 16, 6, 20, 30, 22};
            for (int i = 0; i < widths.length; i++) customers.setColumnWidth(i, widths[i] * 256);

            Sheet inv = wb.createSheet("2026-09");
            Object[][] head = {
                    {"Rechnungsnummer", "September/2026"},
                    {"Rechnungsdatum", LocalDate.of(2026, 9, 30)},
                    {"Kunde", "MUSTER"},
                    {"Bestellnummer", "PO 4711"},
                    {"Käuferreferenz", ""},
                    {"Leistungszeitraum von", LocalDate.of(2026, 9, 1)},
                    {"Leistungszeitraum bis", LocalDate.of(2026, 9, 30)},
                    {"Fälligkeit", ""},
            };
            for (int i = 0; i < head.length; i++) {
                Row r = inv.createRow(i);
                cell(r, 0, head[i][0], st.bold, st);
                cell(r, 1, head[i][1], null, st);
            }
            Row control = inv.createRow(head.length);
            cell(control, 0, "Gesamtbetrag", st.bold, st);
            int firstItem = head.length + 3; // 0-based row of the first item
            Cell sum = control.createCell(1);
            sum.setCellFormula("SUMPRODUCT(D" + (firstItem + 1) + ":D200,E" + (firstItem + 1) + ":E200)");
            sum.setCellStyle(st.money);

            Object[][] items = {
                    {"Datum", "Typ", "Beschreibung", "Menge", "Einzelpreis"},
                    {LocalDate.of(2026, 9, 2), "Stunde", "Durchführung der Probe", 1.5, 60.0},
                    {LocalDate.of(2026, 9, 9), "Stunde", "Durchführung der Probe", 1.5, 60.0},
                    {LocalDate.of(2026, 9, 16), "Stunde", "Leitung des Auftritts", 2.0, 60.0},
                    {"", "Stück", "Fahrtkosten", 3, 10.0},
            };
            table(inv, items, st, firstItem - 1);
            for (int r = firstItem; r < firstItem + items.length - 1; r++) {
                inv.getRow(r).getCell(3).setCellStyle(st.qty);
                inv.getRow(r).getCell(4).setCellStyle(st.money);
            }
            int[] iw = {22, 10, 50, 10, 14};
            for (int i = 0; i < iw.length; i++) inv.setColumnWidth(i, iw[i] * 256);
            DataValidationHelper dvh = inv.getDataValidationHelper();
            DataValidation dv = dvh.createValidation(dvh.createExplicitListConstraint(new String[]{"Stunde", "Stück"}),
                    new CellRangeAddressList(firstItem, 199, 1, 1));
            dv.setShowErrorBox(true);
            inv.addValidationData(dv);

            wb.setActiveSheet(wb.getSheetIndex(inv));
            wb.getCreationHelper().createFormulaEvaluator().evaluateAll(); // store the control sum's value
            wb.write(os);
        }
        System.out.println("Vorlage: " + out.toAbsolutePath());
    }

    private static void table(Sheet sheet, Object[][] rows, Styles st, int startRow) {
        for (int i = 0; i < rows.length; i++) {
            Row r = sheet.createRow(startRow + i);
            for (int c = 0; c < rows[i].length; c++) cell(r, c, rows[i][c], i == 0 ? st.header : null, st);
        }
    }

    private static void cell(Row r, int col, Object v, CellStyle style, Styles st) {
        Cell c = r.createCell(col);
        if (v instanceof LocalDate d) {
            c.setCellValue(d);
            c.setCellStyle(st.date);
        } else if (v instanceof Number n) {
            c.setCellValue(n.doubleValue());
        } else if (v != null && !v.toString().isEmpty()) {
            c.setCellValue(v.toString());
        }
        if (style != null) c.setCellStyle(style);
    }

    private static void yesNo(Sheet sheet, int row, int col) {
        DataValidationHelper h = sheet.getDataValidationHelper();
        sheet.addValidationData(h.createValidation(h.createExplicitListConstraint(new String[]{"ja", "nein"}),
                new CellRangeAddressList(row, row, col, col)));
    }

    private static final class Styles {
        final CellStyle bold, header, date, money, qty;

        Styles(Workbook wb) {
            Font b = wb.createFont();
            b.setBold(true);
            bold = wb.createCellStyle();
            bold.setFont(b);
            header = wb.createCellStyle();
            header.setFont(b);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);
            date = wb.createCellStyle();
            date.setDataFormat(wb.createDataFormat().getFormat("dd.mm.yyyy"));
            money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0.00 \"€\""));
            qty = wb.createCellStyle();
            qty.setDataFormat(wb.createDataFormat().getFormat("#,##0.###"));
        }
    }
}
