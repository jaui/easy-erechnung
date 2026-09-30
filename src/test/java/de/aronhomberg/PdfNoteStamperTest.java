package de.aronhomberg;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfNoteStamperTest {
    private static final String PROP = "easyerechnung.systemFonts";
    private static final String NOTE = "Gemäß § 19 UStG wird keine Umsatzsteuer berechnet.";
    private String previous;

    @BeforeEach
    void disableSystemFonts() {
        previous = System.getProperty(PROP);
        System.setProperty(PROP, "false"); // always the bundled font, independent of the machine
    }

    @AfterEach
    void restore() {
        if (previous == null) System.clearProperty(PROP);
        else System.setProperty(PROP, previous);
    }

    private static Path pdfWithText(Path file, String... lines) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(BundledFonts.regular(doc), 11);
                cs.newLineAtOffset(60, 700);
                for (String line : lines) {
                    cs.showText(line);
                    cs.newLineAtOffset(0, -14);
                }
                cs.endText();
            }
            doc.save(file.toFile());
        }
        return file;
    }

    @Test
    void stampsNoteWithEmbeddedBundledFont(@TempDir Path tmp) throws Exception {
        Path in = pdfWithText(tmp.resolve("in.pdf"), "Rechnung Nr. 1", "Rechnungsbetrag 100,00 €");
        Path out = tmp.resolve("out.pdf");

        assertTrue(PdfNoteStamper.stamp(in, out, NOTE, "§ 19", "§19"));

        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Rechnungsbetrag 100,00 €"), text);
            assertTrue(text.contains(NOTE), text);

            PDResources res = doc.getPage(0).getResources();
            List<String> fonts = new ArrayList<>();
            for (COSName name : res.getFontNames()) {
                PDFont f = res.getFont(name);
                fonts.add(f.getName());
                assertTrue(f.isEmbedded(), () -> f.getName() + " not embedded");
                assertTrue(f.getName().contains("LiberationSans"), f.getName());
            }
            // original font + note font
            assertTrue(fonts.size() >= 2, fonts::toString);
        }
    }

    @Test
    void skipsWhenMarkerIsAlreadyPresent(@TempDir Path tmp) throws Exception {
        Path in = pdfWithText(tmp.resolve("in.pdf"), "Rechnung Nr. 1", "Kein Ausweis der Umsatzsteuer (§ 19 UStG)");
        Path out = tmp.resolve("out.pdf");

        assertFalse(PdfNoteStamper.stamp(in, out, NOTE, "§ 19", "§19"));

        assertTrue(Files.isRegularFile(out));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            String text = new PDFTextStripper().getText(doc);
            assertFalse(text.contains(NOTE), text);
        }
    }

    @Test
    void loadFontFallsBackToBundledFont() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDFont f = PdfNoteStamper.loadFont(doc, "Calibri");
            assertTrue(f.getName().contains("LiberationSans"), f.getName());
            assertArrayEquals(BundledFonts.regular(doc).encode("ä"), f.encode("ä"));
        }
    }
}
