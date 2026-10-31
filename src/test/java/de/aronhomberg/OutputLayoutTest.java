package de.aronhomberg;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class OutputLayoutTest {
    @Test
    void invoiceNumbersWithDifferentPunctuationKeepDistinctDirectories() {
        Path root = Path.of("output");
        assertNotEquals(OutputLayout.of(root, "A/B").dir, OutputLayout.of(root, "A_B").dir);
        assertNotEquals(OutputLayout.of(root, "A/B").dir, OutputLayout.of(root, "A~2f~B").dir);
        assertEquals(root.resolve("Rechnung_A_B"), OutputLayout.of(root, "A_B").dir);
    }
}
