package de.aronhomberg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads the optional validators from their official sources into a tools folder
 * ({@link AppDirs#downloadTarget()}), only when the user asks for it:
 * <ul>
 *   <li>KoSIT validator ({@code itplr-kosit/validator}, asset {@code validator-*-standalone.jar}) plus the
 *       XRechnung configuration ({@code itplr-kosit/validator-configuration-xrechnung}) → {@code kosit-validator/}</li>
 *   <li>veraPDF (installer from software.verapdf.org, installed headless) → {@code verapdf/}</li>
 *   <li>Mustang CLI ({@code ZUGFeRD/mustangproject}, only for scripts/validate-all.sh) → {@code mustang/}</li>
 * </ul>
 * HTTPS only, redirects only to known hosts, SHA-256 checked when GitHub publishes a digest, zip-slip safe,
 * installed atomically (a cancelled or failed download never leaves a half tool behind).
 *
 * Usage: gradlew pruefprogrammeLaden [-Ptools=kosit,verapdf,mustang]
 */
final class ToolDownloader {
    private ToolDownloader() {}

    enum Tool {
        KOSIT(Validators.KOSIT, "KoSIT-Validator + XRechnung-Konfiguration", "Apache-2.0", 12),
        VERAPDF(Validators.VERAPDF, "veraPDF", "GPL-3.0+ / MPL-2.0", 35),
        MUSTANG("mustang", "Mustang-CLI (nur für scripts/validate-all.sh)", "Apache-2.0", 60);

        final String folder, title, license;
        final int approxMb;

        Tool(String folder, String title, String license, int approxMb) {
            this.folder = folder;
            this.title = title;
            this.license = license;
            this.approxMb = approxMb;
        }

        String source() {
            return switch (this) {
                case KOSIT -> "https://github.com/" + KOSIT_REPO + " + https://github.com/" + KOSIT_CONFIG_REPO;
                case VERAPDF -> VERAPDF_URL;
                case MUSTANG -> "https://github.com/" + MUSTANG_REPO;
            };
        }
    }

    interface Progress {
        void step(String message);
    }

    record Asset(String name, String url, long size, String sha256) {}

    record Release(String tag, List<Asset> assets) {}

    static final String KOSIT_REPO = "itplr-kosit/validator";
    static final String KOSIT_CONFIG_REPO = "itplr-kosit/validator-configuration-xrechnung";
    static final String MUSTANG_REPO = "ZUGFeRD/mustangproject";
    static final String VERAPDF_URL = "https://software.verapdf.org/releases/verapdf-installer.zip";
    static final Set<String> ALLOWED_HOSTS = Set.of("api.github.com", "github.com", "objects.githubusercontent.com",
            "release-assets.githubusercontent.com", "software.verapdf.org");
    static final Pattern KOSIT_JAR = Pattern.compile("validator-.*-standalone\\.jar");
    static final Pattern KOSIT_CONFIG = Pattern.compile("xrechnung-.*-validator-configuration-.*\\.zip");
    static final Pattern MUSTANG_JAR = Pattern.compile("Mustang-CLI-.*\\.jar");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER) // redirects are followed manually against ALLOWED_HOSTS
            .build();

    // ------------------------------------------------------------------ CLI

    public static void main(String[] args) throws Exception {
        List<Tool> tools = new ArrayList<>();
        for (String a : args) {
            for (String t : a.split("[,\\s]+")) {
                if (!t.isBlank()) tools.add(Tool.valueOf(t.trim().toUpperCase(Locale.ROOT)));
            }
        }
        if (tools.isEmpty()) tools = List.of(Tool.KOSIT, Tool.VERAPDF);
        Path root = AppDirs.downloadTarget();
        System.out.println("Zielordner: " + root);
        for (Tool t : tools) {
            Path dir = install(t, root, System.out::println);
            System.out.println(t.title + " installiert: " + dir + " (" + installedVersion(dir) + ")");
        }
    }

    // ------------------------------------------------------------------ install

    /** Downloads and installs {@code tool} into {@code root/<tool folder>}; returns that folder. */
    static Path install(Tool tool, Path root, Progress progress) throws IOException, InterruptedException {
        Files.createDirectories(root);
        try (FileChannel ch = FileChannel.open(root.resolve(".download.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock = ch.tryLock();
            if (lock == null) throw new IOException("Download läuft bereits (anderes Programmfenster) – bitte kurz warten und erneut versuchen");
            try {
                cleanupStale(root);
                Path staging = root.resolve(".tmp-" + tool.folder + "-" + UUID.randomUUID());
                Path content = staging.resolve(tool.folder);
                Files.createDirectories(content);
                try {
                    String version = switch (tool) {
                        case KOSIT -> installKosit(content, staging, progress);
                        case VERAPDF -> installVerapdf(content, staging, progress);
                        case MUSTANG -> installMustang(content, progress);
                    };
                    Files.writeString(content.resolve(".version"), version + " (geladen " + LocalDate.now() + ")\n", StandardCharsets.UTF_8);
                    Path target = root.resolve(tool.folder);
                    replaceAtomically(content, target);
                    progress.step(tool.title + " fertig: " + target);
                    return target;
                } finally {
                    OutputLayout.deleteRecursively(staging);
                }
            } finally {
                lock.release();
            }
        }
    }

    private static String installKosit(Path content, Path staging, Progress progress) throws IOException, InterruptedException {
        Release validator = latestRelease(KOSIT_REPO);
        Asset jar = pick(validator, KOSIT_JAR, KOSIT_REPO);
        download(jar, content.resolve(jar.name()), progress);

        Release config = latestRelease(KOSIT_CONFIG_REPO);
        Asset zip = pick(config, KOSIT_CONFIG, KOSIT_CONFIG_REPO);
        Path zipFile = staging.resolve(zip.name());
        download(zip, zipFile, progress);
        unzip(zipFile, content);
        if (Validators.kositJarIn(content) == null) {
            throw new IOException("KoSIT-Konfiguration ohne scenarios.xml – Aufbau des Releases hat sich geändert (" + config.tag() + ")");
        }
        return "validator " + validator.tag() + ", xrechnung-konfiguration " + config.tag();
    }

    private static String installMustang(Path content, Progress progress) throws IOException, InterruptedException {
        Release r = latestRelease(MUSTANG_REPO);
        Asset jar = pick(r, MUSTANG_JAR, MUSTANG_REPO);
        download(jar, content.resolve(jar.name()), progress);
        return "mustang " + r.tag();
    }

    private static String installVerapdf(Path content, Path staging, Progress progress) throws IOException, InterruptedException {
        Path zipFile = staging.resolve("verapdf-installer.zip");
        download(new Asset("verapdf-installer.zip", VERAPDF_URL, -1, null), zipFile, progress);
        Path unpacked = staging.resolve("installer");
        unzip(zipFile, unpacked);
        Path installer;
        try (var s = Files.walk(unpacked)) {
            installer = s.filter(p -> p.getFileName().toString().matches("verapdf-izpack-installer-.*\\.jar"))
                    .findFirst().orElseThrow(() -> new IOException("veraPDF-Installer im Download nicht gefunden"));
        }
        // IzPack installs into an empty or missing folder: the prepared content folder is removed first
        Files.delete(content);
        Path auto = staging.resolve("auto-install.xml");
        Files.writeString(auto, autoInstallXml(content), StandardCharsets.UTF_8);
        progress.step("veraPDF installieren …");
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", installer.toString(), auto.toString())
                .redirectErrorStream(true)
                .redirectOutput(staging.resolve("install.log").toFile())
                .start();
        if (!p.waitFor(10, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("veraPDF-Installation: Zeitüberschreitung");
        }
        if (!Validators.verapdfIn(content)) {
            throw new IOException("veraPDF-Installation fehlgeschlagen (Exit-Code " + p.exitValue() + "): "
                    + Files.readString(staging.resolve("install.log"), StandardCharsets.UTF_8).lines().reduce((a, b) -> b).orElse(""));
        }
        try (DirectoryStream<Path> s = Files.newDirectoryStream(content.resolve("bin"), "cli-*.jar")) {
            for (Path jar : s) return "verapdf " + jar.getFileName().toString().replaceAll("^cli-|\\.jar$", "");
        }
        return "verapdf";
    }

    static String autoInstallXml(Path installPath) {
        String path = installPath.toAbsolutePath().toString().replace("&", "&amp;").replace("<", "&lt;");
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="no"?>
                <AutomatedInstallation langpack="eng">
                  <com.izforge.izpack.panels.htmlhello.HTMLHelloPanel id="welcome"/>
                  <com.izforge.izpack.panels.target.TargetPanel id="install_dir">
                    <installpath>%s</installpath>
                  </com.izforge.izpack.panels.target.TargetPanel>
                  <com.izforge.izpack.panels.packs.PacksPanel id="sdk_pack_select">
                    <pack index="0" name="veraPDF GUI" selected="true"/>
                    <pack index="1" name="veraPDF Batch files" selected="true"/>
                    <pack index="2" name="veraPDF Validation model" selected="false"/>
                    <pack index="3" name="veraPDF Documentation" selected="false"/>
                    <pack index="4" name="veraPDF Sample Plugins" selected="false"/>
                  </com.izforge.izpack.panels.packs.PacksPanel>
                  <com.izforge.izpack.panels.install.InstallPanel id="install"/>
                  <com.izforge.izpack.panels.finish.FinishPanel id="finish"/>
                </AutomatedInstallation>
                """.formatted(path);
    }

    /** Version file written after a download, or a description of a manually installed tool. */
    static String installedVersion(Path toolDir) {
        try {
            Path v = toolDir.resolve(".version");
            if (Files.isRegularFile(v)) return Files.readString(v, StandardCharsets.UTF_8).trim();
        } catch (IOException ignored) {
            // fall through
        }
        return "manuell installiert";
    }

    // ------------------------------------------------------------------ GitHub releases

    static Release latestRelease(String repo) throws IOException, InterruptedException {
        String json = get("https://api.github.com/repos/" + repo + "/releases/latest");
        return parseRelease(json);
    }

    static Release parseRelease(String json) throws IOException {
        JsonNode root = JSON.readTree(json);
        List<Asset> assets = new ArrayList<>();
        for (JsonNode a : root.path("assets")) {
            String digest = a.path("digest").asText("");
            assets.add(new Asset(a.path("name").asText(), a.path("browser_download_url").asText(), a.path("size").asLong(-1),
                    digest.startsWith("sha256:") ? digest.substring(7).toLowerCase(Locale.ROOT) : null));
        }
        return new Release(root.path("tag_name").asText(), assets);
    }

    static Asset pick(Release release, Pattern name, String repo) throws IOException {
        return release.assets().stream().filter(a -> name.matcher(a.name()).matches()).findFirst()
                .orElseThrow(() -> new IOException("Im Release " + release.tag() + " von " + repo + " gibt es keine Datei \""
                        + name.pattern() + "\" – bitte manuell laden: https://github.com/" + repo + "/releases"));
    }

    // ------------------------------------------------------------------ HTTP

    private static String get(String url) throws IOException, InterruptedException {
        HttpResponse<String> r = send(url, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return r.body();
    }

    /** Downloads with retries, checks size and SHA-256 (when known). */
    static void download(Asset asset, Path target, Progress progress) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                progress.step("Lade " + asset.name() + (asset.size() > 0 ? " (" + size(asset.size()) + ")" : "") + " …");
                HttpResponse<InputStream> r = send(asset.url(), HttpResponse.BodyHandlers.ofInputStream());
                long total = asset.size() > 0 ? asset.size() : r.headers().firstValueAsLong("content-length").orElse(-1);
                try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(target)) {
                    byte[] buf = new byte[64 * 1024];
                    long done = 0;
                    int lastPct = -1;
                    for (int n; (n = in.read(buf)) > 0; ) {
                        out.write(buf, 0, n);
                        done += n;
                        int pct = total > 0 ? (int) (done * 100 / total) : -1;
                        if (pct >= 0 && pct / 20 != lastPct / 20) {
                            progress.step("  " + asset.name() + ": " + pct + " %");
                            lastPct = pct;
                        }
                    }
                }
                verify(target, asset);
                return;
            } catch (IOException e) {
                last = e;
                if (e instanceof IntegrityException || attempt == 3) break;
                progress.step("  Verbindungsfehler (" + e.getMessage() + "), neuer Versuch " + (attempt + 1) + "/3 …");
                Thread.sleep(2000L * attempt);
            }
        }
        throw last;
    }

    static String size(long bytes) {
        return bytes >= 1_000_000 ? bytes / 1_000_000 + " MB" : Math.max(1, bytes / 1_000) + " KB";
    }

    static final class IntegrityException extends IOException {
        IntegrityException(String message) {
            super(message);
        }
    }

    static void verify(Path file, Asset asset) throws IOException {
        long size = Files.size(file);
        if (asset.size() > 0 && size != asset.size()) {
            throw new IntegrityException(asset.name() + ": Größe " + size + " statt " + asset.size() + " Bytes");
        }
        if (asset.sha256() != null) {
            String actual = sha256(file);
            if (!actual.equalsIgnoreCase(asset.sha256())) {
                throw new IntegrityException(asset.name() + ": SHA-256 stimmt nicht (" + actual + " statt " + asset.sha256() + ")");
            }
        }
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** Sends a GET request; follows up to 5 redirects, but only to HTTPS hosts in {@link #ALLOWED_HOSTS}. */
    private static <T> HttpResponse<T> send(String url, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
        URI uri = URI.create(url);
        for (int hop = 0; hop < 6; hop++) {
            checkHost(uri);
            HttpRequest.Builder req = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5))
                    .header("User-Agent", "easy-e-rechnung").GET();
            if (uri.getHost().equals("api.github.com")) {
                req.header("Accept", "application/vnd.github+json");
                String token = System.getenv("GITHUB_TOKEN");
                if (token != null && !token.isBlank()) req.header("Authorization", "Bearer " + token);
            }
            HttpResponse<T> r = HTTP.send(req.build(), handler);
            int code = r.statusCode();
            if (code >= 300 && code < 400) {
                uri = uri.resolve(r.headers().firstValue("location").orElseThrow(() -> new IOException("Weiterleitung ohne Ziel: " + url)));
                if (r.body() instanceof InputStream in) in.close();
                continue;
            }
            if (code == 403 || code == 429) {
                throw new IOException("GitHub-Anfragelimit erreicht (HTTP " + code + ") – bitte später erneut versuchen");
            }
            if (code != 200) throw new IOException("HTTP " + code + " für " + uri);
            return r;
        }
        throw new IOException("Zu viele Weiterleitungen: " + url);
    }

    static void checkHost(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || !ALLOWED_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
            throw new IOException("Download von nicht erlaubter Adresse abgelehnt: " + uri);
        }
    }

    // ------------------------------------------------------------------ files

    /** Extracts {@code zip} into {@code dest}; rejects entries that would land outside of {@code dest} (zip slip). */
    static void unzip(Path zip, Path dest) throws IOException {
        Path root = dest.toAbsolutePath().normalize();
        Files.createDirectories(root);
        boolean any = false;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                Path target = root.resolve(e.getName()).normalize();
                if (!target.startsWith(root)) throw new IOException("Unzulässiger Pfad im Archiv: " + e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                any = true;
            }
        }
        if (!any) throw new IOException("Leeres oder ungültiges Archiv: " + zip.getFileName());
    }

    /**
     * Moves {@code staged} to {@code target}. An existing version is kept as {@code <target>.alt} until the move
     * succeeded and restored otherwise. If another process installed the tool in the meantime, its result is kept.
     */
    static void replaceAtomically(Path staged, Path target) throws IOException {
        Path old = target.resolveSibling(target.getFileName() + ".alt");
        OutputLayout.deleteRecursively(old);
        boolean hadOld = Files.exists(target);
        if (hadOld) Files.move(target, old, StandardCopyOption.ATOMIC_MOVE);
        try {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException e) {
            if (hadOld) OutputLayout.deleteRecursively(old); // someone else was faster; keep their fresh copy
            return;
        } catch (IOException e) {
            if (hadOld && !Files.exists(target)) Files.move(old, target, StandardCopyOption.ATOMIC_MOVE);
            throw e;
        }
        if (hadOld) OutputLayout.deleteRecursively(old);
    }

    /** Removes leftovers of cancelled downloads ({@code .tmp-*}, {@code *.alt} next to a complete tool). */
    private static void cleanupStale(Path root) throws IOException {
        try (DirectoryStream<Path> s = Files.newDirectoryStream(root)) {
            for (Path p : s) {
                String n = p.getFileName().toString();
                if (n.startsWith(".tmp-")) OutputLayout.deleteRecursively(p);
                if (n.endsWith(".alt") && Files.isDirectory(root.resolve(n.substring(0, n.length() - 4)))) {
                    OutputLayout.deleteRecursively(p);
                }
            }
        }
    }
}
