package de.aronhomberg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AppDirsTest {

    @Test
    void installedAppUsesToolsNextToLib(@TempDir Path tmp) throws Exception {
        Path jar = tmp.resolve("easy-e-rechnung/lib/easy-e-rechnung-1.0.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "");
        assertEquals(tmp.resolve("easy-e-rechnung/tools"), AppDirs.programTools(jar, tmp.resolve("somewhere")));
    }

    @Test
    void repositoryUsesRepoTools(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("build.gradle.kts"), "");
        Path classes = tmp.resolve("build/classes/java/main");
        Files.createDirectories(classes);
        assertEquals(tmp.resolve("tools"), AppDirs.programTools(classes, tmp));
        assertEquals(tmp.resolve("tools"), AppDirs.programTools(classes, Path.of("/")));
    }

    @Test
    void userFolderPerOperatingSystem() {
        Path home = Path.of("/home/u");
        assertEquals(Path.of("C:/Users/u/AppData/Local/easy-e-rechnung/tools"),
                AppDirs.userTools(Map.of("LOCALAPPDATA", "C:/Users/u/AppData/Local"), "Windows 11", home));
        assertEquals(home.resolve("Library/Application Support/easy-e-rechnung/tools"),
                AppDirs.userTools(Map.of(), "Mac OS X", home));
        assertEquals(home.resolve(".local/share/easy-e-rechnung/tools"), AppDirs.userTools(Map.of(), "Linux", home));
        assertEquals(Path.of("/xdg/easy-e-rechnung/tools"), AppDirs.userTools(Map.of("XDG_DATA_HOME", "/xdg"), "Linux", home));
    }

    @Test
    void protectedProgramFolderDownloadsIntoUserFolder() {
        Path program = Path.of("/Applications/easy-e-rechnung/tools");
        Path user = Path.of("/home/u/.local/share/easy-e-rechnung/tools");
        assertEquals(user, AppDirs.downloadTarget(program, user, p -> false));   // admin-installed, not writable
        assertEquals(program, AppDirs.downloadTarget(program, user, p -> true));
        assertEquals(user, AppDirs.downloadTarget(null, user, p -> true));       // program folder unknown
    }

    @Test
    void writeProbeIsRealAndLeavesNothing(@TempDir Path tmp) throws Exception {
        assertTrue(AppDirs.canWrite(tmp.resolve("new/tools")));
        try (var s = Files.list(tmp.resolve("new/tools"))) {
            assertEquals(List.of(), s.toList());
        }
    }

    @Test
    void validatorSearchOrder() {
        Path osDefault = Path.of("C:/localapps"), user = Path.of("/u/tools"), program = Path.of("/p/tools");
        List<Validators.ToolLocation> roots = Validators.searchRoots(Map.of("LOCALAPPS", "/explicit"), osDefault, user, program);
        assertEquals(List.of(Path.of("/explicit"), user, program, osDefault), roots.stream().map(Validators.ToolLocation::dir).toList());
        assertEquals(List.of(user, program, osDefault),
                Validators.searchRoots(Map.of(), osDefault, user, program).stream().map(Validators.ToolLocation::dir).toList());
    }

    @Test
    void osDefaultToolRoot() {
        Path home = Path.of("/home/u");
        assertEquals(Path.of("C:/localapps"), Validators.localapps(Map.of(), "Windows 10", home));
        assertEquals(home.resolve("localapps"), Validators.localapps(Map.of(), "Mac OS X", home));
        assertEquals(Path.of("/x"), Validators.localapps(Map.of("LOCALAPPS", "/x"), "Linux", home));
    }

    @Test
    void installedToolIsFoundInFirstRootThatHasIt(@TempDir Path tmp) throws Exception {
        Path kosit = tmp.resolve("user/kosit-validator");
        Files.createDirectories(kosit);
        assertNull(Validators.kositJarIn(kosit));                       // jar without scenarios.xml is incomplete
        Files.writeString(kosit.resolve("validator-1.6.3-standalone.jar"), "");
        Files.writeString(kosit.resolve("scenarios.xml"), "<scenarios/>");
        assertEquals(kosit.resolve("validator-1.6.3-standalone.jar"), Validators.kositJarIn(kosit));

        Path vera = tmp.resolve("user/verapdf");
        assertFalse(Validators.verapdfIn(vera));
        Files.createDirectories(vera.resolve("bin"));
        Files.writeString(vera.resolve("bin/cli-1.30.2.jar"), "");
        assertTrue(Validators.verapdfIn(vera));
    }
}
