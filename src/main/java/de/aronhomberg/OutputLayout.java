package de.aronhomberg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Folder layout of one generated invoice:
 * <pre>
 * &lt;out&gt;/Rechnung_&lt;Nr&gt;/
 *   Rechnung_&lt;Nr&gt;.pdf             final e-invoice (ZUGFeRD / Factur-X, PDF/A-3)
 *   Rechnung_&lt;Nr&gt;-xrechnung.xml   only if an XRechnung was created
 *   _pruefung/                     validator reports + zusammenfassung.txt
 *   _zwischenschritte/             original, intermediate PDFs, factur-x.xml
 * </pre>
 * Files are produced in a staging folder and only moved into place when everything succeeded,
 * so a failed run never destroys the previous valid invoice. Only the files listed above are
 * replaced; anything else in the folder is left alone.
 */
final class OutputLayout {
    static final String CHECKS = "_pruefung";
    static final String WORK = "_zwischenschritte";

    final String name;
    final Path dir;

    private OutputLayout(String name, Path dir) {
        this.name = name;
        this.dir = dir;
    }

    static OutputLayout of(Path outRoot, String invoiceNumber) {
        String name = "Rechnung_" + invoiceNumber.trim().replaceAll("[^\\p{L}\\p{N}._-]+", "_");
        return new OutputLayout(name, outRoot.resolve(name));
    }

    /** A fresh staging layout next to the target (same volume, so the final move is cheap). */
    OutputLayout staging() throws IOException {
        Files.createDirectories(dir.getParent());
        Path tmp = Files.createTempDirectory(dir.getParent(), "." + name + "-");
        OutputLayout s = new OutputLayout(name, tmp);
        Files.createDirectories(s.checks());
        Files.createDirectories(s.work());
        return s;
    }

    Path pdf() { return dir.resolve(name + ".pdf"); }
    Path xrechnung() { return dir.resolve(name + "-xrechnung.xml"); }
    Path checks() { return dir.resolve(CHECKS); }
    Path work() { return dir.resolve(WORK); }
    Path work(String file) { return work().resolve(file); }
    Path check(String file) { return checks().resolve(file); }

    /** Replaces the generated files of {@code this} with the content of {@code staged}. */
    void commit(OutputLayout staged) throws IOException {
        Files.createDirectories(dir);
        for (Path p : List.of(pdf(), xrechnung(), checks(), work())) deleteRecursively(p);
        for (Path p : List.of(staged.pdf(), staged.xrechnung(), staged.checks(), staged.work())) {
            if (Files.exists(p)) Files.move(p, dir.resolve(p.getFileName()), StandardCopyOption.ATOMIC_MOVE);
        }
        deleteRecursively(staged.dir);
    }

    static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(x);
        }
    }
}
