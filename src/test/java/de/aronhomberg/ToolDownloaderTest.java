package de.aronhomberg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** No network: release JSON fixtures are real GitHub API answers saved in src/test/resources. */
class ToolDownloaderTest {

    private static String fixture(String name) throws IOException {
        try (InputStream in = ToolDownloaderTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void picksOfficialAssetsWithDigest() throws Exception {
        ToolDownloader.Release validator = ToolDownloader.parseRelease(fixture("github-release-kosit-validator.json"));
        ToolDownloader.Asset jar = ToolDownloader.pick(validator, ToolDownloader.KOSIT_JAR, ToolDownloader.KOSIT_REPO);
        assertTrue(jar.name().matches("validator-.*-standalone\\.jar"), jar.name());
        assertTrue(jar.url().startsWith("https://github.com/itplr-kosit/validator/releases/download/"));
        assertNotNull(jar.sha256());
        assertEquals(64, jar.sha256().length());
        assertTrue(jar.size() > 1_000_000);

        ToolDownloader.Release config = ToolDownloader.parseRelease(fixture("github-release-kosit-config.json"));
        assertTrue(ToolDownloader.pick(config, ToolDownloader.KOSIT_CONFIG, ToolDownloader.KOSIT_CONFIG_REPO).name().endsWith(".zip"));

        ToolDownloader.Release mustang = ToolDownloader.parseRelease(fixture("github-release-mustang.json"));
        assertTrue(ToolDownloader.pick(mustang, ToolDownloader.MUSTANG_JAR, ToolDownloader.MUSTANG_REPO).name().startsWith("Mustang-CLI-"));
    }

    @Test
    void missingAssetGivesManualDownloadHint() throws Exception {
        ToolDownloader.Release r = ToolDownloader.parseRelease("{\"tag_name\":\"v9\",\"assets\":[{\"name\":\"other.zip\"}]}");
        IOException e = assertThrows(IOException.class, () -> ToolDownloader.pick(r, ToolDownloader.KOSIT_JAR, ToolDownloader.KOSIT_REPO));
        assertTrue(e.getMessage().contains("https://github.com/itplr-kosit/validator/releases"), e.getMessage());
    }

    @Test
    void onlyHttpsToKnownHosts() {
        assertDoesNotThrow(() -> ToolDownloader.checkHost(URI.create("https://release-assets.githubusercontent.com/x")));
        assertDoesNotThrow(() -> ToolDownloader.checkHost(URI.create("https://software.verapdf.org/releases/verapdf-installer.zip")));
        assertThrows(IOException.class, () -> ToolDownloader.checkHost(URI.create("http://github.com/x")));
        assertThrows(IOException.class, () -> ToolDownloader.checkHost(URI.create("https://evil.example.com/validator.jar")));
    }

    @Test
    void verifyChecksSizeAndSha256(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("a.jar");
        Files.writeString(f, "hello", StandardCharsets.UTF_8);
        String sha = ToolDownloader.sha256(f);
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", sha);
        assertDoesNotThrow(() -> ToolDownloader.verify(f, new ToolDownloader.Asset("a.jar", "", 5, sha)));
        assertThrows(ToolDownloader.IntegrityException.class,
                () -> ToolDownloader.verify(f, new ToolDownloader.Asset("a.jar", "", 5, "00" + sha.substring(2))));
        assertThrows(ToolDownloader.IntegrityException.class,
                () -> ToolDownloader.verify(f, new ToolDownloader.Asset("a.jar", "", 6, null)));
    }

    private static Path zip(Path file, String... entries) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (String e : entries) {
                out.putNextEntry(new ZipEntry(e));
                if (!e.endsWith("/")) out.write(e.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }

    @Test
    void unzipExtractsNestedFiles(@TempDir Path tmp) throws Exception {
        Path z = zip(tmp.resolve("c.zip"), "scenarios.xml", "resources/", "resources/a/b.xsl");
        Path dest = tmp.resolve("out");
        ToolDownloader.unzip(z, dest);
        assertTrue(Files.isRegularFile(dest.resolve("scenarios.xml")));
        assertEquals("resources/a/b.xsl", Files.readString(dest.resolve("resources/a/b.xsl")));
    }

    @Test
    void unzipRejectsZipSlip(@TempDir Path tmp) throws Exception {
        Path z = zip(tmp.resolve("evil.zip"), "ok.txt", "../escaped.txt");
        Path dest = tmp.resolve("out");
        IOException e = assertThrows(IOException.class, () -> ToolDownloader.unzip(z, dest));
        assertTrue(e.getMessage().contains("Unzulässiger Pfad"), e.getMessage());
        assertFalse(Files.exists(tmp.resolve("escaped.txt")));
    }

    @Test
    void replaceKeepsOldVersionWhenNewOneIsMissing(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("kosit-validator");
        Files.createDirectories(target);
        Files.writeString(target.resolve(".version"), "old");
        Path staged = tmp.resolve(".tmp-kosit/doesnotexist");     // simulates a failed/cancelled download
        assertThrows(IOException.class, () -> ToolDownloader.replaceAtomically(staged, target));
        assertEquals("old", Files.readString(target.resolve(".version")));
        assertFalse(Files.exists(tmp.resolve("kosit-validator.alt")));
    }

    @Test
    void replaceSwapsInNewVersion(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("verapdf");
        Files.createDirectories(target);
        Files.writeString(target.resolve(".version"), "old");
        Path staged = tmp.resolve(".tmp-v/verapdf");
        Files.createDirectories(staged);
        Files.writeString(staged.resolve(".version"), "new");
        ToolDownloader.replaceAtomically(staged, target);
        assertEquals("new", Files.readString(target.resolve(".version")));
        assertFalse(Files.exists(tmp.resolve("verapdf.alt")));
    }

    @Test
    void autoInstallXmlEscapesPath(@TempDir Path tmp) {
        String xml = ToolDownloader.autoInstallXml(tmp.resolve("a&b").resolve("verapdf"));
        assertTrue(xml.contains("a&amp;b"), xml);
        assertFalse(xml.contains("a&b"));
    }

    @Test
    void sizeIsHumanReadable() {
        assertEquals("499 KB", ToolDownloader.size(499_607));
        assertEquals("10 MB", ToolDownloader.size(10_648_594));
    }

    @Test
    void installedVersionFromVersionFile(@TempDir Path tmp) throws IOException {
        assertEquals("manuell installiert", ToolDownloader.installedVersion(tmp));
        try (OutputStream o = Files.newOutputStream(tmp.resolve(".version"))) {
            o.write("verapdf 1.30.2\n".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals("verapdf 1.30.2", ToolDownloader.installedVersion(tmp));
    }
}
