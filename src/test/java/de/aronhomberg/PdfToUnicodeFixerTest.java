package de.aronhomberg;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfToUnicodeFixerTest {

    @Test
    void pdfWithoutUnmappedCodesIsLeftAlone(@TempDir Path tmp) throws Exception {
        Path xlsx = tmp.resolve("vorlage.xlsx");
        ExcelTemplateWriter.main(new String[]{xlsx.toString()});
        InvoiceResponse.Invoice inv = ExcelInvoiceReader.read(xlsx).get(0).data();
        Path in = tmp.resolve("rechnung.pdf");
        InvoicePdfRenderer.render(inv, in);
        Path out = tmp.resolve("fixed.pdf");

        PdfToUnicodeFixer.Report report = PdfToUnicodeFixer.fix(in, out);

        assertEquals(0, report.added());
        assertTrue(report.problems().isEmpty(), report.problems()::toString);
        assertTrue(Files.isRegularFile(out));
        try (PDDocument a = Loader.loadPDF(in.toFile()); PDDocument b = Loader.loadPDF(out.toFile())) {
            assertEquals(new PDFTextStripper().getText(a), new PDFTextStripper().getText(b));
        }
    }
}
