package de.aronhomberg;

import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.GlyfDescript;
import org.apache.fontbox.ttf.GlyphData;
import org.apache.fontbox.ttf.GlyphDescription;
import org.apache.fontbox.ttf.TTFTable;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.FontMappers;
import org.apache.pdfbox.pdmodel.font.FontMapping;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Repairs missing ToUnicode entries of subset TrueType fonts, as written by Word for
 * ligature glyphs (e.g. "ti", "tt", "ft" in Calibri). Without them the text layer reads
 * "Tä`gkeit" instead of "Tätigkeit" and PDF/A-3u validation fails (ISO 19005-3, 6.2.11.7.2).
 * <p>
 * Each unmapped glyph is matched by outline against the installed system font; ligature
 * glyphs are resolved to their component characters via the font's GSUB ligature table.
 * The page content is not touched, so the visual appearance stays identical.
 */
public final class PdfToUnicodeFixer {
    private PdfToUnicodeFixer() {}

    /** Writes a repaired copy of {@code in} to {@code out}; returns the number of added mappings. */
    public static int fix(Path in, Path out) throws IOException {
        int added = 0;
        try (PDDocument doc = Loader.loadPDF(in.toFile())) {
            Set<COSDictionary> done = new HashSet<>();
            for (PDPage page : doc.getPages()) {
                PDResources res = page.getResources();
                if (res == null) continue;
                for (COSName name : res.getFontNames()) {
                    PDFont font = res.getFont(name);
                    if (font instanceof PDTrueTypeFont tt && done.add(tt.getCOSObject())) {
                        added += fixFont(doc, tt);
                    }
                }
            }
            doc.save(out.toFile());
        }
        return added;
    }

    private static int fixFont(PDDocument doc, PDTrueTypeFont font) throws IOException {
        Map<Integer, String> unicode = new TreeMap<>();
        List<Integer> missing = new ArrayList<>();
        for (int code = 0; code < 256; code++) {
            String u = font.toUnicode(code);
            if (u != null) unicode.put(code, u); // keep every existing mapping
            else if (font.getWidth(code) > 0 && font.codeToGID(code) > 0) missing.add(code);
        }
        if (missing.isEmpty()) return 0;

        String baseName = font.getName().replaceFirst("^[A-Z]{6}[+]", "");
        FontMapping<TrueTypeFont> mapping = FontMappers.instance().getTrueTypeFont(baseName, null);
        if (mapping == null || mapping.isFallback()) return 0;
        TrueTypeFont system = mapping.getFont();
        TrueTypeFont subset = font.getTrueTypeFont();

        // outline -> glyph id; outlines shared by several glyphs (e.g. accents drawn alike) are ambiguous (-1)
        Map<String, Integer> byOutline = new HashMap<>();
        for (int gid = 0; gid < system.getNumberOfGlyphs(); gid++) {
            byOutline.merge(outline(system, gid), gid, (a, b) -> -1);
        }
        CmapLookup cmap = system.getUnicodeCmapLookup();
        Map<Integer, int[]> ligatures = ligatures(system);

        int added = 0;
        for (int code : missing) {
            Integer gid = byOutline.get(outline(subset, font.codeToGID(code)));
            if (gid == null || gid < 0) continue;
            String text = null;
            if (ligatures.containsKey(gid)) { // prefer "fi" over U+FB01 and friends
                StringBuilder sb = new StringBuilder();
                for (int component : ligatures.get(gid)) {
                    String c = glyphText(cmap, component);
                    if (c == null) { sb = null; break; }
                    sb.append(c);
                }
                text = sb == null ? null : sb.toString();
            }
            if (text == null) text = glyphText(cmap, gid);
            if (text != null) {
                unicode.put(code, text);
                added++;
                System.out.println("ToUnicode " + font.getName() + " code " + code + " -> \"" + text + "\"");
            }
        }
        if (added > 0) {
            COSStream cmapStream = doc.getDocument().createCOSStream();
            try (OutputStream os = cmapStream.createOutputStream(COSName.FLATE_DECODE)) {
                os.write(toUnicodeCMap(unicode).getBytes(StandardCharsets.US_ASCII));
            }
            font.getCOSObject().setItem(COSName.TO_UNICODE, cmapStream);
        }
        return added;
    }

    private static String glyphText(CmapLookup cmap, int gid) {
        List<Integer> codes = cmap.getCharCodes(gid);
        return (codes == null || codes.isEmpty()) ? null : new String(Character.toChars(codes.get(0)));
    }

