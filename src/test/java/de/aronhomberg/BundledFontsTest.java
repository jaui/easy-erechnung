package de.aronhomberg;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundledFontsTest {
    static final String SPECIAL = "ß ä ö ü Ä Ö Ü € § – « » č š Č Š";

    @Test
    void bothFontsHaveGlyphsForGermanAndCentralEuropeanText() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (PDType0Font font : new PDType0Font[]{BundledFonts.regular(doc), BundledFonts.bold(doc)}) {
                assertTrue(font.getName().contains("LiberationSans"), font.getName());
                SPECIAL.codePoints().filter(cp -> cp != ' ').forEach(cp -> {
                    String ch = new String(Character.toChars(cp));
                    try {
                        byte[] code = font.encode(ch); // throws if the font has no glyph
                        assertEquals(2, code.length, ch);
                        int gid = ((code[0] & 0xff) << 8) | (code[1] & 0xff);
                        assertNotEquals(0, gid, () -> "no glyph for " + ch + " in " + font.getName());
                    } catch (Exception e) {
                        throw new AssertionError("no glyph for " + ch + " in " + font.getName(), e);
                    }
                });
            }
        }
    }

    @Test
    void renderedTextCanBeExtractedAgain(@TempDir Path tmp) throws Exception {
        Path pdf = tmp.resolve("fonts.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(BundledFonts.regular(doc), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(SPECIAL);
                cs.newLineAtOffset(0, -20);
                cs.setFont(BundledFonts.bold(doc), 12);
                cs.showText("Bold " + SPECIAL);
                cs.endText();
            }
            doc.save(pdf.toFile());
        }
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains(SPECIAL), text);
            assertTrue(text.contains("Bold " + SPECIAL), text);
        }
    }
}
