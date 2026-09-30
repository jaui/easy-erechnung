package de.aronhomberg;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.common.PDNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uses the Excel template with fictitious data; only the in-process Mustang validator. */
class ERechnungServiceTest {
    private static final ERechnungService.Options MUSTANG = new ERechnungService.Options(true, Validators.Settings.mustangOnly());
    private static final ERechnungService.Progress QUIET = msg -> { };

    @TempDir
    static Path tmp;
    static InvoiceResponse.Invoice sample;

    @BeforeAll
    static void readTemplate() throws Exception {
        Path xlsx = tmp.resolve("vorlage.xlsx");
        ExcelTemplateWriter.main(new String[]{xlsx.toString()});
        List<ExcelInvoiceReader.ExcelInvoice> list = ExcelInvoiceReader.read(xlsx);
        assertEquals(1, list.size());
        assertNull(list.get(0).error());
        sample = list.get(0).data();
    }

    private static InvoiceResponse.Invoice fresh() throws Exception {
        return ExcelInvoiceReader.read(tmp.resolve("vorlage.xlsx")).get(0).data();
    }

    /** Runs {@code action} with a validator that rejects every invoice (as e.g. veraPDF would). */
    private static <T> T withRejectingValidator(java.util.concurrent.Callable<T> action) throws Exception {
        ERechnungService.ValidatorRunner original = ERechnungService.validators;
        ERechnungService.validators = (settings, pdf, xmls, reportDir) -> {
            Path report = reportDir.resolve("fake-report.xml");
            try {
                Files.writeString(report, "<rejected/>");
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return List.of(new Validators.Check("Fake", pdf.getFileName().toString(), Validators.Status.FAIL, "abgelehnt", report));
        };
        try {
            return action.call();
        } finally {
            ERechnungService.validators = original;
        }
    }

    @Test
    void rejectedRunKeepsPreviousValidInvoice(@TempDir Path out) throws Exception {
        ERechnungService.Result good = ERechnungService.fromData(fresh(), out, MUSTANG, QUIET);
        assertTrue(good.ok());
        byte[] goodPdf = Files.readAllBytes(good.pdf());
        byte[] goodXml = Files.readAllBytes(good.facturX());

        ERechnungService.Result bad = withRejectingValidator(() -> ERechnungService.fromData(fresh(), out, MUSTANG, QUIET));

        assertFalse(bad.ok());
        // previous invoice untouched
        assertArrayEquals(goodPdf, Files.readAllBytes(good.pdf()));
        assertArrayEquals(goodXml, Files.readAllBytes(good.facturX()));
        // rejected run kept for diagnosis, result points there
        Path failed = good.dir().resolve(OutputLayout.FAILED);
        assertEquals(failed, bad.dir());
        assertTrue(bad.pdf().startsWith(failed), bad.pdf().toString());
        assertTrue(Files.isRegularFile(bad.pdf()));
        assertTrue(Files.isRegularFile(failed.resolve("_pruefung/fake-report.xml")));
        assertEquals(failed.resolve("_pruefung/fake-report.xml"), bad.checks().get(0).report());
        assertTrue(Files.readString(failed.resolve("_pruefung/zusammenfassung.txt")).contains("NICHT übernommen"));
        assertTrue(bad.warnings().get(0).contains("NICHT übernommen"), bad.warnings().toString());
        // no staging leftovers next to the invoice folder
        try (Stream<Path> s = Files.list(out)) {
            assertEquals(1, s.count());
        }

        // the next successful run replaces the invoice and removes the old failed attempt
        ERechnungService.Result again = ERechnungService.fromData(fresh(), out, MUSTANG, QUIET);
        assertTrue(again.ok());
        assertFalse(Files.exists(failed));
    }

    @Test
    void rejectedFirstRunPublishesNoInvoice(@TempDir Path out) throws Exception {
        ERechnungService.Result bad = withRejectingValidator(() -> ERechnungService.fromData(fresh(), out, MUSTANG, QUIET));
        Path dir = out.resolve(bad.name());
        try (Stream<Path> s = Files.list(dir)) {
            assertEquals(Set.of(OutputLayout.FAILED), s.map(p -> p.getFileName().toString()).collect(Collectors.toSet()));
        }
        assertFalse(Files.exists(dir.resolve(bad.name() + ".pdf")));
    }

    @Test
    void excelToInvoiceHasCleanFolderAndIsValid(@TempDir Path out) throws Exception {
        ERechnungService.Result r = ERechnungService.fromData(fresh(), out, MUSTANG, QUIET);

        assertTrue(r.ok(), () -> r.checks().toString());
        assertEquals("Rechnung_September_2026", r.name());
        // no buyer reference in the template -> no XRechnung, but a note
        assertNull(r.xrechnung());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("Käuferreferenz")));
        try (Stream<Path> s = Files.list(r.dir())) {
            Set<String> top = s.map(p -> p.getFileName().toString()).collect(Collectors.toSet());
            assertEquals(Set.of("Rechnung_September_2026.pdf", "Rechnung_September_2026-factur-x.xml",
                    "_pruefung", "_zwischenschritte"), top);
        }
        assertTrue(Files.isRegularFile(r.dir().resolve("_pruefung/zusammenfassung.txt")));
        // no staging folders left behind
        try (Stream<Path> s = Files.list(out)) {
            assertEquals(1, s.count());
        }
    }

    @Test
    void facturXFileIsByteIdenticalToEmbeddedXml(@TempDir Path out) throws Exception {
        ERechnungService.Result r = ERechnungService.fromData(fresh(), out, MUSTANG, QUIET);

        assertNotNull(r.facturX());
        assertTrue(Files.isRegularFile(r.facturX()), () -> r.facturX().toString());
        assertEquals(r.dir(), r.facturX().getParent());
        Map<String, byte[]> embedded = embeddedFiles(r.pdf());
        assertEquals(Set.of("factur-x.xml"), embedded.keySet());
        assertArrayEquals(Files.readAllBytes(r.facturX()), embedded.get("factur-x.xml"));
    }

    private static Map<String, byte[]> embeddedFiles(Path pdf) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDDocumentNameDictionary names = doc.getDocumentCatalog().getNames();
            if (names != null && names.getEmbeddedFiles() != null) collect(names.getEmbeddedFiles(), files);
        }
        return files;
    }

    private static void collect(PDNameTreeNode<PDComplexFileSpecification> node, Map<String, byte[]> files) throws Exception {
        Map<String, PDComplexFileSpecification> names = node.getNames();
        if (names != null) {
            for (Map.Entry<String, PDComplexFileSpecification> e : names.entrySet()) {
                PDComplexFileSpecification spec = e.getValue();
                String name = spec.getFilename() != null ? spec.getFilename() : e.getKey();
                files.put(name, spec.getEmbeddedFile().toByteArray());
            }
        }
        if (node.getKids() != null) {
            for (PDNameTreeNode<PDComplexFileSpecification> kid : node.getKids()) collect(kid, files);
        }
    }

    @Test
    void existingPdfWithWrongAmountGivesWarning(@TempDir Path out) throws Exception {
        Path visual = out.resolve("original.pdf");
        InvoicePdfRenderer.render(fresh(), visual);
        InvoiceResponse.Invoice other = fresh();
        other.InvoiceLines.get(0).Quantity = 3;          // amount no longer matches the printed PDF
        other.MonetarySummation.PayableAmount += 90;

        ERechnungService.Result r = ERechnungService.fromPdf(other, visual, out.resolve("x"), MUSTANG, QUIET);

        assertTrue(r.ok(), () -> r.checks().toString());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("Rechnungsbetrag")), () -> r.warnings().toString());
    }

    @Test
    void pdfThatAlreadyIsAnEInvoiceIsRejected(@TempDir Path out) throws Exception {
        ERechnungService.Result first = ERechnungService.fromData(fresh(), out, MUSTANG, QUIET);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ERechnungService.fromPdf(fresh(), first.pdf(), out.resolve("again"), MUSTANG, QUIET));
        assertTrue(e.getMessage().contains("bereits eine E-Rechnung"));
    }

    @Test
    void plausibilityAcceptsMatchingPdfText() {
        assertTrue(ERechnungService.plausibility("Rechnung September/2026 ... Rechnungsbetrag 330,00 €", sample).isEmpty());
        assertEquals(2, ERechnungService.plausibility("irgendein anderer Text 12,00", sample).size());
    }
}
