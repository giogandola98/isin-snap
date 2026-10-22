package isinsnap;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.tesseract.TessBaseAPI;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Embedded OCR: Tesseract 5 called in-process through JavaCPP.
 *
 * Nothing has to be installed: the native libraries for Windows, macOS and Linux and the English
 * model are inside the jar. JavaCPP extracts the right natives to its cache on first use
 * (~/.javacpp/cache, override with -Dorg.bytedeco.javacpp.cachedir=...), and the model is extracted
 * to ~/.isin-snap/tessdata.
 *
 * One instance = one configured Tesseract engine (page segmentation mode + whitelist). An engine is not
 * thread-safe, so calls are serialized; run several instances to OCR in parallel.
 */
public final class TesseractOcr implements AutoCloseable {

    /**
     * Letters (lowercase too: in chats ISINs are typed any old way), digits and typical chat punctuation.
     * Without punctuation "IT000…/IT000…" would be read as a single string.
     */
    public static final String WHITELIST =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789/.,:;-()@%";

    private static final String MODEL_RESOURCE = "/isinsnap/tessdata/eng.traineddata";
    private static final int OEM_LSTM_ONLY = 1;

    private static volatile Path tessdataDir;

    private final TessBaseAPI api = new TessBaseAPI();
    private final int psm;
    private final boolean whitelist;

    /**
     * @param psm        page segmentation mode (6 = block of text, 11 = sparse text, useful for tables)
     * @param whitelist  restricts the recognized characters (see WHITELIST)
     */
    public TesseractOcr(int psm, boolean whitelist) throws IOException {
        this.psm = psm;
        this.whitelist = whitelist;
        if (api.Init(tessdata().toString(), "eng", OEM_LSTM_ONLY) != 0) {
            api.close();
            throw new IOException("Could not initialize the OCR engine (tessdata: " + tessdata() + ")");
        }
        api.SetPageSegMode(psm);
        if (whitelist) api.SetVariable("tessedit_char_whitelist", WHITELIST);
    }

    /** Recognizes the text in an 8-bit grayscale image (see {@link ImagePrep}). */
    public synchronized String recognize(BufferedImage gray) {
        if (gray.getType() != BufferedImage.TYPE_BYTE_GRAY) throw new IllegalArgumentException("grayscale image expected");
        byte[] pixels = ((DataBufferByte) gray.getRaster().getDataBuffer()).getData();
        int w = gray.getWidth(), h = gray.getHeight();
        api.SetImage(pixels, w, h, 1, w);
        api.SetSourceResolution(300);
        BytePointer text = api.GetUTF8Text();
        try {
            return text == null ? "" : text.getString(StandardCharsets.UTF_8);
        } finally {
            if (text != null) text.deallocate();
            api.Clear();
        }
    }

    public int psm() { return psm; }

    public boolean whitelist() { return whitelist; }

    @Override public synchronized void close() {
        api.End();
        api.close();
    }

    /** Tesseract version, for diagnostics. */
    public static String version() {
        BytePointer v = TessBaseAPI.Version();
        return v == null ? "?" : v.getString();
    }

    /** Extracts the embedded model once (re-extracted only if missing or of a different size). */
    static Path tessdata() throws IOException {
        Path dir = tessdataDir;
        if (dir != null) return dir;
        synchronized (TesseractOcr.class) {
            if (tessdataDir != null) return tessdataDir;
            String override = System.getProperty("isinsnap.tessdata");
            if (override != null && !override.isBlank()) return tessdataDir = Paths.get(override);

            byte[] model;
            try (InputStream in = TesseractOcr.class.getResourceAsStream(MODEL_RESOURCE)) {
                if (in == null) throw new IOException("OCR model missing from the jar: " + MODEL_RESOURCE);
                model = in.readAllBytes();
            }
            IOException last = null;
            for (Path base : new Path[] {
                    Paths.get(System.getProperty("user.home"), ".isin-snap"),
                    Paths.get(System.getProperty("java.io.tmpdir"), "isin-snap-" + System.getProperty("user.name")) }) {
                try {
                    Path d = base.resolve("tessdata");
                    Path f = d.resolve("eng.traineddata");
                    if (!Files.isRegularFile(f) || Files.size(f) != model.length) {
                        Files.createDirectories(d);
                        Path tmp = Files.createTempFile(d, "eng", ".tmp");
                        Files.write(tmp, model);
                        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    }
                    return tessdataDir = d;
                } catch (IOException e) {
                    last = e; // home not writable (some corporate setups): fall back to the temp folder
                }
            }
            throw last;
        }
    }
}
