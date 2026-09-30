package de.aronhomberg;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Reads invoices from an Excel workbook:
 * <ul>
 *   <li>sheet "Setup": column A = name, column B = value (seller, bank, tax settings)</li>
 *   <li>sheet "Kunden": header row + one customer per row (Kürzel, Name, Straße, PLZ, Ort, Land,
 *       Ansprechpartner, E-Mail, Käuferreferenz)</li>
 *   <li>every other sheet = one invoice: name/value rows (Rechnungsnummer, Rechnungsdatum, Kunde, ...),
 *       then a header row "Datum | Typ | Beschreibung | Menge | Einzelpreis" and one row per item.
 *       Typ "Stunde" = hours (HUR), "Stück" = pieces (H87); a UN/ECE unit code is accepted as well.</li>
 * </ul>
 * Missing required values raise an error naming sheet and cell instead of being guessed.
 */
public final class ExcelInvoiceReader {
    private ExcelInvoiceReader() {}

    public static final String SETUP = "Setup";
    public static final String CUSTOMERS = "Kunden";
    private static final DateTimeFormatter DE_DATE = DateTimeFormatter.ofPattern("d.M.yyyy");
    private static final DataFormatter FORMATTER = new DataFormatter(Locale.GERMANY);

    /** One invoice sheet mapped onto the converter's JSON model, or the reason why it could not be read. */
    public record ExcelInvoice(String sheet, InvoiceResponse.Invoice data, String error) {}

    public static List<ExcelInvoice> read(Path xlsx) throws IOException {
        try (Workbook wb = WorkbookFactory.create(xlsx.toFile(), null, true)) {
            wb.getCreationHelper().createFormulaEvaluator().evaluateAll(); // e.g. the "Gesamtbetrag" control sum
            Sheet setupSheet = wb.getSheet(SETUP);
            if (setupSheet == null) throw new IllegalArgumentException("Blatt \"" + SETUP + "\" fehlt");
            Map<String, Cell> setup = keyValues(setupSheet, Integer.MAX_VALUE);
            Map<String, Map<String, String>> customers = customers(wb.getSheet(CUSTOMERS));

            List<ExcelInvoice> result = new ArrayList<>();
            for (Sheet sheet : wb) {
                String name = sheet.getSheetName();
                if (name.equalsIgnoreCase(SETUP) || name.equalsIgnoreCase(CUSTOMERS) || name.startsWith("_")) continue;
                try {
                    result.add(new ExcelInvoice(name, invoice(sheet, setup, customers), null));
                } catch (IllegalArgumentException e) {
                    result.add(new ExcelInvoice(name, null, e.getMessage()));
                }
            }
            return result;
        }
    }