    private static String outline(TrueTypeFont font, int gid) throws IOException {
        GlyphData g = font.getGlyph().getGlyph(gid);
        if (g == null) return "empty:" + font.getAdvanceWidth(gid);
        // full contour: every point with its on-curve flag, plus the contour ends and the advance width
        GlyphDescription d = g.getDescription();
        StringBuilder sb = new StringBuilder().append(font.getAdvanceWidth(gid)).append('|');
        for (int c = 0; c < d.getContourCount(); c++) sb.append(d.getEndPtOfContours(c)).append(',');
        sb.append('|');
        for (int i = 0; i < d.getPointCount(); i++) {
            sb.append(d.getXCoordinate(i)).append(',').append(d.getYCoordinate(i))
              .append((d.getFlags(i) & GlyfDescript.ON_CURVE) != 0 ? 'o' : 'x').append(';');
        }
        return sb.toString();
    }

    /** Ligature glyph id -> component glyph ids, from GSUB lookup type 4 (also inside type 7 extensions). */
    private static Map<Integer, int[]> ligatures(TrueTypeFont font) throws IOException {
        Map<Integer, int[]> out = new HashMap<>();
        TTFTable table = font.getTableMap().get("GSUB");
        if (table == null) return out;
        ByteBuffer b = ByteBuffer.wrap(font.getTableBytes(table));
        int lookupList = u16(b, 8);
        int lookupCount = u16(b, lookupList);
        for (int i = 0; i < lookupCount; i++) {
            int lookup = lookupList + u16(b, lookupList + 2 + 2 * i);
            int type = u16(b, lookup);
            int subCount = u16(b, lookup + 4);
            for (int s = 0; s < subCount; s++) {
                int sub = lookup + u16(b, lookup + 6 + 2 * s);
                int subType = type;
                if (type == 7) {
                    subType = u16(b, sub + 2);
                    sub += b.getInt(sub + 4);
                }
                if (subType != 4) continue;
                int[] coverage = coverage(b, sub + u16(b, sub + 2));
                int setCount = u16(b, sub + 4);
                for (int k = 0; k < setCount && k < coverage.length; k++) {
                    int set = sub + u16(b, sub + 6 + 2 * k);
                    int ligCount = u16(b, set);
                    for (int l = 0; l < ligCount; l++) {
                        int lig = set + u16(b, set + 2 + 2 * l);
                        int compCount = u16(b, lig + 2);
                        int[] components = new int[compCount];
                        components[0] = coverage[k];
                        for (int c = 1; c < compCount; c++) components[c] = u16(b, lig + 4 + 2 * (c - 1));
                        out.putIfAbsent(u16(b, lig), components);
                    }
                }
            }
        }
        return out;
    }

    private static int[] coverage(ByteBuffer b, int off) {
        List<Integer> glyphs = new ArrayList<>();
        int count = u16(b, off + 2);
        if (u16(b, off) == 1) {
            for (int i = 0; i < count; i++) glyphs.add(u16(b, off + 4 + 2 * i));
        } else {
            for (int i = 0; i < count; i++) {
                int range = off + 4 + 6 * i;
                for (int g = u16(b, range); g <= u16(b, range + 2); g++) glyphs.add(g);
            }
        }
        return glyphs.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int u16(ByteBuffer b, int off) {
        return b.getShort(off) & 0xFFFF;
    }

    private static String toUnicodeCMap(Map<Integer, String> unicode) {
        StringBuilder sb = new StringBuilder();
        sb.append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
          .append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
          .append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n")
          .append("1 begincodespacerange\n<00> <FF>\nendcodespacerange\n");
        List<Map.Entry<Integer, String>> entries = new ArrayList<>(unicode.entrySet());
        for (int i = 0; i < entries.size(); i += 100) {
            List<Map.Entry<Integer, String>> chunk = entries.subList(i, Math.min(i + 100, entries.size()));
            sb.append(chunk.size()).append(" beginbfchar\n");
            for (Map.Entry<Integer, String> e : chunk) {
                sb.append(String.format("<%02X> <", e.getKey()));
                for (char c : e.getValue().toCharArray()) sb.append(String.format("%04X", (int) c));
                sb.append(">\n");
            }
            sb.append("endbfchar\n");
        }
        sb.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
        return sb.toString();
    }
}
