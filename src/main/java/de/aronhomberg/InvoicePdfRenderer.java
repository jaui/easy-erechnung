package de.aronhomberg;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.awt.Color;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders the visible invoice PDF (A4, Arial embedded, no ligatures) from the converter's JSON model.
 * The layout mirrors the RechnungFertig template "Kleinunternehmer": address window, info block,
 * item table with page breaks, totals, §19 note or VAT line, three-column footer with bank details.
 */
public final class InvoicePdfRenderer {
    private InvoicePdfRenderer() {}

    private static final float W = PDRectangle.A4.getWidth();
    private static final float H = PDRectangle.A4.getHeight();
    private static final float LEFT = 56.7f;          // 2 cm
    private static final float RIGHT = W - 56.7f;
    private static final float FOOTER_TOP = 95f;      // content must stay above this line
    private static final Color GREY = new Color(0x55, 0x55, 0x55);
    private static final Color RULE = new Color(0x99, 0x99, 0x99);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final Map<String, String> UNIT_LABELS = Map.of("HUR", "h", "H87", "Stk", "C62", "Einh.", "DAY", "Tag", "LS", "pausch.");

    // item table columns: x position (left edge) and alignment
    private static final float COL_POS = LEFT, COL_TEXT = LEFT + 30, COL_QTY_R = LEFT + 318,
            COL_UNIT = LEFT + 324, COL_PRICE_R = LEFT + 420, COL_SUM_R = RIGHT;
    private static final float TEXT_WIDTH = COL_QTY_R - COL_TEXT - 40;

    public static void render(InvoiceResponse.Invoice inv, Path out) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDFont regular = PDType0Font.load(doc, font("arial.ttf").toFile());
            PDFont bold = PDType0Font.load(doc, font("arialbd.ttf").toFile());
            Ctx ctx = new Ctx(doc, regular, bold);
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setTitle("Rechnung " + inv.InvoiceNumber);
            info.setAuthor(inv.Seller.Name);