    private static InvoiceResponse.Invoice invoice(Sheet sheet, Map<String, Cell> setup,
                                                   Map<String, Map<String, String>> customers) {
        int tableHeader = findTableHeader(sheet);
        Map<String, Cell> head = keyValues(sheet, tableHeader);
        String where = "Blatt \"" + sheet.getSheetName() + "\"";

        InvoiceResponse.Invoice inv = new InvoiceResponse.Invoice();
        inv.InvoiceNumber = required(head, where, "Rechnungsnummer");
        LocalDate issue = requiredDate(head, where, "Rechnungsdatum");
        inv.InvoiceDate = issue.toString();
        LocalDate due = date(head.get(key("Fälligkeit")), where);
        int termDays = (int) number(setup.get(key("Zahlungsziel Tage")), "Setup", 14);
        inv.DueDate = (due != null ? due : issue.plusDays(termDays)).toString();
        inv.OrderReference = text(head.get(key("Bestellnummer")));
        LocalDate from = date(head.get(key("Leistungszeitraum von")), where);
        LocalDate to = date(head.get(key("Leistungszeitraum bis")), where);
        if (from != null) inv.PeriodStart = from.toString();
        if (to != null) inv.PeriodEnd = to.toString();
        inv.DocumentCurrencyCode = "EUR";

        // seller
        InvoiceResponse.Invoice.Party seller = new InvoiceResponse.Invoice.Party();
        seller.Name = required(setup, "Setup", "Name");
        seller.StreetName = required(setup, "Setup", "Straße");
        seller.PostalCode = required(setup, "Setup", "PLZ");
        seller.City = required(setup, "Setup", "Ort");
        seller.CountryCode = orDefault(text(setup.get(key("Land"))), "DE");
        seller.TaxIdentificationNumber = text(setup.get(key("Steuernummer")));
        seller.TaxVATNumber = text(setup.get(key("USt-IdNr")));
        seller.Email = text(setup.get(key("E-Mail")));
        seller.Phone = text(setup.get(key("Telefon")));
        seller.ContactName = seller.Name;
        inv.Seller = seller;
        inv.IBAN = required(setup, "Setup", "IBAN");
        inv.BIC = text(setup.get(key("BIC")));
        inv.PaymentReceiver = orDefault(text(setup.get(key("Kontoinhaber"))), seller.Name);

        // buyer
        String customerKey = required(head, where, "Kunde");
        Map<String, String> c = customers.get(key(customerKey));
        if (c == null) throw new IllegalArgumentException(where + ": Kunde \"" + customerKey + "\" nicht im Blatt \"" + CUSTOMERS + "\"");
        InvoiceResponse.Invoice.Party buyer = new InvoiceResponse.Invoice.Party();
        buyer.Name = requiredCustomer(c, customerKey, "Name");
        buyer.StreetName = requiredCustomer(c, customerKey, "Straße");
        buyer.PostalCode = requiredCustomer(c, customerKey, "PLZ");
        buyer.City = requiredCustomer(c, customerKey, "Ort");
        buyer.CountryCode = orDefault(c.get(key("Land")), "DE");
        buyer.ContactName = c.get(key("Ansprechpartner"));
        buyer.Email = c.get(key("E-Mail"));
        inv.Buyer = buyer;
        inv.BuyerReference = orDefault(text(head.get(key("Käuferreferenz"))), c.get(key("Käuferreferenz")));

        // tax
        boolean smallBusiness = yes(text(setup.get(key("Kleinunternehmer"))));
        double rate = smallBusiness ? 0 : number(setup.get(key("Umsatzsteuer %")), "Setup", 19);
        InvoiceResponse.Invoice.Tax tax = new InvoiceResponse.Invoice.Tax();
        tax.TaxTypeCode = "VAT";
        tax.TaxCategoryCode = smallBusiness ? "E" : "S";
        tax.TaxPercentage = rate;
        tax.TaxExemptionReason = smallBusiness ? text(setup.get(key("Befreiungsgrund"))) : null;
        inv.Tax = tax;

        // items
        inv.InvoiceLines = new ArrayList<>();
        Map<String, Integer> cols = tableColumns(sheet.getRow(tableHeader), where);
        BigDecimal net = BigDecimal.ZERO;
        for (int r = tableHeader + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String desc = text(row.getCell(cols.get("beschreibung")));
            String type = text(row.getCell(cols.get("typ")));
            Cell qtyCell = row.getCell(cols.get("menge"));
            Cell priceCell = row.getCell(cols.get("einzelpreis"));
            if (isBlank(desc) && isBlank(type) && isBlank(text(qtyCell)) && isBlank(text(priceCell))) continue;
            String cellRef = where + " Zeile " + (r + 1);
            if (isBlank(desc)) throw new IllegalArgumentException(cellRef + ": Beschreibung fehlt");
            InvoiceResponse.Invoice.InvoiceLine line = new InvoiceResponse.Invoice.InvoiceLine();
            line.LineID = String.valueOf(inv.InvoiceLines.size() + 1);
            LocalDate serviceDate = date(row.getCell(cols.get("datum")), cellRef);
            if (serviceDate != null) line.ServiceDate = serviceDate.toString();
            line.ProductName = desc;
            line.Unit = unit(type, cellRef);
            line.Quantity = number(qtyCell, cellRef, Double.NaN);
            line.UnitPrice = number(priceCell, cellRef, Double.NaN);
            if (Double.isNaN(line.Quantity) || Double.isNaN(line.UnitPrice)) {
                throw new IllegalArgumentException(cellRef + ": Menge und Einzelpreis sind Pflicht");
            }
            BigDecimal lineTotal = BigDecimal.valueOf(line.Quantity).multiply(BigDecimal.valueOf(line.UnitPrice))
                    .setScale(2, RoundingMode.HALF_UP);
            line.LineTotalAmount = lineTotal.doubleValue();
            line.TaxCategoryCode = tax.TaxCategoryCode;
            line.TaxPercentage = rate;
            net = net.add(lineTotal);
            inv.InvoiceLines.add(line);
        }
        if (inv.InvoiceLines.isEmpty()) throw new IllegalArgumentException(where + ": keine Positionen");

        BigDecimal vat = net.multiply(BigDecimal.valueOf(rate)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal gross = net.add(vat);
        double control = number(head.get(key("Gesamtbetrag")), where, Double.NaN);
        if (!Double.isNaN(control) && BigDecimal.valueOf(control).setScale(2, RoundingMode.HALF_UP).compareTo(gross) != 0) {
            throw new IllegalArgumentException(where + ": Gesamtbetrag " + control + " passt nicht zur Summe der Positionen " + gross);
        }
        InvoiceResponse.Invoice.MonetarySummation sum = new InvoiceResponse.Invoice.MonetarySummation();
        sum.LineTotal = net.doubleValue();
        sum.TaxExclusiveAmount = net.doubleValue();
        sum.TaxInclusiveAmount = gross.doubleValue();
        sum.PayableAmount = gross.doubleValue();
        inv.MonetarySummation = sum;
        return inv;
    }

    // ---------- helpers

    private static int findTableHeader(Sheet sheet) {
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            List<String> cells = new ArrayList<>();
            row.forEach(c -> cells.add(key(text(c))));
            if (cells.contains("datum") && cells.contains("typ") && cells.contains("menge")) return r;
        }
        throw new IllegalArgumentException("Blatt \"" + sheet.getSheetName()
                + "\": Kopfzeile \"Datum | Typ | Beschreibung | Menge | Einzelpreis\" fehlt");
    }

