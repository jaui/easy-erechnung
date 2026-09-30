package de.aronhomberg;

import org.apache.fontbox.ttf.OTFParser;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.font.FontMappers;
import org.apache.pdfbox.pdmodel.font.FontMapping;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds an installed font by its PDF base name (e.g. "Calibri", "Calibri-Bold"):
 * <ol>
 *   <li>the operating system's font folders (PDFBox {@link FontMappers}, no fallback substitutes)</li>
 *   <li>folders PDFBox does not scan: Microsoft Office for Mac keeps Calibri & co. inside the app bundles
 *       ({@code /Applications/Microsoft Word.app/Contents/Resources/DFonts})</li>
 *   <li>folders from the environment variable {@code EASY_ERECHNUNG_FONTS} (separated by the path separator)</li>
 * </ol>
 * The system property {@code easyerechnung.systemFonts=false} disables the lookup (used to test the
 * "font missing" path on machines that have the font).
 */
final class SystemFonts {
    private SystemFonts() {}

    private static final String[] OFFICE_APPS = {"Microsoft Word", "Microsoft Excel", "Microsoft PowerPoint", "Microsoft Outlook"};
    private static Map<String, Path> extraIndex; // normalized PostScript/family name -> font file

    static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("easyerechnung.systemFonts"));
    }

    /** The installed font for a PDF base name (subset prefix already removed), or null. */
    static TrueTypeFont find(String baseName) throws IOException {
        if (!enabled() || baseName == null || baseName.isBlank()) return null;
        FontMapping<TrueTypeFont> mapping = FontMappers.instance().getTrueTypeFont(baseName, null);
        if (mapping != null && !mapping.isFallback()) return mapping.getFont();
        Path file = extraIndex().get(norm(baseName));
        return file == null ? null : parse(file);
    }

    /** Additional font folders that exist on this machine. */
    static List<Path> extraFolders() {
        List<Path> dirs = new ArrayList<>();
        for (String app : OFFICE_APPS) {
            dirs.add(Path.of("/Applications", app + ".app", "Contents", "Resources", "DFonts"));
        }
        String env = System.getenv("EASY_ERECHNUNG_FONTS");
        if (env != null) for (String p : env.split(File.pathSeparator)) if (!p.isBlank()) dirs.add(Path.of(p.trim()));
        dirs.removeIf(d -> !Files.isDirectory(d));
        return dirs;
    }

    private static synchronized Map<String, Path> extraIndex() {
        if (extraIndex != null) return extraIndex;
        Map<String, Path> index = new HashMap<>();
        for (Path dir : extraFolders()) {
            try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, "*.{ttf,TTF,otf,OTF}")) {
                for (Path f : s) {
                    try (TrueTypeFont font = parse(f)) {
                        if (font == null) continue;
                        index.putIfAbsent(norm(font.getName()), f);
                        if (font.getNaming() != null) {
                            String family = font.getNaming().getFontFamily();
                            String sub = font.getNaming().getFontSubFamily();
                            if (family != null) {
                                index.putIfAbsent(norm(family + ("Regular".equalsIgnoreCase(sub) || sub == null ? "" : "-" + sub)), f);
                            }
                        }
                    } catch (IOException | RuntimeException ignored) {
                        // unreadable font file: skip
                    }
                }
            } catch (IOException ignored) {
                // unreadable folder: skip
            }
        }
        extraIndex = index;
        return index;
    }

    private static TrueTypeFont parse(Path f) throws IOException {
        String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
        RandomAccessReadBufferedFile raf = new RandomAccessReadBufferedFile(f.toFile());
        return n.endsWith(".otf") ? new OTFParser().parse(raf) : new TTFParser().parse(raf);
    }

    /** "Calibri-Bold", "Calibri Bold", "CALIBRI,Bold" -> "calibribold" */
    static String norm(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
