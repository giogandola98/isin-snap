package isinsnap;

import isinsnap.IsinExtractor.Match;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs several OCR passes with different settings and merges their results.
 * Each pass has its own embedded Tesseract engine, created once and reused, and the passes
 * run in parallel: the total time is roughly that of the slowest pass.
 */
public final class IsinScanner implements AutoCloseable {

    /** One pass: upscaling factor, page segmentation mode, whitelist on/off. */
    public record Pass(double scale, int psm, boolean whitelist) {
        @Override public String toString() { return "x" + scale + " psm" + psm + (whitelist ? " whitelist" : ""); }
    }

    /**
     * Choices measured on simulated Bloomberg chat screenshots (light text on black, 11-14 px fonts):
     *  - passes without a whitelist never produced a wrong ISIN among the "confident" ones;
     *  - passes with a whitelist recover missed ISINs but sometimes merge words and make mistakes:
     *    their results stay "needs review" unless another pass confirms them.
     */
    public static final List<Pass> DEFAULT_PASSES = List.of(
        new Pass(3.0, 6, false),
        new Pass(2.0, 6, false),
        new Pass(3.0, 6, true),
        new Pass(3.0, 11, true));

    /**
     * @param isins        one entry per distinct ISIN, in order of appearance
     * @param occurrences  how many times each ISIN appears in the capture
     */
    public record Result(BufferedImage image, List<Match> isins, Map<Match, Integer> occurrences,
                         String ocrText, long millis) {
        public int occurrences(Match m) { return occurrences.getOrDefault(m, 1); }
        /** Total ISIN mentions in the capture, duplicates included. */
        public int totalMentions() { return occurrences.values().stream().mapToInt(Integer::intValue).sum(); }
    }

    private final List<Pass> passes;
    private final List<TesseractOcr> engines = new ArrayList<>();
    private final ExecutorService pool;

    public IsinScanner() throws IOException { this(DEFAULT_PASSES); }

    public IsinScanner(List<Pass> passes) throws IOException {
        this.passes = passes;
        try {
            for (Pass p : passes) engines.add(new TesseractOcr(p.psm(), p.whitelist()));
        } catch (IOException | RuntimeException | LinkageError e) {
            engines.forEach(TesseractOcr::close);
            throw e;
        }
        pool = Executors.newFixedThreadPool(passes.size(), r -> {
            Thread t = new Thread(r, "ocr");
            t.setDaemon(true);
            return t;
        });
    }

    public Result scan(BufferedImage image) throws Exception {
        long t0 = System.currentTimeMillis();
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < passes.size(); i++) {
            Pass p = passes.get(i);
            TesseractOcr engine = engines.get(i);
            futures.add(pool.submit(() -> engine.recognize(ImagePrep.prepare(image, p.scale()))));
        }
        IsinExtractor.Collector collector = new IsinExtractor.Collector();
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < passes.size(); i++) {
            String text = futures.get(i).get();
            collector.addText(text, !passes.get(i).whitelist());
            all.append("── pass ").append(passes.get(i)).append(" ──\n").append(text.strip()).append("\n\n");
        }
        List<Match> isins = collector.results();
        Map<Match, Integer> occ = new LinkedHashMap<>();
        for (Match m : isins) occ.put(m, collector.occurrences(m));
        return new Result(image, isins, occ, all.toString(), System.currentTimeMillis() - t0);
    }

    @Override public void close() {
        pool.shutdownNow();
        engines.forEach(TesseractOcr::close);
    }
}
