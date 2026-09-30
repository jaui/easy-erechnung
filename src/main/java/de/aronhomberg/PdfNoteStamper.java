package de.aronhomberg;

import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.FontMappers;
import org.apache.pdfbox.pdmodel.font.FontMapping;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Adds a single line of text below the last text line of the last page, in the body font of
 * the document (e.g. the §19 UStG note that the paper invoice is missing). The font is
 * embedded as a subset with ToUnicode, so the result stays PDF/A-compatible.
 */
public final class PdfNoteStamper {
    private PdfNoteStamper() {}

    /** Returns false (and writes {@code in} unchanged to {@code out}) if the text is already present. */
    public static boolean stamp(Path in, Path out, String text, String... alreadyPresentMarkers) throws IOException {
        try (PDDocument doc = Loader.loadPDF(in.toFile())) {
            int pageNo = doc.getNumberOfPages();
            LastLine last = new LastLine();
            last.setStartPage(pageNo);
            last.setEndPage(pageNo);
            String pageText = last.getText(doc);
            for (String marker : alreadyPresentMarkers) {
                if (pageText.replaceAll("\\s+", " ").contains(marker)) {
                    doc.save(out.toFile());
                    return false;
                }
            }
            if (last.font == null) throw new IOException("No text found on last page of " + in);

            PDPage page = doc.getPage(pageNo - 1);
            float fontSize = last.fontSize > 0 ? last.fontSize : 11f;
            PDFont font = loadFont(doc, last.font.getName().replaceFirst("^[A-Z]{6}[+]", ""));
            float pageHeight = page.getMediaBox().getHeight();
            float baseline = pageHeight - last.maxY - 2.4f * fontSize; // one empty line below the last text
            float bottom = page.getMediaBox().getLowerLeftY() + 2 * fontSize;
            if (baseline < bottom) throw new IOException("No free space below the last text line for the note");

            try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                cs.beginText();
                cs.setFont(font, fontSize);
                cs.newLineAtOffset(last.minX, baseline);
                cs.showText(text);
                cs.endText();
            }
            doc.save(out.toFile());
            return true;
        }
    }

    private static PDFont loadFont(PDDocument doc, String baseName) throws IOException {
        FontMapping<TrueTypeFont> mapping = FontMappers.instance().getTrueTypeFont(baseName, null);
        if (mapping == null || mapping.isFallback()) {
            mapping = FontMappers.instance().getTrueTypeFont("Arial", null);
        }
        return PDType0Font.load(doc, mapping.getFont(), true);
    }

    /** Collects the left text edge, the lowest baseline and the body font of the page. */
    private static final class LastLine extends PDFTextStripper {
        float minX = Float.MAX_VALUE;
        float maxY = 0;
        PDFont font;
        float fontSize;

        LastLine() throws IOException {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            for (TextPosition p : positions) {
                minX = Math.min(minX, p.getXDirAdj());
                if (p.getYDirAdj() >= maxY) {
                    maxY = p.getYDirAdj();
                    font = p.getFont();
                    fontSize = p.getFontSizeInPt();
                }
            }
            super.writeString(text, positions);
        }
    }
}
