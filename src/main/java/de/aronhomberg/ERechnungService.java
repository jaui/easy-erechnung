package de.aronhomberg;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.PDNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.text.PDFTextStripper;
import org.mustangproject.Invoice;
import org.mustangproject.ZUGFeRD.Profiles;
import org.mustangproject.ZUGFeRD.ZUGFeRD2PullProvider;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Creates one e-invoice (ZUGFeRD / Factur-X EN16931, optionally XRechnung) in the {@link OutputLayout}:
 * <ul>
 *   <li>{@link #fromData}: visible PDF rendered from the data (Excel)</li>
 *   <li>{@link #fromPdf}: an existing PDF keeps its look, the data (Excel or verified JSON) becomes the XML</li>
 * </ul>
 * Errors are thrown as exceptions (no System.exit), progress is reported through a callback.
 */
final class ERechnungService {
    private ERechnungService() {}

    interface Progress {
        void step(String message);
    }

    /**
     * @param allowMismatch Excel + PDF only: publish even if invoice number or amount due of the data are not
     *                      printed on the PDF (explicit user decision; default: the invoice is not published)
     */
    record Options(boolean xrechnung, Validators.Settings validators, boolean allowMismatch) {
        Options(boolean xrechnung, Validators.Settings validators) {
            this(xrechnung, validators, false);
        }
    }

    static final String MATCH_CHECK = "Abgleich PDF ↔ Daten";

    record Result(String name, Path dir, Path pdf, Path facturX, Path xrechnung, List<Validators.Check> checks, List<String> warnings) {
        boolean ok() {
            return checks.stream().noneMatch(c -> c.status() == Validators.Status.FAIL);
        }
    }

    /** Excel → own PDF layout → e-invoice. */
    static Result fromData(InvoiceResponse.Invoice data, Path outRoot, Options opt, Progress progress) throws Exception {
        applyDefaultPeriod(data);
        OutputLayout target = OutputLayout.of(outRoot, data.InvoiceNumber);
        progress.step(target.name + ": Daten prüfen");
        Invoice mustang = ConvertRechnungenToZugferd.toMustangInvoice(data, target.name);
        OutputLayout s = target.staging();
        try {
            progress.step(target.name + ": PDF erstellen");
            Path visual = s.work("visuell.pdf");
            InvoicePdfRenderer.render(data, visual);
            return finish(data, mustang, visual, target, s, new ArrayList<>(), List.of(), opt, progress);
        } catch (Exception e) {
            OutputLayout.deleteRecursively(s.dir);
            throw e;
        }
    }

    /** Existing PDF (look is kept) + data → e-invoice. */
    static Result fromPdf(InvoiceResponse.Invoice data, Path originalPdf, Path outRoot, Options opt, Progress progress) throws Exception {
        OutputLayout target = OutputLayout.of(outRoot, data.InvoiceNumber);
        progress.step(target.name + ": Original-PDF prüfen");
        checkOriginal(originalPdf);
        Invoice mustang = ConvertRechnungenToZugferd.toMustangInvoice(data, target.name);
        OutputLayout s = target.staging();
        try {
            List<String> warnings = new ArrayList<>();
            Path original = s.work("original.pdf");
            Files.copy(originalPdf, original, StandardCopyOption.REPLACE_EXISTING);

            // no active content in an outgoing invoice (and PDF/A forbids most of it)
            Path clean = s.work("bereinigt.pdf");
            List<String> removed = PdfSanitizer.sanitize(original, clean);
            if (!removed.isEmpty()) {
                warnings.add("Aus dem Original-PDF entfernt: " + String.join(", ", removed));
                progress.step(target.name + ": aktive Inhalte entfernt (" + removed.size() + ")");
            }

            Path fixed = s.work("unicode-fixed.pdf");
            // keep the look: only repair missing ToUnicode entries (e.g. Word ligatures), no re-rendering
            PdfToUnicodeFixer.Report repair = PdfToUnicodeFixer.fix(clean, fixed);
            for (String problem : repair.problems()) {
                warnings.add(problem + " – die PDF/A-Prüfung kann deshalb fehlschlagen. Abhilfe: Schrift installieren "
                        + "oder den Ordner mit der Schrift in EASY_ERECHNUNG_FONTS angeben.");
            }

            // The XML (from the data) is legally binding; the visible PDF must show the same invoice.
            String text = pdfText(fixed);
            List<String> mismatch = new ArrayList<>();
            if (text.isBlank()) {
                if (ConvertRechnungenToZugferd.allExempt(data)) {
                    throw new IllegalArgumentException("Das PDF hat keine Textebene (Scan?) – der §19-Hinweis kann nicht "
                            + "eingefügt werden. Bitte den Weg „Excel → Rechnung“ nutzen.");
                }
                mismatch.add("PDF ohne Textebene: Rechnungsnummer und Betrag im PDF lassen sich nicht abgleichen");
            } else {
                mismatch.addAll(plausibility(text, data));
            }
            List<Validators.Check> matchCheck = new ArrayList<>();
            if (mismatch.isEmpty()) {
                matchCheck.add(new Validators.Check(MATCH_CHECK, originalPdf.getFileName().toString(), Validators.Status.OK,
                        "Rechnungsnummer und Betrag stehen im PDF", null));
            } else {
                warnings.addAll(mismatch);
                matchCheck.add(new Validators.Check(MATCH_CHECK, originalPdf.getFileName().toString(),
                        opt.allowMismatch() ? Validators.Status.WARN : Validators.Status.FAIL,
                        String.join("; ", mismatch) + (opt.allowMismatch() ? " (ausdrücklich zugelassen)"
                                : " – nicht übernommen; Abweichung nur mit ausdrücklicher Freigabe zulassen"), null));
            }

            Path visual = fixed;
            if (ConvertRechnungenToZugferd.allExempt(data)) {
                Path stamped = s.work("mit-19-hinweis.pdf");
                // §19 UStG / § 34a UStDV: the note must be visible, not only in the XML (BT-120)
                if (PdfNoteStamper.stamp(fixed, stamped, ConvertRechnungenToZugferd.VISIBLE_19_NOTE, "§ 19", "§19")) {
                    progress.step(target.name + ": §19-Hinweis eingefügt");
                }
                visual = stamped;
            }
            return finish(data, mustang, visual, target, s, warnings, matchCheck, opt, progress);
        } catch (Exception e) {
            OutputLayout.deleteRecursively(s.dir);
            throw e;
        }
    }

    private static Result finish(InvoiceResponse.Invoice data, Invoice mustang, Path visual, OutputLayout target,
                                 OutputLayout s, List<String> warnings, List<Validators.Check> preChecks,
                                 Options opt, Progress progress) throws Exception {
        progress.step(target.name + ": E-Rechnung einbetten");
        byte[] xml = ConvertRechnungenToZugferd.generateXml(mustang);
        Files.write(s.facturX(), xml);   // next to the final PDF, byte-identical to the embedded file
        ConvertRechnungenToZugferd.embedZugferd(visual, s.pdf(), xml);

        List<Path> xmls = new ArrayList<>(List.of(s.facturX()));
        if (opt.xrechnung()) {
            if (data.BuyerReference == null || data.BuyerReference.isBlank()) {
                warnings.add("keine XRechnung: Käuferreferenz (BT-10) fehlt");
            } else {
                ZUGFeRD2PullProvider xr = new ZUGFeRD2PullProvider();
                xr.setProfile(Profiles.getByName("XRECHNUNG"));
                xr.generateXML(mustang);
                Files.write(s.xrechnung(), xr.getXML());
                xmls.add(s.xrechnung());
            }
        }

        progress.step(target.name + ": prüfen (" + enabled(opt.validators()) + ")");
        List<Validators.Check> checks = new ArrayList<>(preChecks);
        checks.addAll(validators.run(opt.validators(), s.pdf(), xmls, s.checks()));

        // Only a run that no validator rejected replaces the invoice; a rejected run is kept for diagnosis
        // in _letzter-fehlversuch/ and the previous valid invoice stays untouched.
        boolean rejected = checks.stream().anyMatch(c -> c.status() == Validators.Status.FAIL);
        OutputLayout dest = rejected ? target.failedLayout() : target;
        if (rejected) {
            warnings.add(0, "Prüfung fehlgeschlagen – die Rechnung wurde NICHT übernommen, eine vorhandene Rechnung "
                    + "bleibt unverändert. Fehlversuch mit Prüfberichten: " + dest.dir);
        }
        // report paths point into the staging folder until the move; rewrite them to the destination
        List<Validators.Check> finalChecks = new ArrayList<>();
        for (Validators.Check c : checks) {
            Path report = c.report() == null ? null : dest.checks().resolve(c.report().getFileName());
            finalChecks.add(new Validators.Check(c.validator(), c.target(), c.status(), c.summary(), report));
        }
        List<String> summary = new ArrayList<>();
        summary.add("E-Rechnung " + target.name + " – " + LocalDate.now() + (rejected ? " – NICHT übernommen" : ""));
        finalChecks.forEach(c -> summary.add(c.line()));
        warnings.forEach(w -> summary.add("Hinweis: " + w));
        Files.write(s.check("zusammenfassung.txt"), summary, StandardCharsets.UTF_8);

        if (rejected) target.keepFailed(s); else target.commit(s);
        return new Result(target.name, dest.dir, dest.pdf(), dest.facturX(),
                Files.exists(dest.xrechnung()) ? dest.xrechnung() : null, finalChecks, warnings);
    }

    /** Validator call; replaceable in tests to simulate a rejected invoice. */
    interface ValidatorRunner {
        List<Validators.Check> run(Validators.Settings settings, Path pdf, List<Path> xmls, Path reportDir);
    }

    static ValidatorRunner validators = Validators::run;

    /** Same period for PDF and XML: explicit values, else the billed month. */
    static void applyDefaultPeriod(InvoiceResponse.Invoice src) {
        if (src.PeriodStart == null || src.PeriodStart.isBlank()) {
            LocalDate start = ConvertRechnungenToZugferd.billingMonth(src.InvoiceNumber, LocalDate.parse(src.InvoiceDate));
            src.PeriodStart = start.toString();
        }
        if (src.PeriodEnd == null || src.PeriodEnd.isBlank()) {
            LocalDate start = LocalDate.parse(src.PeriodStart);
            src.PeriodEnd = start.withDayOfMonth(start.lengthOfMonth()).toString();
        }
    }

    private static void checkOriginal(Path pdf) throws IOException {
        if (!Files.isRegularFile(pdf)) throw new IllegalArgumentException("PDF nicht gefunden: " + pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            if (doc.isEncrypted()) throw new IllegalArgumentException("Das PDF ist verschlüsselt: " + pdf.getFileName());
            PDDocumentNameDictionary names = doc.getDocumentCatalog().getNames();
            PDEmbeddedFilesNameTreeNode files = names == null ? null : names.getEmbeddedFiles();
            if (files != null && hasInvoiceXml(files)) {
                throw new IllegalArgumentException("Das PDF enthält bereits eine E-Rechnung (" + pdf.getFileName()
                        + ") – bitte das ursprüngliche PDF ohne eingebettetes XML wählen.");
            }
        }
    }

    private static boolean hasInvoiceXml(PDNameTreeNode<PDComplexFileSpecification> node) throws IOException {
        Map<String, PDComplexFileSpecification> map = node.getNames();
        if (map != null) {
            for (String n : map.keySet()) {
                String l = n.toLowerCase(Locale.ROOT);
                if (l.equals("factur-x.xml") || l.equals("zugferd-invoice.xml") || l.equals("xrechnung.xml")) return true;
            }
        }
        if (node.getKids() != null) for (PDNameTreeNode<PDComplexFileSpecification> kid : node.getKids()) if (hasInvoiceXml(kid)) return true;
        return false;
    }

    private static String pdfText(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    /** Warnings if the printed PDF does not show the invoice number or the amount due of the data. */
    static List<String> plausibility(String pdfText, InvoiceResponse.Invoice data) {
        List<String> warnings = new ArrayList<>();
        String text = pdfText.replaceAll("\\s+", " ");
        String compact = text.replace(" ", "");
        if (!compact.contains(data.InvoiceNumber.replace(" ", ""))) {
            warnings.add("Rechnungsnummer „" + data.InvoiceNumber + "“ steht nicht im PDF – passt das PDF zu diesem Blatt?");
        }
        BigDecimal amount = BigDecimal.valueOf(data.MonetarySummation.PayableAmount).setScale(2, RoundingMode.HALF_UP);
        NumberFormat de = NumberFormat.getNumberInstance(Locale.GERMANY);
        de.setMinimumFractionDigits(2);
        de.setMaximumFractionDigits(2);
        String german = de.format(amount);                     // 1.234,50
        String plain = german.replace(".", "");               // 1234,50
        if (!compact.contains(german) && !compact.contains(plain) && !compact.contains(amount.toPlainString())) {
            warnings.add("Rechnungsbetrag " + german + " € steht nicht im PDF – passt das PDF zu diesem Blatt?");
        }
        return warnings;
    }

    private static String enabled(Validators.Settings s) {
        List<String> v = new ArrayList<>();
        if (s.mustang()) v.add("Mustang");
        if (s.kosit()) v.add("KoSIT");
        if (s.verapdf()) v.add("veraPDF");
        return v.isEmpty() ? "keine Validatoren aktiv" : String.join(", ", v);
    }
}
