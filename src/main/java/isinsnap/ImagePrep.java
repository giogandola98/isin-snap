package isinsnap;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** Image preparation for OCR: grayscale, dark-theme inversion, upscaling, white border. */
public final class ImagePrep {
    private ImagePrep() {}

    /** Maximum side after upscaling (beyond this, Tesseract gets slower without gaining accuracy). */
    private static final int MAX_SIDE = 7000;

    public static BufferedImage prepare(BufferedImage src, double scale) {
        int w = src.getWidth(), h = src.getHeight();

        // 1) grayscale + mean luminance
        BufferedImage gray = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        long sum = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y);
                int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
                int l = (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
                sum += l;
                gray.getRaster().setSample(x, y, 0, l);
            }
        }
        // 2) dark theme (light text on a dark background) -> invert: Tesseract prefers black on white
        if (sum / Math.max(1, (long) w * h) < 120) {
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    gray.getRaster().setSample(x, y, 0, 255 - gray.getRaster().getSample(x, y, 0));
        }

        // 3) bicubic upscaling: on-screen text (10-14 px) is too small for Tesseract
        double s = Math.min(scale, (double) MAX_SIDE / Math.max(w, h));
        s = Math.max(1.0, s);
        int sw = (int) Math.round(w * s), sh = (int) Math.round(h * s);

        // 4) white border: Tesseract often misreads characters touching the edge
        int pad = 24;
        BufferedImage out = new BufferedImage(sw + 2 * pad, sh + 2 * pad, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(gray, pad, pad, sw, sh, null);
        g.dispose();
        return out;
    }
}
