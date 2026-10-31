package de.aronhomberg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputLayoutTest {
    @Test
    void folderNamesStayReadable() {
        Path root = Path.of("output");
        assertEquals(root.resolve("Rechnung_2026_001"), OutputLayout.of(root, "2026/001").dir);
        assertEquals(root.resolve("Rechnung_September_2026"), OutputLayout.of(root, " September 2026 ").dir);
        assertEquals("Rechnung_RE-1.2", OutputLayout.of(root, "RE-1.2").name);
    }

    @Test
    void trailingDotIsReplacedBecauseWindowsDropsIt() {
        assertEquals("Rechnung_R1_", OutputLayout.folderName("R1."));
        assertEquals("Rechnung_R1_", OutputLayout.folderName("R1..."));
    }

    @Test
    void folderOfAnotherNumberWithTheSameNameIsNotReplaced(@TempDir Path out) throws IOException {
        OutputLayout first = OutputLayout.of(out, "A/B");
        first.commit(first.staging());
        assertEquals("A/B\n", Files.readString(first.dir.resolve(OutputLayout.MARKER), StandardCharsets.UTF_8));

        OutputLayout second = OutputLayout.of(out, "A_B");
        assertEquals(first.dir, second.dir);
        IOException e = assertThrows(IOException.class, second::staging);
        assertTrue(e.getMessage().contains("\"A/B\"") && e.getMessage().contains("\"A_B\""), e.getMessage());

        // the same number may of course replace its own invoice
        assertDoesNotThrow(() -> first.commit(first.staging()));
    }

    @Test
    void legacyFolderWithoutMarkerIsTakenOver(@TempDir Path out) throws IOException {
        OutputLayout l = OutputLayout.of(out, "2026/001");
        Files.createDirectories(l.dir);
        Files.writeString(l.pdf(), "alt");
        l.commit(l.staging());
        assertEquals("2026/001\n", Files.readString(l.dir.resolve(OutputLayout.MARKER), StandardCharsets.UTF_8));
    }

    @Test
    void rejectedRunAlsoClaimsTheFolder(@TempDir Path out) throws IOException {
        OutputLayout l = OutputLayout.of(out, "X/1");
        l.keepFailed(l.staging());
        assertThrows(IOException.class, () -> OutputLayout.of(out, "X_1").staging());
    }
}