    private static Map<String, Integer> tableColumns(Row header, String where) {
        Map<String, Integer> cols = new HashMap<>();
        header.forEach(c -> cols.putIfAbsent(key(text(c)), c.getColumnIndex()));
        for (String required : List.of("datum", "typ", "beschreibung", "menge", "einzelpreis")) {
            if (!cols.containsKey(required)) throw new IllegalArgumentException(where + ": Spalte \"" + required + "\" fehlt");
        }
        return cols;
    }

    /** Column A = name, column B = value, for all rows before {@code endRow}. */
    private static Map<String, Cell> keyValues(Sheet sheet, int endRow) {
        Map<String, Cell> map = new LinkedHashMap<>();
        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), endRow - 1); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String k = key(text(row.getCell(0)));
            // header row "Bezeichnung | Wert" (older templates: "Name | Wert") is not a value
            if (k.isEmpty() || "wert".equals(key(text(row.getCell(1))))) continue;
            if (map.containsKey(k)) {
                // the same name twice is only ambiguous if the values differ
                if (Objects.equals(text(map.get(k)), text(row.getCell(1)))) continue;
                throw new IllegalArgumentException("Blatt \"" + sheet.getSheetName() + "\" Zeile " + (r + 1)
                        + ": \"" + text(row.getCell(0)) + "\" steht doppelt mit unterschiedlichen Werten");
            }
            map.put(k, row.getCell(1));
        }
        return map;
    }

    private static Map<String, Map<String, String>> customers(Sheet sheet) {
        Map<String, Map<String, String>> result = new HashMap<>();
        if (sheet == null) return result;
        Row header = sheet.getRow(sheet.getFirstRowNum());
        if (header == null) return result;
        Map<Integer, String> names = new HashMap<>();
        header.forEach(c -> names.put(c.getColumnIndex(), key(text(c))));
        for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            Map<String, String> values = new HashMap<>();
            row.forEach(c -> { if (names.containsKey(c.getColumnIndex())) values.put(names.get(c.getColumnIndex()), text(c)); });
            String id = values.get(key("Kürzel"));
            if (!isBlank(id)) result.put(key(id), values);
            if (!isBlank(values.get("name"))) result.putIfAbsent(key(values.get("name")), values);
        }
        return result;
    }

    private static String unit(String type, String where) {
        String t = key(type);
        switch (t) {
            case "stunde": case "stunden": case "std": case "h": return "HUR";
            case "stück": case "stk": case "stueck": return "H87";
            default:
                if (type != null && type.trim().matches("[A-Z0-9]{2,3}")) return type.trim();
                throw new IllegalArgumentException(where + ": Typ \"" + type + "\" unbekannt (Stunde oder Stück)");
        }
    }

    private static String required(Map<String, Cell> map, String where, String name) {
        String v = text(map.get(key(name)));
        if (isBlank(v)) throw new IllegalArgumentException(where + ": \"" + name + "\" fehlt");
        return v;
    }

    private static String requiredCustomer(Map<String, String> c, String id, String name) {
        String v = c.get(key(name));
        if (isBlank(v)) throw new IllegalArgumentException("Kunde \"" + id + "\": \"" + name + "\" fehlt");
        return v;
    }

    private static LocalDate requiredDate(Map<String, Cell> map, String where, String name) {
        LocalDate d = date(map.get(key(name)), where);
        if (d == null) throw new IllegalArgumentException(where + ": \"" + name + "\" fehlt");
        return d;
    }

    private static LocalDate date(Cell cell, String where) {
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate();
        }
        String t = text(cell);
        if (isBlank(t)) return null;
        try {
            return t.contains(".") ? LocalDate.parse(t, DE_DATE) : LocalDate.parse(t);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(where + ": \"" + t + "\" ist kein Datum (TT.MM.JJJJ)");
        }
    }

    private static double number(Cell cell, String where, double fallback) {
        if (cell == null) return fallback;
        if (cell.getCellType() == CellType.NUMERIC) return cell.getNumericCellValue();
        if (cell.getCellType() == CellType.FORMULA && cell.getCachedFormulaResultType() == CellType.NUMERIC) {
            return cell.getNumericCellValue();
        }
        String t = text(cell);
        if (isBlank(t)) return fallback;
        String n = t.replace("€", "").replace(" ", "");
        // German "1.234,50" -> "1234.50"; a plain "1.5" stays a decimal point
        if (n.contains(",")) n = n.replace(".", "").replace(',', '.');
        try {
            return Double.parseDouble(n);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(where + ": \"" + t + "\" ist keine Zahl");
        }
    }

    private static String text(Cell cell) {
        if (cell == null) return null;
        String t = FORMATTER.formatCellValue(cell).trim();
        return t.isEmpty() ? null : t;
    }

    private static String key(String s) {
        if (s == null) return "";
        return s.trim().replaceAll(":$", "").replaceAll("\\s+", " ").toLowerCase(Locale.GERMAN);
    }

    private static boolean yes(String s) {
        return s != null && List.of("ja", "yes", "x", "true", "1", "wahr").contains(key(s));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String orDefault(String s, String fallback) {
        return isBlank(s) ? fallback : s;
    }
}
