package de.aronhomberg;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Removes active content from an existing PDF before it becomes an outgoing e-invoice:
 * JavaScript (document-level and in actions), the open action and additional actions (AA) of document,
 * pages and annotations, actions forbidden by PDF/A (Launch, JavaScript, SubmitForm, ImportData, …),
 * XFA forms and previously embedded files. Links (URI, GoTo, GoToR) and named navigation stay.
 * Page content is not touched, so the visible invoice is unchanged.
 */
final class PdfSanitizer {
    private PdfSanitizer() {}

    /** Action types allowed by PDF/A-3 (ISO 19005-3, 6.6.1) and harmless for a sent invoice. */
    private static final Set<String> ALLOWED_ACTIONS = Set.of("GoTo", "GoToR", "URI", "Named", "Thread");

    /** Writes a cleaned copy of {@code in} to {@code out}; returns what was removed (empty if nothing). */
    static List<String> sanitize(Path in, Path out) throws IOException {
        List<String> removed = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(in.toFile())) {
            PDDocumentCatalog catalog = doc.getDocumentCatalog();
            COSDictionary cat = catalog.getCOSObject();

            if (cat.containsKey(COSName.OPEN_ACTION)) {
                COSBase open = cat.getDictionaryObject(COSName.OPEN_ACTION);
                // an explicit destination (array) only jumps to a page and may stay
                if (open instanceof COSDictionary action && !allowed(action)) {
                    cat.removeItem(COSName.OPEN_ACTION);
                    removed.add("Aktion beim Öffnen (" + type(action) + ")");
                }
            }
            if (cat.containsKey(COSName.AA)) {
                cat.removeItem(COSName.AA);
                removed.add("Dokument-Zusatzaktionen (AA)");
            }
            PDDocumentNameDictionary names = catalog.getNames();
            if (names != null) {
                if (names.getCOSObject().containsKey(COSName.JAVA_SCRIPT)) {
                    names.getCOSObject().removeItem(COSName.JAVA_SCRIPT);
                    removed.add("JavaScript auf Dokumentebene");
                }
                if (names.getCOSObject().containsKey(COSName.EMBEDDED_FILES)) {
                    names.getCOSObject().removeItem(COSName.EMBEDDED_FILES);
                    removed.add("eingebettete Dateien");
                }
            }
            if (cat.containsKey(COSName.AF)) {
                cat.removeItem(COSName.AF);
            }
            PDAcroForm form = catalog.getAcroForm(null);
            if (form != null && form.getCOSObject().containsKey(COSName.XFA)) {
                form.getCOSObject().removeItem(COSName.XFA);
                removed.add("XFA-Formular");
            }

            int pageNo = 0;
            for (PDPage page : doc.getPages()) {
                pageNo++;
                if (page.getCOSObject().containsKey(COSName.AA)) {
                    page.getCOSObject().removeItem(COSName.AA);
                    removed.add("Seiten-Zusatzaktionen (Seite " + pageNo + ")");
                }
                for (PDAnnotation annot : page.getAnnotations()) {
                    COSDictionary a = annot.getCOSObject();
                    if (a.containsKey(COSName.AA)) {
                        a.removeItem(COSName.AA);
                        removed.add("Zusatzaktionen einer Annotation (Seite " + pageNo + ")");
                    }
                    if (a.getDictionaryObject(COSName.A) instanceof COSDictionary action && !allowed(action)) {
                        a.removeItem(COSName.A);
                        removed.add("Aktion " + type(action) + " in einer Annotation (Seite " + pageNo + ")");
                    }
                }
            }
            doc.save(out.toFile());
        }
        return removed;
    }

    private static boolean allowed(COSDictionary action) {
        return allowed(action, 0);
    }

    /**
     * An action is allowed only if it and all chained follow-up actions (/Next) are allowed types.
     * Chains deeper than 16 (or cyclic chains in a crafted PDF) count as not allowed.
     */
    private static boolean allowed(COSDictionary action, int depth) {
        if (depth > 16 || !ALLOWED_ACTIONS.contains(type(action))) return false;
        COSBase next = action.getDictionaryObject(COSName.NEXT);
        if (next instanceof COSDictionary n) return allowed(n, depth + 1);
        if (next instanceof org.apache.pdfbox.cos.COSArray arr) {
            for (COSBase b : arr) if (b instanceof COSDictionary n && !allowed(n, depth + 1)) return false;
        }
        return true;
    }

    private static String type(COSDictionary action) {
        String s = action.getNameAsString(COSName.S);
        return s == null ? "unbekannt" : s;
    }
}
