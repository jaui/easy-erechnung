package de.aronhomberg;

import org.mustangproject.Invoice;
import org.mustangproject.ZUGFeRD.Profiles;
import org.mustangproject.ZUGFeRD.ZUGFeRD2PullProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Creates ZUGFeRD / Factur-X EN16931 invoices from an Excel workbook (see {@link ExcelInvoiceReader}):
 * own PDF layout ({@link InvoicePdfRenderer}), embedded factur-x.xml and, when a buyer reference
 * (BT-10) is given, an additional XRechnung CII file. Everything is validated with Mustang.
 *
 * Usage: gradlew excelRechnungen [-Pexcel=rechnungen.in/rechnungen.xlsx] [-PoutDir=rechnungen.out/excel] [-Psheet=2026-08]
 */
public final class ExcelRechnungenToZugferd {
    private ExcelRechnungenToZugferd() {}

    public static void main(String[] args) throws Exception {
        Path excel = Path.of(args.length > 0 ? args[0] : "rechnungen.in/rechnungen.xlsx");
        Path outDir = Path.of(args.length > 1 ? args[1] : "rechnungen.out/excel");
        String onlySheet = args.length > 2 ? args[2] : null;

        List<ExcelInvoiceReader.ExcelInvoice> invoices = ExcelInvoiceReader.read(excel);
        int total = 0, failed = 0;
        for (ExcelInvoiceReader.ExcelInvoice ei : invoices) {
            if (onlySheet != null && !onlySheet.equalsIgnoreCase(ei.sheet())) continue;
            if (ei.error() != null) {
                total++;
                failed++;
                System.err.println("FAILED (Blatt " + ei.sheet() + "): " + ei.error());
                continue;
            }
            InvoiceResponse.Invoice src = ei.data();
            total++;
            String stem = "Rechnung_" + src.InvoiceNumber.replaceAll("[^\\p{L}\\p{N}._-]+", "_");
            System.out.println("\n======== Blatt " + ei.sheet() + " -> " + stem + " ========");
            try {
                // same period for PDF and XML: explicit values, else the billed month
                LocalDate issue = LocalDate.parse(src.InvoiceDate);
                if (src.PeriodStart == null) {
                    LocalDate start = ConvertRechnungenToZugferd.billingMonth(src.InvoiceNumber, issue);
                    src.PeriodStart = start.toString();
                    if (src.PeriodEnd == null) src.PeriodEnd = start.withDayOfMonth(start.lengthOfMonth()).toString();
                }
                if (src.PeriodEnd == null) {
                    LocalDate start = LocalDate.parse(src.PeriodStart);
                    src.PeriodEnd = start.withDayOfMonth(start.lengthOfMonth()).toString();
                }

                Path dir = outDir.resolve(stem);
                Files.createDirectories(dir);
                Invoice mustang = ConvertRechnungenToZugferd.toMustangInvoice(src, stem); // checks + total check

                Path visual = dir.resolve(stem + "-visual.pdf");
                InvoicePdfRenderer.render(src, visual);
                Path xml = dir.resolve(stem + "-factur-x.xml");
                Files.write(xml, ConvertRechnungenToZugferd.generateXml(mustang));
                Path pdf = dir.resolve(stem + ".pdf");
                ConvertRechnungenToZugferd.embedZugferd(visual, pdf, mustang);
                Files.delete(visual);

                boolean ok = ConvertRechnungenToZugferd.validate(pdf, dir.resolve(stem + "-mustang-pdf-report.xml"))
                        & ConvertRechnungenToZugferd.validate(xml, dir.resolve(stem + "-mustang-xml-report.xml"));
                System.out.println("PDF: " + pdf.toAbsolutePath() + (ok ? "  VALID" : "  INVALID"));

                if (src.BuyerReference != null && !src.BuyerReference.isBlank()) {
                    ZUGFeRD2PullProvider xr = new ZUGFeRD2PullProvider();
                    xr.setProfile(Profiles.getByName("XRECHNUNG"));
                    xr.generateXML(mustang);
                    Path xrechnung = dir.resolve(stem + "-xrechnung.xml");
                    Files.write(xrechnung, xr.getXML());
                    boolean xrOk = ConvertRechnungenToZugferd.validate(xrechnung, dir.resolve(stem + "-mustang-xrechnung-report.xml"));
                    System.out.println("XRechnung: " + xrechnung.toAbsolutePath() + (xrOk ? "  VALID" : "  INVALID"));
                    ok &= xrOk;
                }
                if (!ok) failed++;
            } catch (Exception e) {
                failed++;
                System.err.println("FAILED: " + e.getMessage());
            }
        }
        if (total == 0) {
            System.err.println("Keine Rechnungsblätter gefunden" + (onlySheet != null ? " (Blatt " + onlySheet + ")" : ""));
            System.exit(1);
        }
        System.out.println("\nDone. Failed: " + failed + " / " + total);
        if (failed > 0) System.exit(1);
    }
}
