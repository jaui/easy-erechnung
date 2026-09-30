package de.aronhomberg;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDJavascriptNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionLaunch;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.action.PDPageAdditionalActions;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PdfSanitizerTest {

    /** A PDF with every kind of active content the sanitizer must remove, plus an allowed web link. */
    private static Path activePdf(Path dir) throws Exception {
        Path pdf = dir.resolve("active.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(BundledFonts.regular(doc), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Rechnung September/2026 Betrag 330,00 €");
                cs.endText();
            }
            PDDocumentCatalog cat = doc.getDocumentCatalog();
            cat.setOpenAction(new PDActionJavaScript("app.alert('open')"));
            COSDictionary docAA = new COSDictionary();
            docAA.setItem(COSName.getPDFName("WC"), new PDActionJavaScript("app.alert('close')").getCOSObject());
            cat.getCOSObject().setItem(COSName.AA, docAA);

            PDDocumentNameDictionary names = new PDDocumentNameDictionary(cat);
            PDJavascriptNameTreeNode js = new PDJavascriptNameTreeNode();
            js.setNames(Map.of("init", new PDActionJavaScript("app.alert('js')")));
            names.setJavascript(js);
            PDEmbeddedFile ef = new PDEmbeddedFile(doc, new ByteArrayInputStream("MZ-evil".getBytes(StandardCharsets.UTF_8)));
            PDComplexFileSpecification spec = new PDComplexFileSpecification();
            spec.setFile("tool.exe");
            spec.setEmbeddedFile(ef);
            PDEmbeddedFilesNameTreeNode files = new PDEmbeddedFilesNameTreeNode();
            files.setNames(Map.of("tool.exe", spec));
            names.setEmbeddedFiles(files);
            cat.setNames(names);

            PDAcroForm form = new PDAcroForm(doc);
            form.getCOSObject().setItem(COSName.XFA, new COSArray());
            cat.setAcroForm(form);

            PDPageAdditionalActions pageAA = new PDPageAdditionalActions();
            pageAA.setO(new PDActionJavaScript("app.alert('page')"));
            page.setActions(pageAA);

            PDAnnotationLink launch = new PDAnnotationLink();
            launch.setRectangle(new PDRectangle(72, 600, 100, 20));
            PDActionLaunch l = new PDActionLaunch();
            l.setFile(new PDComplexFileSpecification());
            launch.setAction(l);
            PDAnnotationLink web = new PDAnnotationLink();
            web.setRectangle(new PDRectangle(72, 560, 100, 20));
            PDActionURI uri = new PDActionURI();
            uri.setURI("https://example.org");
            web.setAction(uri);
            // URI action whose /Next chain points to itself (crafted PDF) must not hang the sanitizer
            PDAnnotationLink cyclic = new PDAnnotationLink();
            cyclic.setRectangle(new PDRectangle(72, 520, 100, 20));
            PDActionURI loop = new PDActionURI();
            loop.setURI("https://example.org/loop");
            loop.getCOSObject().setItem(COSName.NEXT, loop.getCOSObject());
            cyclic.setAction(loop);
            page.setAnnotations(List.of(launch, web, cyclic));
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    @Test
    void removesActiveContentAndKeepsLinksAndText(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("clean.pdf");
        List<String> removed = PdfSanitizer.sanitize(activePdf(tmp), out);

        String all = String.join(" | ", removed);
        for (String expected : List.of("Aktion beim Öffnen (JavaScript)", "Dokument-Zusatzaktionen", "JavaScript auf Dokumentebene",
                "eingebettete Dateien", "XFA-Formular", "Seiten-Zusatzaktionen", "Aktion Launch")) {
            assertTrue(all.contains(expected), expected + " missing in: " + all);
        }

        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            COSDictionary cat = doc.getDocumentCatalog().getCOSObject();
            assertFalse(cat.containsKey(COSName.OPEN_ACTION));
            assertFalse(cat.containsKey(COSName.AA));
            PDDocumentNameDictionary names = doc.getDocumentCatalog().getNames();
            assertTrue(names == null || (names.getJavaScript() == null && names.getEmbeddedFiles() == null));
            assertFalse(doc.getDocumentCatalog().getAcroForm(null).getCOSObject().containsKey(COSName.XFA));
            PDPage page = doc.getPage(0);
            assertFalse(page.getCOSObject().containsKey(COSName.AA));
            List<PDAnnotation> annots = page.getAnnotations();
            assertNull(annots.get(0).getCOSObject().getDictionaryObject(COSName.A), "Launch action must be removed");
            assertEquals("https://example.org",
                    ((PDActionURI) ((PDAnnotationLink) annots.get(1)).getAction()).getURI(), "web link must stay");
            assertNull(annots.get(2).getCOSObject().getDictionaryObject(COSName.A), "cyclic chain counts as not allowed");
            assertTrue(new PDFTextStripper().getText(doc).contains("Rechnung September/2026"));
        }
    }

    @Test
    void cleanPdfReportsNothing(@TempDir Path tmp) throws Exception {
        InvoiceResponse.Invoice inv;
        Path xlsx = tmp.resolve("v.xlsx");
        ExcelTemplateWriter.main(new String[]{xlsx.toString()});
        inv = ExcelInvoiceReader.read(xlsx).get(0).data();
        ERechnungService.applyDefaultPeriod(inv);
        Path pdf = tmp.resolve("rendered.pdf");
        InvoicePdfRenderer.render(inv, pdf);
        assertEquals(List.of(), PdfSanitizer.sanitize(pdf, tmp.resolve("out.pdf")));
    }
}
