package de.aronhomberg;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Folder layout of one generated invoice:
 * <pre>
 * &lt;out&gt;/Rechnung_&lt;Nr&gt;/
 *   Rechnung_&lt;Nr&gt;.pdf             final e-invoice (ZUGFeRD / Factur-X, PDF/A-3)
 *   Rechnung_&lt;Nr&gt;-factur-x.xml    the XML embedded in the PDF (byte-identical)
 *   Rechnung_&lt;Nr&gt;-xrechnung.xml   only if an XRechnung was created
 *   _pruefung/                     validator reports + zusammenfassung.txt
 *   _zwischenschritte/             original and intermediate PDFs
 *   _letzter-fehlversuch/          only after a run that a validator rejected: its complete output
 * </pre>
 * Files are produced in a staging folder and only moved into place when generation <b>and validation</b>
 * succeeded ({@link #commit}), so a failed or rejected run never replaces the previous valid invoice.
 * A rejected run is kept for diagnosis in {@code _letzter-fehlversuch/} ({@link #keepFailed}).
 * Only the files listed above are replaced; anything else in the folder is left alone.
 */
final class OutputLayout {
    static final String CHECKS = "_pruefung";
    static final String WORK = "_zwischenschritte";
    static final String FAILED = "_letzter-fehlversuch";

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
        Path tmp;
        try {
            Files.createDirectories(dir.getParent());
            tmp = Files.createTempDirectory(dir.getParent(), "." + name + "-");
        } catch (IOException e) {
            throw new IOException("Ausgabeordner nicht beschreibbar: " + dir.getParent()
                    + " – bitte einen anderen Ausgabeordner wählen", e);
        }
        OutputLayout s = new OutputLayout(name, tmp);
        Files.createDirectories(s.checks());
        Files.createDirectories(s.work());
        return s;
    }

    Path pdf() { return dir.resolve(name + ".pdf"); }
    Path facturX() { return dir.resolve(name + "-factur-x.xml"); }
    Path xrechnung() { return dir.resolve(name + "-xrechnung.xml"); }
    Path checks() { return dir.resolve(CHECKS); }
    Path work() { return dir.resolve(WORK); }
    Path work(String file) { return work().resolve(file); }
    Path check(String file) { return checks().resolve(file); }
    Path failed() { return dir.resolve(FAILED); }

    /** The layout of the last rejected run inside this invoice folder. */
    OutputLayout failedLayout() { return new OutputLayout(name, failed()); }

    /**
     * Replaces the generated files of {@code this} with the content of {@code staged} (validated run).
     * The previous files are first moved aside and only deleted after all new files are in place; if a move
     * fails (e.g. the PDF is locked by a viewer or virus scanner), the new files are removed again and the
     * previous invoice is restored, so the folder never ends up with a half old / half new invoice.
     */
    void commit(OutputLayout staged) throws IOException {
        Files.createDirectories(dir);
        Path aside = Files.createTempDirectory(dir, ".alt-");
        List<Path> moved = new ArrayList<>();
        try {
            for (Path p : List.of(pdf(), facturX(), xrechnung(), checks(), work())) {
                if (Files.exists(p)) Files.move(p, aside.resolve(p.getFileName()), StandardCopyOption.ATOMIC_MOVE);
            }
            for (Path p : List.of(staged.pdf(), staged.facturX(), staged.xrechnung(), staged.checks(), staged.work())) {
                if (!Files.exists(p)) continue;
                Path target = dir.resolve(p.getFileName());
                mover.move(p, target);
                moved.add(target);
            }
        } catch (IOException e) {
            for (Path p : moved) deleteRecursively(p);
            try (DirectoryStream<Path> old = Files.newDirectoryStream(aside)) {
                for (Path p : old) Files.move(p, dir.resolve(p.getFileName()), StandardCopyOption.ATOMIC_MOVE);
            }
            deleteRecursively(aside);
            throw new IOException("Rechnung konnte nicht übernommen werden (Datei gesperrt?) – die bisherige Rechnung "
                    + "ist unverändert: " + e.getMessage(), e);
        }
        deleteRecursively(aside);
        deleteRecursively(staged.dir);
        deleteRecursively(failed()); // an older rejected attempt is obsolete now
    }

    /**
     * Keeps a run that a validator rejected in {@code _letzter-fehlversuch/} (replacing an older one) and
     * leaves the previous valid invoice untouched.
     */
    void keepFailed(OutputLayout staged) throws IOException {
        Files.createDirectories(dir);
        deleteRecursively(failed());
        Files.move(staged.dir, failed(), StandardCopyOption.ATOMIC_MOVE);
    }

    /** Moves a new file into place; replaceable in tests to simulate a locked file. */
    interface Mover {
        void move(Path from, Path to) throws IOException;
    }

    static Mover mover = (from, to) -> Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);

    static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(x);
        }
    }
}
