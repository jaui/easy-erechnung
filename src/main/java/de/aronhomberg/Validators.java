package de.aronhomberg;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 * External tools are looked up under %LOCALAPPS% (default C:\localapps).
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

    static Path localapps() {
        String env = System.getenv("LOCALAPPS");
        return Path.of(env == null || env.isBlank() ? "C:/localapps" : env);
    }

    static Path kositDir() { return localapps().resolve("kosit-validator"); }
    static Path verapdfDir() { return localapps().resolve("verapdf"); }

    static Path kositJar() {
        Path dir = kositDir();
        if (!Files.isDirectory(dir) || !Files.isRegularFile(dir.resolve("scenarios.xml"))) return null;
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, "validator-*-standalone.jar")) {
            for (Path p : s) return p;
        } catch (IOException ignored) {
            // treated as not installed
        }
        return null;
    }

    static boolean verapdfAvailable() {
        return Files.isDirectory(verapdfDir().resolve("bin"));
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
            if (!"valid".equals(last)) return new Check("Mustang", target, Status.FAIL, errors + " Fehler, " + warnings + " Warnungen", report);
            return new Check("Mustang", target, warnings > 0 ? Status.WARN : Status.OK,
                    warnings > 0 ? warnings + " Warnungen" : "gültig", report);
        } catch (Exception e) {
            return new Check("Mustang", target, Status.FAIL, "Abbruch: " + e.getMessage(), report);
        }
    }

    static Check kosit(Path xml, Path reportDir) {
        String target = xml.getFileName().toString();
        Path jar = kositJar();
        if (jar == null) return new Check("KoSIT", target, Status.MISSING, "nicht installiert (" + kositDir() + ")", null);
        Path log = reportDir.resolve(stem(xml) + "-kosit.log");
        try {
            Path empty = Files.createTempFile("kosit-stdin", ".in");
            try {
                // KoSIT probes stdin to detect piped input; on Windows that fails without a real stdin file
                Process p = new ProcessBuilder(java(), "-jar", jar.toString(),
                        "-s", kositDir().resolve("scenarios.xml").toString(), "-r", kositDir().toString(),
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
        if (!verapdfAvailable()) return new Check("veraPDF", target, Status.MISSING, "nicht installiert (" + verapdfDir() + ")", null);
        Path report = reportDir.resolve(stem(pdf) + "-verapdf-3b.xml");
        try {
            Path dir = verapdfDir();
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

    private static int count(String s, String token) {
        int n = 0;
        for (int i = s.indexOf(token); i >= 0; i = s.indexOf(token, i + 1)) n++;
        return n;
    }
}
