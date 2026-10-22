package isinsnap;

import isinsnap.IsinExtractor.Kind;
import isinsnap.IsinExtractor.Match;

import java.util.List;

/** Dependency-free tests: java -jar isin-snap.jar --selftest */
public final class SelfTest {
    private static int failures = 0;

    public static int run() {
        // Real ISINs: they check the check-digit algorithm independently of the code
        String[] real = {
            "US0378331005", // Apple
            "DE0007164600", // SAP
            "IE00B4L5Y983", // iShares Core MSCI World
            "IE00B5BMR087", // iShares Core S&P 500
            "LU0274208692", // Xtrackers MSCI World
            "FR0000120271", // TotalEnergies
            "CH0038863350", // Nestlé
            "GB0002634946", // BAE Systems
            "IT0003132476", // Eni
        };
        for (String s : real) check("valid " + s, Isin.isValid(s));
        check("wrong check digit", !Isin.isValid("US0378331006"));
        check("unknown prefix", !Isin.isValid("ZZ0378331005"));

        // Clean read
        expect("IE00B4L5Y983", "Price IE00B4L5Y983 EUR 98.12", Kind.EXACT);
        // O instead of 0 in the body: deterministic fix (national codes never use O and I)
        expect("IE00B4L5Y983", "IEO0B4L5Y983", Kind.CORRECTED);
        // B instead of 8 in the body: plausible but needs review
        expect("IE00B4L5Y983", "IE00B4L5Y9B3", Kind.SUGGESTED);
        // Z instead of 2 in the body
        expect("FR0000120271", "FR0000120Z71", Kind.SUGGESTED);
        // prefix read with a digit (I -> 1)
        expect("IT0003132476", "1T0003132476", Kind.CORRECTED);
        // last position read as a letter
        expect("US0378331005", "US037833100S", Kind.CORRECTED);
        // ISIN split by the OCR
        expect("CH0038863350", "CH0038 863350", Kind.EXACT);
        // ISIN glued to other text
        expect("DE0007164600", "ISIN:DE0007164600EUR", Kind.EXACT);
        // Arbitrary 12-letter words must not pass
        check("no false positive on plain text", IsinExtractor.extract(
            "INTERNATIONAL CONFIGURATION ITALIANISSIMO 123456789012 Total 1,234,567.89").isEmpty());
        // An exact read from another pass confirms a suggestion / resolves an ambiguity
        IsinExtractor.Collector col = new IsinExtractor.Collector();
        col.addText("IE00B4L5Y9B3");
        col.addText("IE00B4L5Y983");
        List<Match> merged = col.results();
        check("merge passes", merged.size() == 1 && merged.get(0).kind() == Kind.EXACT);
        // Read by only one of two passes -> needs review, even if the check digit matches
        IsinExtractor.Collector lone = new IsinExtractor.Collector();
        lone.addText("US0378331005 IT0003132476");
        lone.addText("US0378331005");
        List<Match> l = lone.results();
        check("two-pass agreement " + l, l.size() == 2 && l.get(0).kind() == Kind.EXACT && l.get(1).kind() == Kind.SUGGESTED);
        // Two passes read valid ISINs that differ only by 5/S -> conflict to review
        IsinExtractor.Collector conflict = new IsinExtractor.Collector();
        conflict.addText("IE00B4L5Y983");
        conflict.addText("IE00B4LSY983");
        List<Match> c = conflict.results();
        check("conflict between passes", c.size() == 1 && c.get(0).kind() == Kind.AMBIGUOUS
            && c.get(0).alternatives().containsAll(List.of("IE00B4L5Y983", "IE00B4LSY983")));
        // Bloomberg chat: lowercase, punctuation, prices and sizes on the same line
        List<Match> chat = IsinExtractor.extract(
            "10:32 MROSSI: bid it0005441883/IT0003132476 2mm @ 99.5, offer (XS2357951164)? lmk");
        check("bloomberg chat " + chat, chat.size() == 3 && chat.stream().allMatch(x -> x.kind() == Kind.EXACT));
        // Prices and quantities are never joined into ISINs, even when they add up to 12 characters
        check("prices not joined", IsinExtractor.extract("USD 186,963 675 EUR 158 636 7165 US 0378331005").size() == 1);
        // A 12-digit number is not an ISIN
        check("12-digit number", IsinExtractor.extract("572254586119 505035416014").isEmpty());
        // De-duplication: one row per ISIN, with the number of times it appears
        IsinExtractor.Collector dup = new IsinExtractor.Collector();
        dup.addText("US0378331005\nbid US0378331005 / us0378331005, IT0003132476");
        dup.addText("US0378331005 IT0003132476");   // a second pass over the same image does not add up
        List<Match> d = dup.results();
        check("dedup " + d, d.size() == 2 && dup.occurrences(d.get(0)) == 3 && dup.occurrences(d.get(1)) == 1);

        System.out.println(failures == 0 ? "SELFTEST OK" : "SELFTEST FAILED: " + failures + " errors");
        return failures;
    }

    private static void expect(String isin, String text, Kind kind) {
        List<Match> r = IsinExtractor.extract(text);
        boolean ok = r.size() == 1 && r.get(0).isin().equals(isin) && r.get(0).kind() == kind;
        check("'" + text + "' -> " + isin + " " + kind + (ok ? "" : " (got " + r + ")"), ok);
    }

    private static void check(String name, boolean ok) {
        if (!ok) { failures++; System.out.println("  FAIL " + name); }
    }
}
