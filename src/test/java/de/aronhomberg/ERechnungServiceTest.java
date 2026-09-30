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
