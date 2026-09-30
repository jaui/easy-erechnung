package de.aronhomberg;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.mustangproject.BankDetails;
import org.mustangproject.Invoice;
import org.mustangproject.Item;
import org.mustangproject.Product;
import org.mustangproject.TradeParty;
import org.mustangproject.ZUGFeRD.Profiles;
import org.mustangproject.ZUGFeRD.ZUGFeRD2PullProvider;
import org.mustangproject.ZUGFeRD.ZUGFeRDExporterFromA3;
import org.mustangproject.validator.ZUGFeRDValidator;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

/**
 * Builds a Factur-X / ZUGFeRD EN16931 invoice:
 * visual PDF + embedded CII XML + standalone external XML, then validates.
 *
 * Usage:
 *   gradlew buildERechnung
 *   gradlew buildERechnung --args="rechnungen.out/erechnung"
 */
public final class BuildZugferdInvoice {
    private BuildZugferdInvoice() {}

    private static final String EXEMPTION_REASON =
            "Gemaess § 19 UStG wird keine Umsatzsteuer berechnet.";

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 0 ? args[0] : "rechnungen.out/erechnung");
        Files.createDirectories(outDir);

        Invoice invoice = buildSampleInvoice();

        Path visualPdf = outDir.resolve("rechnung-visual.pdf");
        Path zugferdPdf = outDir.resolve("rechnung-zugferd-en16931.pdf");
        Path externalXml = outDir.resolve("factur-x.xml");
        Path reportXml = outDir.resolve("mustang-validation-report.xml");

        writeVisualPdf(invoice, visualPdf);
        System.out.println("Visual PDF: " + visualPdf.toAbsolutePath());

        byte[] xmlBytes = generateXml(invoice);
        Files.write(externalXml, xmlBytes);
        System.out.println("External XML: " + externalXml.toAbsolutePath());

        embedZugferd(visualPdf, zugferdPdf, invoice);
        System.out.println("ZUGFeRD PDF: " + zugferdPdf.toAbsolutePath());

        // Also keep a copy of the embedded payload name used by Factur-X
        Files.write(outDir.resolve("zugferd-invoice.xml"), xmlBytes);

        boolean pdfOk = validateWithMustang(zugferdPdf, reportXml);
        boolean xmlOk = validateWithMustang(externalXml, outDir.resolve("mustang-xml-validation-report.xml"));

        System.out.println();
        System.out.println("Mustang PDF validation: " + (pdfOk ? "VALID" : "INVALID"));
        System.out.println("Mustang XML validation: " + (xmlOk ? "VALID" : "INVALID"));

        if (!pdfOk || !xmlOk) {
            System.err.println("Validation failed — see reports in " + outDir.toAbsolutePath());
            System.exit(1);
        }
        System.out.println("OK — Factur-X/ZUGFeRD EN16931 artifacts ready in " + outDir.toAbsolutePath());
    }

    /** Sample invoice with fictitious data (Kleinunternehmer, service hours + piece items). */
    static Invoice buildSampleInvoice() {
        Date issue = toDate(LocalDate.of(2026, 9, 28));
        Date due = toDate(LocalDate.of(2026, 10, 12));
        Date delivery = toDate(LocalDate.of(2026, 9, 30));

        TradeParty seller = new TradeParty(
                "Max Mustermann",
                "Musterweg 1",
                "01067",
                "Dresden",
                "DE"
        ).setID("201/123/45678") // BT-29 Seller identifier (required by BR-CO-26 when no VAT ID)
                .addTaxID("201/123/45678")
                .addBankDetails(new BankDetails("DE02120300000000202051", "BYLADEM1001")
                        .setAccountName("Max Mustermann"));

        TradeParty buyer = new TradeParty(
                "Musterfirma GmbH",
                "Musterstrasse 10",
                "01099",
                "Dresden",
                "DE"
        );

        Invoice invoice = new Invoice()
                .setNumber("RE-2026-09-001")
                .setIssueDate(issue)
                .setDueDate(due)
                .setDeliveryDate(delivery)
                .setCurrency("EUR")
                .setSender(seller)
                .setRecipient(buyer)
                .setBuyerOrderReferencedDocumentID("PO 4711")
                .setPaymentTermDescription("Zahlbar innerhalb von 14 Tagen ohne Abzug.")
                .setOwnOrganisationFullPlaintextInfo(
                        "Max Mustermann, Musterweg 1, 01067 Dresden, Steuernummer 201/123/45678");

        List<LineSpec> lines = List.of(
                new LineSpec("1", "02.09.2026 Durchfuehrung der Probe", "HUR", "1.5", "60.00"),
                new LineSpec("2", "09.09.2026 Durchfuehrung der Probe", "HUR", "1.5", "60.00"),
                new LineSpec("3", "16.09.2026 Durchfuehrung der Probe", "HUR", "1.5", "60.00"),
                new LineSpec("4", "30.09.2026 Durchfuehrung der Probe", "HUR", "1.5", "60.00"),
                new LineSpec("5", "Fahrtkosten", "H87", "4", "10.00")
        );

        for (LineSpec line : lines) {
            Product product = new Product(line.name, line.name, line.unit, BigDecimal.ZERO)
                    .setTaxCategoryCode("E")
                    .setTaxExemptionReason(EXEMPTION_REASON);
            invoice.addItem(new Item(product, new BigDecimal(line.price), new BigDecimal(line.qty)).setId(line.id));
        }

        return invoice;
    }

    private static byte[] generateXml(Invoice invoice) {
        ZUGFeRD2PullProvider provider = new ZUGFeRD2PullProvider();
        provider.setProfile(Profiles.getByName("EN16931"));
        provider.generateXML(invoice);
        return provider.getXML();
    }

    private static void embedZugferd(Path visualPdf, Path outPdf, Invoice invoice) throws IOException {
        // PDFBox output is not PDF/A; ignorePDFAErrors lets Mustang attach Factur-X and produce PDF/A-3.
        // Fonts are embedded TrueType so veraPDF font rules can pass after conversion.
        ZUGFeRDExporterFromA3 exporter = new ZUGFeRDExporterFromA3()
                .setProducer("easy-e-rechnung")
                .setCreator(System.getProperty("user.name", "easy-e-rechnung"))
                .setZUGFeRDVersion(2)
                .setProfile("EN16931")
                .ignorePDFAErrors()
                .load(visualPdf.toString());
        try {
            exporter.setTransaction(invoice);
            exporter.export(outPdf.toString());
        } finally {
            exporter.close();
        }
    }

    private static boolean validateWithMustang(Path source, Path reportPath) throws IOException {
        ZUGFeRDValidator validator = new ZUGFeRDValidator();
        String report = validator.validate(source.toAbsolutePath().toString());
        Files.writeString(reportPath, report, StandardCharsets.UTF_8);
        System.out.println("Mustang report: " + reportPath.toAbsolutePath());

        boolean valid = report.contains("status=\"valid\"")
                && !report.matches("(?s).*?<summary[^>]*status=\"invalid\".*");
        // Prefer the final summary attribute if present
        int lastSummary = report.lastIndexOf("<summary");
        if (lastSummary >= 0) {
            String tail = report.substring(lastSummary, Math.min(report.length(), lastSummary + 80));
            if (tail.contains("status=\"valid\"")) valid = true;
            if (tail.contains("status=\"invalid\"")) valid = false;
        }
        return valid;
    }

    private static void writeVisualPdf(Invoice invoice, Path out) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            // Embedded TrueType fonts (required for PDF/A); Standard 14 fonts are not embeddable.
            PDType0Font font = BundledFonts.regular(doc);
            PDType0Font fontBold = BundledFonts.bold(doc);

            float margin = 50;
            float y = page.getMediaBox().getHeight() - margin;
            float leading = 14;

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                y = writeLine(cs, fontBold, 16, margin, y, "RECHNUNG " + invoice.getNumber());
                y -= leading;
                y = writeLine(cs, font, 10, margin, y,
                        "Datum: " + fmt(invoice.getIssueDate())
                                + "   Faellig: " + fmt(invoice.getDueDate())
                                + "   Bestellnr.: " + nullToEmpty(invoice.getBuyerOrderReferencedDocumentID()));
                y -= leading * 1.5f;

                y = writeLine(cs, fontBold, 11, margin, y, "Absender");
                y = writeLine(cs, font, 10, margin, y, invoice.getSender().getName());
                y = writeLine(cs, font, 10, margin, y,
                        invoice.getSender().getStreet() + ", "
                                + invoice.getSender().getZIP() + " " + invoice.getSender().getLocation());
                y = writeLine(cs, font, 10, margin, y, "Steuernummer: " + nullToEmpty(invoice.getSender().getTaxID()));
                y -= leading;

                y = writeLine(cs, fontBold, 11, margin, y, "Empfaenger");
                y = writeLine(cs, font, 10, margin, y, invoice.getRecipient().getName());
                y = writeLine(cs, font, 10, margin, y,
                        invoice.getRecipient().getStreet() + ", "
                                + invoice.getRecipient().getZIP() + " " + invoice.getRecipient().getLocation());
                y -= leading * 1.5f;

                y = writeLine(cs, fontBold, 10, margin, y,
                        pad("Pos", 4) + pad("Beschreibung", 42) + pad("Menge", 8) + pad("Preis", 10) + "Summe");
                y -= 4;
                BigDecimal total = BigDecimal.ZERO;
                int i = 1;
                for (org.mustangproject.ZUGFeRD.IZUGFeRDExportableItem item : invoice.getZFItems()) {
                    BigDecimal line = item.getPrice().multiply(item.getQuantity());
                    total = total.add(line);
                    String name = item.getProduct().getName();
                    if (name.length() > 40) name = name.substring(0, 40);
                    y = writeLine(cs, font, 10, margin, y,
                            pad(String.valueOf(i++), 4)
                                    + pad(name, 42)
                                    + pad(item.getQuantity().toPlainString(), 8)
                                    + pad(item.getPrice().toPlainString(), 10)
                                    + line.toPlainString() + " EUR");
                }
                y -= leading;
                y = writeLine(cs, fontBold, 11, margin, y, "Gesamt (netto = brutto): " + total.toPlainString() + " EUR");
                y = writeLine(cs, font, 9, margin, y, EXEMPTION_REASON);
                y -= leading;
                y = writeLine(cs, font, 10, margin, y, "IBAN: DE02 1203 0000 0000 2020 51");
                y = writeLine(cs, font, 10, margin, y, "BIC:  BYLADEM1001");
                writeLine(cs, font, 10, margin, y - leading, invoice.getPaymentTermDescription());
            }

            doc.save(out.toFile());
        }
    }

    private static float writeLine(PDPageContentStream cs, PDType0Font font, float size,
                                   float x, float y, String text) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(text == null ? "" : text);
        cs.endText();
        return y - (size + 4);
    }

    private static String pad(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s.substring(0, width);
        return s + " ".repeat(width - s.length());
    }

    private static String fmt(Date d) {
        return new SimpleDateFormat("dd.MM.yyyy").format(d);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static Date toDate(LocalDate d) {
        return Date.from(d.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private record LineSpec(String id, String name, String unit, String qty, String price) {}
}
