package de.aronhomberg;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.io.IOException;
import java.io.InputStream;

/**
 * Liberation Sans (SIL Open Font License 1.1, metric-compatible with Arial), shipped with the app so
 * that generated PDFs look the same on Windows, macOS and Linux regardless of installed fonts.
 * Fonts are embedded as subsets with ToUnicode, which keeps the PDFs PDF/A-compatible.
 */
final class BundledFonts {
    private BundledFonts() {}

    static final String REGULAR = "/fonts/LiberationSans-Regular.ttf";
    static final String BOLD = "/fonts/LiberationSans-Bold.ttf";

    static PDType0Font regular(PDDocument doc) throws IOException {
        return load(doc, REGULAR);
    }

    static PDType0Font bold(PDDocument doc) throws IOException {
        return load(doc, BOLD);
    }

    private static PDType0Font load(PDDocument doc, String resource) throws IOException {
        try (InputStream in = BundledFonts.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("Font resource missing: " + resource);
            return PDType0Font.load(doc, in, true);
        }
    }
}
