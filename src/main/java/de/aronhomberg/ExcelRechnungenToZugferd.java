package de.aronhomberg;

import java.nio.file.Path;
import java.util.List;

/**
 * Creates ZUGFeRD / Factur-X EN16931 invoices from an Excel workbook (see {@link ExcelInvoiceReader}),
 * with the visible PDF rendered by {@link InvoicePdfRenderer}; see {@link ERechnungService#fromData}.
 *
 * Usage: gradlew excelRechnungen [-Pexcel=rechnungen.in/rechnungen.xlsx] [-PoutDir=rechnungen.out]
 *        [-Psheet=2026-08] [-Pxrechnung] [-Pkosit] [-Pverapdf]
 */
public final class ExcelRechnungenToZugferd {
    private ExcelRechnungenToZugferd() {}

    public static void main(String[] rawArgs) throws Exception {
        List<String> args = ConvertRechnungenToZugferd.positional(rawArgs);
        ERechnungService.Options opt = ConvertRechnungenToZugferd.cliOptions(rawArgs);
        Path excel = Path.of(args.size() > 0 ? args.get(0) : "rechnungen.in/rechnungen.xlsx");
        Path outDir = Path.of(args.size() > 1 ? args.get(1) : "rechnungen.out");
        String onlySheet = args.size() > 2 ? args.get(2) : null;

        int total = 0, failed = 0;
        for (ExcelInvoiceReader.ExcelInvoice ei : ExcelInvoiceReader.read(excel)) {
            if (onlySheet != null && !onlySheet.equalsIgnoreCase(ei.sheet())) continue;
            total++;
            System.out.println("\n======== Blatt " + ei.sheet() + " ========");
            if (ei.error() != null) {
                failed++;
                System.err.println("FAILED: " + ei.error());
                continue;
            }
            try {
                ERechnungService.Result r = ERechnungService.fromData(ei.data(), outDir, opt, System.out::println);
                if (!ConvertRechnungenToZugferd.printResult(r)) failed++;
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
