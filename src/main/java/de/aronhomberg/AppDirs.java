package de.aronhomberg;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Where downloaded validator tools live:
 * <ul>
 *   <li><b>program folder</b> {@code <APP_HOME>/tools} (next to {@code lib/}; from the repo: {@code <repo>/tools})</li>
 *   <li><b>user folder</b>, used when the program folder is write-protected (installed by an admin):
 *       Windows {@code %LOCALAPPDATA%\easy-e-rechnung\tools},
 *       macOS {@code ~/Library/Application Support/easy-e-rechnung/tools},
 *       Linux {@code ${XDG_DATA_HOME:-~/.local/share}/easy-e-rechnung/tools}</li>
 * </ul>
 */
final class AppDirs {
    private AppDirs() {}

    static final String APP = "easy-e-rechnung";

    /** {@code <APP_HOME>/tools}, derived from the location of the running code, or null if unknown. */
    static Path programTools() {
        return programTools(codeLocation(), Path.of(System.getProperty("user.dir")));
    }

    static Path programTools(Path codeLocation, Path workingDir) {
        if (codeLocation != null && Files.isRegularFile(codeLocation) && codeLocation.getParent() != null
                && codeLocation.getParent().getFileName() != null
                && codeLocation.getParent().getFileName().toString().equals("lib")) {
            return codeLocation.getParent().getParent().resolve("tools");        // installed: <APP_HOME>/lib/x.jar
        }
        if (workingDir != null && Files.isRegularFile(workingDir.resolve("build.gradle.kts"))) {
            return workingDir.resolve("tools");                                  // started from the repository
        }
        if (codeLocation != null) {                                             // build/classes/java/main -> repo
            for (Path p = codeLocation; p != null; p = p.getParent()) {
                if (Files.isRegularFile(p.resolve("build.gradle.kts"))) return p.resolve("tools");
            }
        }
        return null;
    }

    static Path userTools() {
        return userTools(System.getenv(), System.getProperty("os.name", ""), Path.of(System.getProperty("user.home")));
    }

    static Path userTools(Map<String, String> env, String osName, Path home) {
        String os = osName.toLowerCase(Locale.ROOT);
        Path base;
        if (os.contains("win")) {
            String local = env.get("LOCALAPPDATA");
            base = local != null && !local.isBlank() ? Path.of(local) : home.resolve("AppData").resolve("Local");
        } else if (os.contains("mac")) {
            base = home.resolve("Library").resolve("Application Support");
        } else {
            String xdg = env.get("XDG_DATA_HOME");
            base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : home.resolve(".local").resolve("share");
        }
        return base.resolve(APP).resolve("tools");
    }

    /** Folders searched for tools, most specific first (user folder before program folder). */
    static List<Path> toolSearchPath() {
        List<Path> dirs = new ArrayList<>();
        dirs.add(userTools());
        Path program = programTools();
        if (program != null) dirs.add(program);
        return dirs;
    }

    /** Where a download goes: the program folder if it is really writable, else the user folder. */
    static Path downloadTarget() {
        return downloadTarget(programTools(), userTools(), AppDirs::canWrite);
    }

    static Path downloadTarget(Path program, Path user, Predicate<Path> writable) {
        return program != null && writable.test(program) ? program : user;
    }

    /**
     * Real write test (create + delete a probe file). {@code Files.isWritable} is unreliable on Windows
     * (ACLs, UAC virtualisation of "Program Files").
     */
    static boolean canWrite(Path dir) {
        try {
            Files.createDirectories(dir);
            Path probe = dir.resolve(".write-test-" + UUID.randomUUID());
            Files.writeString(probe, "x");
            Files.delete(probe);
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    private static Path codeLocation() {
        try {
            CodeSource cs = AppDirs.class.getProtectionDomain().getCodeSource();
            return cs == null ? null : Path.of(cs.getLocation().toURI());
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }
}
