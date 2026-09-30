package de.aronhomberg;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfMatcherTest {

    private static InvoiceResponse.Invoice inv(String number, String date) {
        InvoiceResponse.Invoice i = new InvoiceResponse.Invoice();
        i.InvoiceNumber = number;
        i.InvoiceDate = date;
        return i;
    }

    @Test
    void yearMonthsFromDifferentSpellings() {
        assertEquals(Set.of("2026-08"), PdfMatcher.yearMonths("Kunde Rechnung 2026_08.pdf"));
        assertEquals(Set.of("2026-08"), PdfMatcher.yearMonths("2026-08"));
        assertEquals(Set.of("2026-09"), PdfMatcher.yearMonths("September/2026"));
        assertEquals(Set.of("2026-03"), PdfMatcher.yearMonths("Rechnung 03.2026"));
        assertTrue(PdfMatcher.yearMonths("Rechnung 2026").isEmpty());
    }

    @Test
    void assignsByMonthAndUsesEachPdfOnce() {
        Map<String, InvoiceResponse.Invoice> sheets = new LinkedHashMap<>();
        sheets.put("2026-08", inv("August/2026", "2026-08-28"));
        sheets.put("2026-09", inv("September/2026", "2026-09-28"));
        List<Path> pdfs = List.of(Path.of("GF Rechnung 2026_09.pdf"), Path.of("GF Rechnung 2026_08.pdf"), Path.of("Sonstiges.pdf"));

        Map<String, Path> m = PdfMatcher.suggest(sheets, pdfs);

        assertEquals(Path.of("GF Rechnung 2026_08.pdf"), m.get("2026-08"));
        assertEquals(Path.of("GF Rechnung 2026_09.pdf"), m.get("2026-09"));
    }

    @Test
    void invoiceNumberInFileNameWins() {
        Map<String, InvoiceResponse.Invoice> sheets = Map.of("Blatt1", inv("RE-2026-117", "2026-08-28"));
        List<Path> pdfs = List.of(Path.of("Rechnung 2026_08.pdf"), Path.of("RE-2026-117.pdf"));
        assertEquals(Path.of("RE-2026-117.pdf"), PdfMatcher.suggest(sheets, pdfs).get("Blatt1"));
    }

    @Test
    void ambiguousCandidatesStayUnassigned() {
        Map<String, InvoiceResponse.Invoice> sheets = Map.of("2026-08", inv("A-1", "2026-08-28"));
        List<Path> pdfs = List.of(Path.of("x 2026_08.pdf"), Path.of("y 2026-08.pdf"));
        assertFalse(PdfMatcher.suggest(sheets, pdfs).containsKey("2026-08"));
    }
}