            ctx.newPage();
            head(ctx, inv);
            ctx.y = H - 235;
            ctx.text(bold, 16, LEFT, ctx.y, "Rechnung " + inv.InvoiceNumber);
            ctx.y -= 30;
            items(ctx, inv);
            totals(ctx, inv);
            closing(ctx, inv);
            ctx.cs.close();
            for (int i = 0; i < ctx.pages.size(); i++) footer(ctx, inv, i);
            doc.save(out.toFile());
        }
    }

    // ---------- sections

    private static void head(Ctx c, InvoiceResponse.Invoice inv) throws IOException {
        InvoiceResponse.Invoice.Party s = inv.Seller, b = inv.Buyer;
        float y = H - 70;
        c.text(c.regular, 7.5f, LEFT, y, GREY, s.Name + " · " + s.StreetName + " · " + s.PostalCode + " " + s.City);
        y -= 18;
        for (String l : nonBlank(b.Name,
                b.ContactName == null || b.ContactName.isBlank() ? null : "z. Hd. " + b.ContactName,
                b.StreetName, b.PostalCode + " " + b.City)) {
            for (String w : wrap(c.regular, 10, l, 268)) {
                c.text(c.regular, 10, LEFT, y, w);
                y -= 13;
            }
        }

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Rechnungs-Nr.:", inv.InvoiceNumber});
        rows.add(new String[]{"Rechnungsdatum:", fmt(inv.InvoiceDate)});
        String from = inv.PeriodStart, to = inv.PeriodEnd;
        if (from != null && to != null) rows.add(new String[]{"Leistungszeitraum:", fmt(from) + " – " + fmt(to)});
        if (inv.OrderReference != null && !inv.OrderReference.isBlank()) rows.add(new String[]{"Bestell-Nr.:", inv.OrderReference});
        if (inv.BuyerReference != null && !inv.BuyerReference.isBlank() && !inv.BuyerReference.equals(inv.OrderReference)) rows.add(new String[]{"Ihre Referenz:", inv.BuyerReference});
        if (s.TaxIdentificationNumber != null && !s.TaxIdentificationNumber.isBlank()) rows.add(new String[]{"Steuernummer:", s.TaxIdentificationNumber});
        if (s.TaxVATNumber != null && !s.TaxVATNumber.isBlank()) rows.add(new String[]{"USt-IdNr.:", s.TaxVATNumber});
        float iy = H - 72;
        for (String[] r : rows) {
            c.text(c.regular, 9, 330, iy, GREY, r[0]);
            c.text(c.regular, 9, 420, iy, r[1]);
            iy -= 12.5f;
        }
    }

    private static void items(Ctx c, InvoiceResponse.Invoice inv) throws IOException {
        tableHeader(c);
        for (InvoiceResponse.Invoice.InvoiceLine l : inv.InvoiceLines) {
            String name = l.ProductName;
            if (l.ServiceDate != null && !l.ServiceDate.isBlank()) name = fmt(l.ServiceDate) + " – " + name;
            List<String> lines = wrap(c.regular, 10, name, TEXT_WIDTH);
            float rowHeight = lines.size() * 12.5f + 7;
            if (c.y - rowHeight < FOOTER_TOP + 20) {
                c.newPage();
                c.y = H - 70;
                tableHeader(c);
            }
            float base = c.y - 12;
            c.text(c.regular, 10, COL_POS, base, l.LineID);
            for (int i = 0; i < lines.size(); i++) c.text(c.regular, 10, COL_TEXT, base - i * 12.5f, lines.get(i));
            c.textRight(c.regular, 10, COL_QTY_R, base, qty(l.Quantity));
            c.text(c.regular, 10, COL_UNIT, base, UNIT_LABELS.getOrDefault(l.Unit, l.Unit));
            c.textRight(c.regular, 10, COL_PRICE_R, base, money(BigDecimal.valueOf(l.UnitPrice)));
            c.textRight(c.regular, 10, COL_SUM_R, base, money(lineTotal(l)));
            c.y -= rowHeight;
            c.rule(c.y, 0.5f, RULE);
        }
    }

    private static void tableHeader(Ctx c) throws IOException {
        c.rule(c.y, 1f, Color.BLACK);
        float base = c.y - 12;
        c.text(c.bold, 10, COL_POS, base, "Pos.");
        c.text(c.bold, 10, COL_TEXT, base, "Leistung");
        c.textRight(c.bold, 10, COL_QTY_R, base, "Menge");
        c.text(c.bold, 10, COL_UNIT, base, "Einheit");
        c.textRight(c.bold, 10, COL_PRICE_R, base, "Einzelpreis");
        c.textRight(c.bold, 10, COL_SUM_R, base, "Summe");
        c.y -= 17;
        c.rule(c.y, 1f, Color.BLACK);
    }

    private static void totals(Ctx c, InvoiceResponse.Invoice inv) throws IOException {
        BigDecimal net = BigDecimal.ZERO;
        for (InvoiceResponse.Invoice.InvoiceLine l : inv.InvoiceLines) net = net.add(lineTotal(l));
        boolean exempt = "E".equals(inv.Tax.TaxCategoryCode);
        BigDecimal vat = exempt ? BigDecimal.ZERO : net.multiply(BigDecimal.valueOf(inv.Tax.TaxPercentage))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        if (c.y < FOOTER_TOP + 90) { c.newPage(); c.y = H - 70; }
        float labelX = COL_UNIT - 60;
        c.y -= 16;
        c.text(c.regular, 10, labelX, c.y, "Summe netto");
        c.textRight(c.regular, 10, COL_SUM_R, c.y, money(net));
        if (!exempt) {
            c.y -= 15;
            c.text(c.regular, 10, labelX, c.y, "Umsatzsteuer " + qty(inv.Tax.TaxPercentage) + " %");
            c.textRight(c.regular, 10, COL_SUM_R, c.y, money(vat));
        }
        c.y -= 8;
        c.line(labelX, COL_SUM_R, c.y, 1f, Color.BLACK);
        c.y -= 14;
        c.text(c.bold, 10, labelX, c.y, "Rechnungsbetrag");
        c.textRight(c.bold, 10, COL_SUM_R, c.y, money(net.add(vat)));
        c.y -= 30;
    }

    private static void closing(Ctx c, InvoiceResponse.Invoice inv) throws IOException {
        List<String[]> paras = new ArrayList<>();
        if ("E".equals(inv.Tax.TaxCategoryCode)) {
            paras.add(new String[]{"b", "Gemäß § 19 UStG wird keine Umsatzsteuer berechnet (Kleinunternehmerregelung)."});
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(inv.InvoiceDate), LocalDate.parse(inv.DueDate));
        paras.add(new String[]{"r", "Bitte überweisen Sie den Rechnungsbetrag bis zum " + fmt(inv.DueDate)
                + " (" + days + " Tage ohne Abzug) unter Angabe der Rechnungsnummer auf das unten genannte Konto."});
        paras.add(new String[]{"r", "Mit freundlichen Grüßen"});
        paras.add(new String[]{"r", inv.Seller.Name});
        for (String[] p : paras) {
            PDFont f = p[0].equals("b") ? c.bold : c.regular;
            List<String> lines = wrap(f, 10, p[1], RIGHT - LEFT);
            if (c.y - lines.size() * 13 < FOOTER_TOP + 10) { c.newPage(); c.y = H - 70; }
            for (String l : lines) {
                c.text(f, 10, LEFT, c.y, l);
                c.y -= 13;
            }
            c.y -= 8;
        }
    }

    private static void footer(Ctx c, InvoiceResponse.Invoice inv, int pageIndex) throws IOException {
        try (PDPageContentStream cs = new PDPageContentStream(c.doc, c.pages.get(pageIndex), PDPageContentStream.AppendMode.APPEND, true)) {
            cs.setStrokingColor(RULE);
            cs.setLineWidth(0.5f);
            cs.moveTo(LEFT, FOOTER_TOP - 10);
            cs.lineTo(RIGHT, FOOTER_TOP - 10);
            cs.stroke();
            InvoiceResponse.Invoice.Party s = inv.Seller;
            float colW = (RIGHT - LEFT) / 3;
            String[][] cols = {
                    {s.Name, s.StreetName, s.PostalCode + " " + s.City},
                    nonBlank("Kontakt & Steuer",
                            s.Email == null ? null : "E-Mail: " + s.Email,
                            s.Phone == null ? null : "Telefon: " + s.Phone,
                            s.TaxIdentificationNumber == null ? null : "Steuernummer: " + s.TaxIdentificationNumber,
                            s.TaxVATNumber == null ? null : "USt-IdNr.: " + s.TaxVATNumber).toArray(new String[0]),
                    nonBlank("Bankverbindung",
                            "Kontoinhaber: " + inv.PaymentReceiver,
                            "IBAN: " + iban(inv.IBAN),
                            inv.BIC == null || inv.BIC.isBlank() ? null : "BIC: " + inv.BIC).toArray(new String[0]),
            };
            for (int col = 0; col < 3; col++) {
                float y = FOOTER_TOP - 24;
                for (int i = 0; i < cols[col].length; i++) {
                    write(cs, i == 0 ? c.bold : c.regular, 7.5f, LEFT + col * colW, y, i == 0 ? Color.BLACK : GREY, cols[col][i]);
                    y -= 9.5f;
                }
            }
            String page = "Seite " + (pageIndex + 1) + " von " + c.pages.size();
            write(cs, c.regular, 7, RIGHT - c.regular.getStringWidth(page) / 1000 * 7, 30, GREY, page);
        }
    }

    // ---------- drawing context

    private static final class Ctx {
        final PDDocument doc;
        final PDFont regular, bold;
        final List<PDPage> pages = new ArrayList<>();
        PDPageContentStream cs;
        float y;

        Ctx(PDDocument doc, PDFont regular, PDFont bold) {
            this.doc = doc;
            this.regular = regular;
            this.bold = bold;
        }

        void newPage() throws IOException {
            if (cs != null) cs.close();
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            pages.add(page);
            cs = new PDPageContentStream(doc, page);
        }

        void text(PDFont f, float size, float x, float y, String s) throws IOException {
            text(f, size, x, y, Color.BLACK, s);
        }

        void text(PDFont f, float size, float x, float y, Color color, String s) throws IOException {
            write(cs, f, size, x, y, color, s);
        }

        void textRight(PDFont f, float size, float xRight, float y, String s) throws IOException {
            write(cs, f, size, xRight - f.getStringWidth(s) / 1000 * size, y, Color.BLACK, s);
        }

        void rule(float y, float width, Color color) throws IOException {
            line(LEFT, RIGHT, y, width, color);
        }

        void line(float x1, float x2, float y, float width, Color color) throws IOException {
            cs.setStrokingColor(color);
            cs.setLineWidth(width);
            cs.moveTo(x1, y);
            cs.lineTo(x2, y);
            cs.stroke();
        }
    }

    private static void write(PDPageContentStream cs, PDFont f, float size, float x, float y, Color color, String s) throws IOException {
        if (s == null || s.isEmpty()) return;
        cs.beginText();
        cs.setNonStrokingColor(color);
        cs.setFont(f, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }

    // ---------- formatting

    private static List<String> wrap(PDFont f, float size, String text, float width) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (f.getStringWidth(candidate) / 1000 * size > width && line.length() > 0) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    private static List<String> nonBlank(String... values) {
        List<String> out = new ArrayList<>();
        for (String v : values) if (v != null && !v.isBlank() && !v.startsWith("null")) out.add(v);
        return out;
    }

    static BigDecimal lineTotal(InvoiceResponse.Invoice.InvoiceLine l) {
        return BigDecimal.valueOf(l.Quantity).multiply(BigDecimal.valueOf(l.UnitPrice)).setScale(2, RoundingMode.HALF_UP);
    }

    private static String money(BigDecimal v) {
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.GERMANY);
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        return nf.format(v) + " €";
    }

    private static String qty(double v) {
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.GERMANY);
        nf.setMaximumFractionDigits(3);
        return nf.format(v);
    }

    private static String fmt(String isoDate) {
        return LocalDate.parse(isoDate).format(DATE);
    }

    private static String iban(String iban) {
        return iban.replaceAll("\\s+", "").replaceAll("(.{4})", "$1 ").trim();
    }

    private static Path font(String file) throws IOException {
        Path p = Path.of(System.getenv().getOrDefault("WINDIR", "C:/Windows"), "Fonts", file);
        if (!Files.isRegularFile(p)) throw new IOException("Font for PDF/A embedding missing: " + p);
        return p;
    }
}
