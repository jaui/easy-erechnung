package de.aronhomberg;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Official validators, each one switchable:
 * <ul>
 *   <li>Mustang (in-process): XML schema + EN16931 schematron, for PDFs also PDF/A via its bundled veraPDF</li>
 *   <li>KoSIT validator (subprocess) with the XRechnung configuration: scenarios "EN16931 (CII)",
 *       "EN16931 XRechnung (CII)", ...</li>
 *   <li>veraPDF CLI (subprocess), profile PDF/A-3b</li>
 * </ul>
 * External tools are looked up in {@link #searchRoots()}: %LOCALAPPS%, the user folder and the program
 * folder {@code tools/} (see {@link AppDirs}, filled by {@link ToolDownloader}), then C:\localapps / ~/localapps.
 */
final class Validators {
    private Validators() {}

    enum Status { OK, WARN, FAIL, MISSING }

    record Check(String validator, String target, Status status, String summary, Path report) {
        String line() {
            return validator + " | " + target + " | " + status + (summary.isBlank() ? "" : " | " + summary);
        }
    }

    record Settings(boolean mustang, boolean kosit, boolean verapdf) {
        static Settings mustangOnly() { return new Settings(true, false, false); }
    }

    static final String KOSIT = "kosit-validator";
    static final String VERAPDF = "verapdf";

    /** Where a tool was found and why that folder was searched (shown in tooltips and the log). */
    record ToolLocation(Path dir, String origin) {}

    /** OS default tool root: C:\localapps on Windows, ~/localapps elsewhere; LOCALAPPS overrides. */
    static Path localapps() {
        return localapps(System.getenv(), System.getProperty("os.name", ""), Path.of(System.getProperty("user.home")));
    }

    static Path localapps(Map<String, String> env, String osName, Path home) {
        String v = env.get("LOCALAPPS");
        if (v != null && !v.isBlank()) return Path.of(v);
        return osName.toLowerCase(Locale.ROOT).contains("win") ? Path.of("C:/localapps") : home.resolve("localapps");
    }

    /** Tool roots in search order: LOCALAPPS, user folder, program folder tools/, OS default. */
    static List<ToolLocation> searchRoots() {
        return searchRoots(System.getenv(), localapps(), AppDirs.userTools(), AppDirs.programTools());
    }

    static List<ToolLocation> searchRoots(Map<String, String> env, Path osDefault, Path userTools, Path programTools) {
        List<ToolLocation> roots = new ArrayList<>();
        String v = env.get("LOCALAPPS");
        if (v != null && !v.isBlank()) roots.add(new ToolLocation(Path.of(v), "LOCALAPPS"));
        if (userTools != null) roots.add(new ToolLocation(userTools, "Benutzerordner"));
        if (programTools != null) roots.add(new ToolLocation(programTools, "Programmordner"));
        if (roots.stream().noneMatch(r -> r.dir().equals(osDefault))) roots.add(new ToolLocation(osDefault, "Standard"));
        return roots;
    }

    /** Folder of the first installed KoSIT validator (jar + scenarios.xml), or null. */
    static ToolLocation kosit() {
        for (ToolLocation root : searchRoots()) {
            Path dir = root.dir().resolve(KOSIT);
            if (kositJarIn(dir) != null) return new ToolLocation(dir, root.origin());
        }
        return null;
    }

    /** Folder of the first installed veraPDF (bin/ with the CLI jar), or null. */
    static ToolLocation verapdf() {
        for (ToolLocation root : searchRoots()) {
            Path dir = root.dir().resolve(VERAPDF);
            if (verapdfIn(dir)) return new ToolLocation(dir, root.origin());
        }
        return null;
    }

    static Path kositJarIn(Path dir) {
        if (!Files.isDirectory(dir) || !Files.isRegularFile(dir.resolve("scenarios.xml"))) return null;
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, "validator-*-standalone.jar")) {
            for (Path p : s) return p;
        } catch (IOException ignored) {
            // treated as not installed
        }
        return null;
    }

    static boolean verapdfIn(Path dir) {
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir.resolve("bin"), "cli-*.jar")) {
            return s.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }

    static Path kositJar() {
        ToolLocation l = kosit();
        return l == null ? null : kositJarIn(l.dir());
    }

    static boolean verapdfAvailable() {
        return verapdf() != null;
    }

    /** Human readable list of the searched folders, for "not installed" hints. */
    static String searchedFolders(String tool) {
        StringBuilder sb = new StringBuilder();
        for (ToolLocation r : searchRoots()) sb.append("\n  ").append(r.dir().resolve(tool)).append(" (").append(r.origin()).append(')');
        return sb.toString();
    }

    /** Runs all enabled validators for the final PDF and the XML files, reports go to {@code reportDir}. */
    static List<Check> run(Settings s, Path pdf, List<Path> xmls, Path reportDir) {
        List<Check> checks = new ArrayList<>();
        if (s.mustang()) {
            checks.add(mustang(pdf, reportDir));
            for (Path x : xmls) checks.add(mustang(x, reportDir));
        }
        if (s.kosit()) for (Path x : xmls) checks.add(kosit(x, reportDir));
        if (s.verapdf()) checks.add(verapdf(pdf, reportDir));
        return checks;
    }

    static Check mustang(Path file, Path reportDir) {
        String target = file.getFileName().toString();
        Path report = reportDir.resolve(stem(file) + "-mustang.xml");
        try {
            ConvertRechnungenToZugferd.validate(file, report);
            String xml = Files.readString(report, StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("<summary status=\"([a-z]+)\"").matcher(xml);
            String last = null;
            while (m.find()) last = m.group(1);
            int errors = count(xml, "<error"), warnings = count(xml, "<warning");
            int pdfaFailures = count(xml, "status=failed"); // PDF/A (veraPDF) assertions are reported inside <pdf>
            if (!"valid".equals(last)) {
                List<String> parts = new ArrayList<>();
                if (pdfaFailures > 0) parts.add("PDF/A: " + pdfaFailures + " Verstöße" + firstClause(xml));
                if (errors > 0) parts.add(errors + " Fehler");
                if (warnings > 0) parts.add(warnings + " Warnungen");
                return new Check("Mustang", target, Status.FAIL, parts.isEmpty() ? "ungültig" : String.join(", ", parts), report);
            }
            return new Check("Mustang", target, warnings > 0 ? Status.WARN : Status.OK,
                    warnings > 0 ? warnings + " Warnungen" : "gültig", report);
        } catch (Exception e) {
            return new Check("Mustang", target, Status.FAIL, "Abbruch: " + e.getMessage(), report);
        }
    }

    static Check kosit(Path xml, Path reportDir) {
        String target = xml.getFileName().toString();
        ToolLocation loc = kosit();
        if (loc == null) return new Check("KoSIT", target, Status.MISSING, "nicht installiert – herunterladbar", null);
        Path dir = loc.dir();
        Path jar = kositJarIn(dir);
        Path log = reportDir.resolve(stem(xml) + "-kosit.log");
        try {
            Path empty = Files.createTempFile("kosit-stdin", ".in");
            try {
                // KoSIT probes stdin to detect piped input; on Windows that fails without a real stdin file
                Process p = new ProcessBuilder(java(), "-jar", jar.toString(),
                        "-s", dir.resolve("scenarios.xml").toString(), "-r", dir.toString(),
                        "-o", reportDir.toString(), "-h", xml.toAbsolutePath().toString())
                        .redirectInput(empty.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
                if (!p.waitFor(5, TimeUnit.MINUTES)) {
                    p.destroyForcibly();
                    return new Check("KoSIT", target, Status.FAIL, "Zeitüberschreitung", log);
                }
            } finally {
                Files.deleteIfExists(empty);
            }
            String out = Files.readString(log, StandardCharsets.UTF_8);
            Path report = reportDir.resolve(stem(xml) + "-report.xml");
            String scenario = "";
            if (Files.isRegularFile(report)) {
                Matcher m = Pattern.compile("<s:name>([^<]*)</s:name>").matcher(Files.readString(report, StandardCharsets.UTF_8));
                if (m.find()) scenario = m.group(1);
            }
            boolean ok = out.contains("Acceptable:  1");
            int warnings = Files.isRegularFile(report) ? count(Files.readString(report, StandardCharsets.UTF_8), "flag=\"warning\"") : 0;
            Status st = !ok ? Status.FAIL : warnings > 0 ? Status.WARN : Status.OK;
            return new Check("KoSIT", target, st, (ok ? "ACCEPTABLE" : "REJECT") + (scenario.isEmpty() ? "" : " [" + scenario + "]"),
                    Files.isRegularFile(report) ? report : log);
        } catch (Exception e) {
            return new Check("KoSIT", target, Status.FAIL, "Abbruch: " + e.getMessage(), log);
        }
    }

    static Check verapdf(Path pdf, Path reportDir) {
        String target = pdf.getFileName().toString();
        ToolLocation loc = verapdf();
        if (loc == null) return new Check("veraPDF", target, Status.MISSING, "nicht installiert – herunterladbar", null);
        Path report = reportDir.resolve(stem(pdf) + "-verapdf-3b.xml");
        try {
            Path dir = loc.dir();
            String cp = dir.resolve("etc") + File.pathSeparator + dir.resolve("bin") + File.separator + "*";
            Process p = new ProcessBuilder(java(), "-cp", cp, "--add-exports=java.base/sun.security.pkcs=ALL-UNNAMED",
                    "org.verapdf.apps.GreenfieldCliWrapper", "--flavour", "3b", "--format", "xml", pdf.toAbsolutePath().toString())
                    .redirectOutput(report.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!p.waitFor(5, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                return new Check("veraPDF", target, Status.FAIL, "Zeitüberschreitung", report);
            }
            String xml = Files.readString(report, StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("isCompliant=\"(true|false)\"").matcher(xml);
            if (!m.find()) return new Check("veraPDF", target, Status.FAIL, "kein Ergebnis", report);
            boolean ok = m.group(1).equals("true");
            Matcher f = Pattern.compile("failedRules=\"(\\d+)\"").matcher(xml);
            return new Check("veraPDF", target, ok ? Status.OK : Status.FAIL,
                    ok ? "PDF/A-3b konform" : "nicht konform" + (f.find() ? " (" + f.group(1) + " Regeln verletzt)" : ""), report);
        } catch (Exception e) {
            return new Check("veraPDF", target, Status.FAIL, "Abbruch: " + e.getMessage(), report);
        }
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String stem(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /** " (z. B. ISO 19005-3:2012 6.2.11.7.2)" for the first failed PDF/A rule, or "". */
    private static String firstClause(String mustangReport) {
        Matcher m = Pattern.compile("specification=([^,\\]]+), clause=([^,\\]]+)").matcher(mustangReport);
        return m.find() ? " (z. B. " + m.group(1) + " " + m.group(2) + ")" : "";
    }

    private static int count(String s, String token) {
        int n = 0;
        for (int i = s.indexOf(token); i >= 0; i = s.indexOf(token, i + 1)) n++;
        return n;
    }
}
