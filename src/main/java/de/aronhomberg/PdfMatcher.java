package de.aronhomberg;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Suggests which original PDF belongs to which invoice sheet: invoice number in the file name
 * (strongest hint), otherwise the same year/month (sheet name "2026-08", invoice number "August/2026",
 * service period or invoice date vs. "… 2026_08.pdf" or "… August 2026.pdf"). Each PDF is used once;
 * ambiguous candidates are left unassigned.
 */
final class PdfMatcher {
    private PdfMatcher() {}

    private static final String[] MONTHS = {"januar", "februar", "märz", "april", "mai", "juni",
            "juli", "august", "september", "oktober", "november", "dezember"};
    private static final Pattern YM = Pattern.compile("(20\\d{2})[-_. ]?(0[1-9]|1[0-2])(?!\\d)");
    private static final Pattern MY = Pattern.compile("(?<!\\d)(0[1-9]|1[0-2])[-_. ](20\\d{2})");
    private static final Pattern NAMED = Pattern.compile("(\\p{L}+)[-_ ./]*(20\\d{2})");

    /** Sheet name → suggested PDF. */
    static Map<String, Path> suggest(Map<String, InvoiceResponse.Invoice> sheets, List<Path> pdfs) {
        Map<String, Path> result = new LinkedHashMap<>();
        Set<Path> used = new HashSet<>();
        for (Map.Entry<String, InvoiceResponse.Invoice> e : sheets.entrySet()) {
            int best = 0;
            List<Path> bestPdfs = new ArrayList<>();
            for (Path pdf : pdfs) {
                if (used.contains(pdf)) continue;
                int score = score(e.getKey(), e.getValue(), pdf.getFileName().toString());
                if (score > best) {
                    best = score;
                    bestPdfs.clear();
                    bestPdfs.add(pdf);
                } else if (score == best && score > 0) {
                    bestPdfs.add(pdf);
                }
            }
            if (best > 0 && bestPdfs.size() == 1) {
                result.put(e.getKey(), bestPdfs.get(0));
                used.add(bestPdfs.get(0));
            }
        }
        return result;
    }

    static int score(String sheet, InvoiceResponse.Invoice inv, String fileName) {
        String file = norm(fileName.replaceFirst("(?i)\\.pdf$", ""));
        int score = 0;
        if (inv != null && inv.InvoiceNumber != null && inv.InvoiceNumber.replaceAll("[^\\p{L}\\p{N}]", "").length() >= 3
                && file.contains(norm(inv.InvoiceNumber))) {
            score += 3;
        }
        Set<String> wanted = new HashSet<>(yearMonths(sheet));
        if (inv != null) {
            wanted.addAll(yearMonths(inv.InvoiceNumber));
            if (inv.PeriodStart != null) wanted.add(inv.PeriodStart.substring(0, 7));
            if (wanted.isEmpty() && inv.InvoiceDate != null) wanted.add(inv.InvoiceDate.substring(0, 7));
        }
        for (String ym : yearMonths(fileName)) {
            if (wanted.contains(ym)) {
                score += 2;
                break;
            }
        }
        return score;
    }

    /** All "yyyy-MM" found in a text: 2026-08, 2026_08, 08.2026, August/2026. */
    static Set<String> yearMonths(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) return out;
        String t = text.toLowerCase(Locale.GERMAN);
        Matcher m = YM.matcher(t);
        while (m.find()) out.add(m.group(1) + "-" + m.group(2));
        m = MY.matcher(t);
        while (m.find()) out.add(m.group(2) + "-" + m.group(1));
        m = NAMED.matcher(t);
        while (m.find()) {
            for (int i = 0; i < MONTHS.length; i++) {
                if (MONTHS[i].equals(m.group(1)) || (m.group(1).length() >= 3 && MONTHS[i].startsWith(m.group(1)))) {
                    out.add(LocalDate.of(Integer.parseInt(m.group(2)), i + 1, 1).toString().substring(0, 7));
                }
            }
        }
        return out;
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.GERMAN).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
