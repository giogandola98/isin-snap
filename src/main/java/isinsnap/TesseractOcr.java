package isinsnap;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * OCR through the Tesseract (5.x) executable, launched as a process.
 * No native library to load into Java: it works the same on Windows, macOS and Linux.
 */
public final class TesseractOcr {

    /**
     * Letters (lowercase too: in chats ISINs are typed any old way), digits and typical chat punctuation.
     * Without punctuation "IT000…/IT000…" would be read as a single string.
     */
    public static final String WHITELIST =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789/.,:;-()@%";

    private final Path exe;

    public TesseractOcr(Path exe) { this.exe = exe; }

    public Path executable() { return exe; }

    /**
     * Looks for tesseract in this order: -Dtesseract.path, TESSERACT_PATH variable,
     * "tesseract" folder next to the jar (portable version), PATH, standard install locations.
     */
    public static Optional<Path> locate() {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        String name = win ? "tesseract.exe" : "tesseract";
        List<Path> candidates = new ArrayList<>();

        String prop = System.getProperty("tesseract.path");
        if (prop != null && !prop.isBlank()) candidates.add(Paths.get(prop));
        String env = System.getenv("TESSERACT_PATH");
        if (env != null && !env.isBlank()) candidates.add(Paths.get(env));

        appDir().ifPresent(d -> candidates.add(d.resolve("tesseract").resolve(name)));

        String path = System.getenv("PATH");
        if (path != null) for (String dir : path.split(File.pathSeparator)) {
            if (!dir.isBlank()) candidates.add(Paths.get(dir, name));
        }
        if (win) {
            for (String base : new String[]{ System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"),
                                             System.getenv("LOCALAPPDATA") + "\\Programs" }) {
                if (base != null) candidates.add(Paths.get(base, "Tesseract-OCR", name));
            }
        } else {
            // GUI apps on macOS don't inherit the shell PATH: add Homebrew explicitly
            candidates.add(Paths.get("/opt/homebrew/bin/tesseract"));
            candidates.add(Paths.get("/usr/local/bin/tesseract"));
            candidates.add(Paths.get("/usr/bin/tesseract"));
        }
        for (Path p : candidates) {
            try {
                // an environment variable may point to the folder instead of the executable
                Path exe = Files.isDirectory(p) ? p.resolve(name) : p;
                if (Files.isRegularFile(exe) && Files.isExecutable(exe)) return Optional.of(exe);
            } catch (Exception ignored) { }
        }
        return Optional.empty();
    }

    private static Optional<Path> appDir() {
        try {
            Path jar = Paths.get(TesseractOcr.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Optional.of(Files.isDirectory(jar) ? jar : jar.getParent());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * @param psm        page segmentation mode (6 = block of text, 11 = sparse text, useful for tables)
     * @param whitelist  restricts the recognized characters (see WHITELIST)
     */
    public String recognize(BufferedImage img, int psm, boolean whitelist) throws IOException, InterruptedException {
        Path tmp = Files.createTempFile("isin-snap-", ".png");
        try {
            ImageIO.write(img, "png", tmp.toFile());
            List<String> cmd = new ArrayList<>(List.of(
                exe.toString(), tmp.toString(), "stdout",
                "-l", "eng", "--oem", "1", "--psm", String.valueOf(psm), "--dpi", "300"));
            if (whitelist) {
                cmd.add("-c");
                cmd.add("tessedit_char_whitelist=" + WHITELIST);
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            byte[] out;
            try (InputStream in = p.getInputStream()) { out = in.readAllBytes(); }
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("Tesseract did not respond within 60 seconds");
            }
            if (p.exitValue() != 0) throw new IOException("Tesseract exited with code " + p.exitValue());
            return new String(out, StandardCharsets.UTF_8);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Tesseract version, for diagnostics. */
    public String version() {
        try {
            Process p = new ProcessBuilder(exe.toString(), "--version").redirectErrorStream(true).start();
            String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(10, TimeUnit.SECONDS);
            return s.lines().findFirst().orElse("?");
        } catch (Exception e) {
            return "?";
        }
    }
}
