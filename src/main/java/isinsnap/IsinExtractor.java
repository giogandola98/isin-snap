package isinsnap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts ISINs from OCR text, fixing the typical OCR confusions (O/0, I/1, S/5, B/8, Z/2, G/6).
 *
 * Rules against false positives:
 *  - valid country prefix + correct check digit + at least 2 digits in the body;
 *  - the first two positions must be letters and the last one a digit: corrections there are forced;
 *  - in the body (positions 3-11) only a few substitutions are tried, and when several variants are
 *    valid the ISIN is flagged for review instead of picking one at random.
 */
public final class IsinExtractor {

    /**
     * EXACT     = read as-is and valid;
     * CORRECTED = valid after deterministic fixes (country prefix / last digit / O and I in the body);
     * SUGGESTED = valid after a fix in the body: plausible, but should be checked by eye;
     * AMBIGUOUS = several equally good corrections: a human has to choose.
     */
    public enum Kind {
        EXACT, CORRECTED, SUGGESTED, AMBIGUOUS;
        public boolean confident() { return this == EXACT || this == CORRECTED; }
    }

    /** isin = proposed value; source = what the OCR read; alternatives only for AMBIGUOUS. */
    public record Match(String isin, Kind kind, String source, List<String> alternatives) {
        String key() { return kind == Kind.AMBIGUOUS ? "?" + String.join("|", new TreeSet<>(alternatives)) : isin; }
    }

    /** Digit -> look-alike letter (used for positions 1-2, which must be letters). */
    private static final Map<Character, Character> DIGIT_TO_LETTER = Map.of(
        '0', 'O', '1', 'I', '2', 'Z', '5', 'S', '6', 'G', '8', 'B');

    /** Letter -> look-alike digit (used for the last position, which must be a digit). */
    private static final Map<Character, Character> LETTER_TO_DIGIT = Map.ofEntries(
        Map.entry('O', '0'), Map.entry('D', '0'), Map.entry('Q', '0'), Map.entry('U', '0'),
        Map.entry('I', '1'), Map.entry('L', '1'), Map.entry('T', '7'), Map.entry('Z', '2'),
        Map.entry('S', '5'), Map.entry('G', '6'), Map.entry('B', '8'), Map.entry('A', '4'));

    /** Plausible swaps in the ISIN body (where both letters and digits are allowed). */
    private static final Map<Character, char[]> BODY_ALTERNATIVES = new HashMap<>();
    static {
        String[] groups = { "0DQ", "1L", "5S", "8B", "2Z", "6G" }; // O and I are already normalized to 0 and 1
        for (String g : groups) {
            for (char c : g.toCharArray()) {
                BODY_ALTERNATIVES.put(c, g.replace(String.valueOf(c), "").toCharArray());
            }
        }
    }

    private static final Pattern ALNUM_RUN = Pattern.compile("[A-Z0-9]+");

    /** Maximum body substitutions for a standalone 12-character word. */
    static final int MAX_BODY_FIXES_TOKEN = 2;
    /** For joined words (OCR split the ISIN in two) we are more careful. */
    static final int MAX_BODY_FIXES_JOINED = 1;

    private IsinExtractor() {}

    /**
     * Evaluates a 12-character string; null if it is not (and cannot be turned into) an ISIN.
     *
     * Deterministic fixes (CORRECTED):
     *  - at most ONE of the first two positions read as a digit (1T -> IT); two digits means a number, not an ISIN;
     *  - last position read as a letter (…10S -> …105);
     *  - O and I in the body -> 0 and 1: the most common national codes (WKN, SEDOL, CUSIP) never use
     *    these letters precisely to avoid confusion, so the letter is almost always an OCR error.
     */
    static Match evaluate(String raw, int maxBodyFixes) {
        if (raw.length() != 12) return null;
        char[] c = raw.toCharArray();

        int prefixFixes = 0;
        for (int i = 0; i < 2; i++) {
            if (Isin.isDigit(c[i])) {
                Character l = DIGIT_TO_LETTER.get(c[i]);
                if (l == null || ++prefixFixes > 1) return null;
                c[i] = l;
            }
        }
        if (Isin.isLetter(c[11])) {
            Character d = LETTER_TO_DIGIT.get(c[11]);
            if (d == null) return null;
            c[11] = d;
        }
        if (!Isin.hasKnownPrefix(new String(c))) return null;

        char[] normalized = c.clone();
        for (int i = 2; i <= 10; i++) {
            if (normalized[i] == 'O') normalized[i] = '0';
            else if (normalized[i] == 'I') normalized[i] = '1';
        }
        String forced = new String(normalized);
        if (plausible(forced)) {
            // ISINs with few digits in the body are rare (even SEDOL-based ones like GB00BH4HKS39 have 4+),
            // and they are exactly the ones where a letter/digit swap most easily yields another valid ISIN
            Kind k = bodyDigits(forced) < 4 ? Kind.SUGGESTED : forced.equals(raw) ? Kind.EXACT : Kind.CORRECTED;
            return new Match(forced, k, raw, List.of());
        }
        String withLetters = new String(c);
        if (!withLetters.equals(forced) && plausible(withLetters)) {
            // valid only when O/I are kept as letters: possible but unusual, so it gets reviewed
            return new Match(withLetters, Kind.SUGGESTED, raw, List.of());
        }
        if (maxBodyFixes <= 0) return null;
        c = normalized;

        // Find every valid variant with at most maxBodyFixes substitutions in the body
        // and keep the cheapest one. A tie means ambiguous.
        List<Integer> positions = new ArrayList<>();
        for (int i = 2; i <= 10; i++) if (BODY_ALTERNATIVES.containsKey(c[i])) positions.add(i);

        Map<String, Integer> found = new LinkedHashMap<>();
        for (int k = 1; k <= maxBodyFixes; k++) combine(c, forced, positions, 0, k, found);
        if (found.isEmpty()) return null;

        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(found.entrySet());
        ranked.sort(Map.Entry.comparingByValue());
        int best = ranked.get(0).getValue();
        List<String> tied = ranked.stream().filter(e -> e.getValue() == best).map(Map.Entry::getKey).toList();
        if (tied.size() == 1) {
            return new Match(tied.get(0), Kind.SUGGESTED, raw, List.of());
        }
        List<String> alts = ranked.stream().map(Map.Entry::getKey).toList();
        return new Match(alts.get(0), Kind.AMBIGUOUS, raw, Collections.unmodifiableList(alts));
    }

    private static void combine(char[] c, String forced, List<Integer> positions, int from, int remaining,
                                Map<String, Integer> found) {
        if (remaining == 0) {
            String s = new String(c);
            if (plausible(s)) found.putIfAbsent(s, cost(forced, s));
            return;
        }
        for (int p = from; p < positions.size(); p++) {
            int pos = positions.get(p);
            char orig = c[pos];
            for (char alt : BODY_ALTERNATIVES.get(orig)) {
                c[pos] = alt;
                combine(c, forced, positions, p + 1, remaining - 1, found);
            }
            c[pos] = orig;
        }
    }

    /**
     * Cost of a variant compared with the OCR reading:
     *  - letter -> digit costs 1 (an ISIN body is mostly numeric: this is the most typical error);
     *  - digit -> letter costs 2;
     *  - +1 if the first body character stays a letter (it is almost always a digit: IE00, US0, DE000...).
     */
    static int cost(String from, String to) {
        int cost = 0;
        for (int i = 2; i <= 10; i++) {
            char a = from.charAt(i), b = to.charAt(i);
            if (a == b) continue;
            cost += Isin.isDigit(b) ? 1 : 2;
        }
        if (Isin.isLetter(to.charAt(2))) cost += 1;
        return cost;
    }

    static int bodyDigits(String s) {
        int d = 0;
        for (int i = 2; i <= 10; i++) if (Isin.isDigit(s.charAt(i))) d++;
        return d;
    }

    /** True if a and b share a substring of at least n characters (identical strings excluded). */
    static boolean overlaps(String a, String b, int n) {
        if (a.equals(b)) return false;
        for (int i = 0; i + n <= a.length(); i++) if (b.contains(a.substring(i, i + n))) return true;
        return false;
    }

    /** True if a and b differ in 1-2 positions, all between characters that OCR easily confuses. */
    static boolean confusable(String a, String b) {
        int diff = 0;
        for (int i = 0; i < 12; i++) {
            char x = a.charAt(i), y = b.charAt(i);
            if (x == y) continue;
            if (++diff > 2) return false;
            char[] alts = BODY_ALTERNATIVES.get(x);
            boolean ok = false;
            if (alts != null) for (char c : alts) if (c == y) ok = true;
            if (!ok && i < 2) ok = DIGIT_TO_LETTER.get(y) != null && DIGIT_TO_LETTER.get(y) == x;
            if (!ok && i == 11) ok = LETTER_TO_DIGIT.get(y) != null && LETTER_TO_DIGIT.get(y) == x;
            if (!ok) return false;
        }
        return diff > 0;
    }

    /** Formally valid with at least 2 digits in the body (rejects arbitrary 12-letter words). */
    private static boolean plausible(String s) {
        if (!Isin.isValid(s)) return false;
        int digits = 0;
        for (int i = 2; i <= 10; i++) if (Isin.isDigit(s.charAt(i))) digits++;
        return digits >= 2;
    }

    /** Collects results from several OCR passes, de-duplicating them and resolving ambiguities. */
    public static final class Collector {
        private final LinkedHashMap<String, Match> byKey = new LinkedHashMap<>();

        /** Text from a trusted pass. */
        public void addText(String text) { addText(text, true); }

        /**
         * @param trusted false for passes that read more but also make more mistakes (e.g. with a whitelist):
         *                what only they find stays "needs review" until another pass confirms it.
         */
        public void addText(String text, boolean trusted) {
            this.trusted = trusted;
            passCounts.clear();
            for (String line : text.split("\\R")) addLine(line.toUpperCase(Locale.ROOT));
            // every pass reads the same image: an ISIN's count is the highest count seen in any single pass
            passCounts.forEach((k, n) -> counts.merge(k, n, Math::max));
            this.trusted = true;
        }

        private boolean trusted = true;
        private final Map<String, Integer> passCounts = new HashMap<>();
        private final Map<String, Integer> counts = new HashMap<>();

        /** How many times this ISIN appears in the captured text (duplicates are merged into one row). */
        public int occurrences(Match m) {
            Integer n = counts.get(m.key());
            if (n == null) n = counts.get(m.isin());
            return n == null ? 1 : n;
        }

        private void addLine(String line) {
            // Words are split on whitespace; punctuation at the edges is stripped ("(IT000…)," -> "IT000…").
            // A word with inner punctuation ("IT000…/IT000…", "ISIN:DE000…", "99.5") is split into
            // alphanumeric pieces, which are never joined with other words.
            List<String> words = new ArrayList<>();
            List<Boolean> joinable = new ArrayList<>();
            for (String w : line.trim().split("\\s+")) {
                String t = w.replaceAll("^[^A-Z0-9]+|[^A-Z0-9]+$", "");
                if (t.isEmpty()) continue;
                List<String> parts = new ArrayList<>();
                Matcher m = ALNUM_RUN.matcher(t);
                while (m.find()) parts.add(m.group());
                if (parts.size() == 1) {
                    words.add(t);
                    joinable.add(true);
                } else {
                    for (String part : parts) { words.add(part); joinable.add(false); }
                }
            }

            for (int i = 0; i < words.size(); i++) {
                String t = words.get(i);
                // 1) word of exactly 12 characters: full correction
                if (t.length() == 12) add(evaluate(t, trusted ? MAX_BODY_FIXES_TOKEN : 0));
                // 2) longer word: ISIN glued to other text ("bidIT0005…", or words merged by the OCR).
                //    Every window is tried, but without body fixes and only if the first character after
                //    the country is a digit (true for almost every ISIN; rejects "REF4…", "BIDUS…").
                if (t.length() > 12) {
                    List<Match> hits = new ArrayList<>();
                    for (int s = 0; s + 12 <= t.length(); s++) {
                        Match w = evaluate(t.substring(s, s + 12), 0);
                        if (w != null && Isin.isDigit(w.isin().charAt(2))) hits.add(w);
                    }
                    for (Match h : hits) add(hits.size() == 1 ? h : demote(h));
                }
                // 3) ISIN split in two by the OCR: "CH0038 863350". Never join a letters-only word of
                //    3+ characters (USD, EUR, bid...) or pieces that contained punctuation (prices).
                if (trusted && i + 1 < words.size() && joinable.get(i) && joinable.get(i + 1)) {
                    String u = words.get(i + 1);
                    boolean wordLike = t.length() >= 3 && t.chars().allMatch(ch -> ch >= 'A' && ch <= 'Z');
                    if (!wordLike && t.length() >= 2 && u.length() >= 2 && t.length() + u.length() == 12) {
                        add(evaluate(t + u, MAX_BODY_FIXES_JOINED));
                    }
                }
            }
        }

        private static Match demote(Match m) {
            return m.kind().confident() ? new Match(m.isin(), Kind.SUGGESTED, m.source(), m.alternatives()) : m;
        }

        void add(Match m) {
            if (m == null) return;
            if (!trusted) m = demote(m);
            String key = m.key();
            passCounts.merge(key, 1, Integer::sum);
            Match prev = byKey.get(key);
            if (prev == null || m.kind().ordinal() < prev.kind().ordinal()) {
                // keeps the position of first appearance
                if (prev != null) byKey.replace(key, m); else byKey.put(key, m);
            }
        }

        /**
         * Results in order of appearance.
         *  - an ambiguity disappears if one of its variants was read (or corrected) elsewhere;
         *  - two valid ISINs that differ only by confusable characters (e.g. OCR passes reading 0 and O
         *    at the same spot) become a single AMBIGUOUS result: the checksum alone cannot tell which is right.
         */
        public List<Match> results() {
            List<Match> plain = new ArrayList<>();
            for (Match m : byKey.values()) if (m.kind() != Kind.AMBIGUOUS) plain.add(m);
            Set<String> seen = new LinkedHashSet<>();
            for (Match m : plain) seen.add(m.isin());

            // conflicts between different readings
            Set<String> absorbed = new LinkedHashSet<>();
            Map<String, Match> replacement = new HashMap<>();
            for (int i = 0; i < plain.size(); i++) {
                Match a = plain.get(i);
                if (absorbed.contains(a.isin())) continue;
                List<Match> group = new ArrayList<>(List.of(a));
                for (int j = i + 1; j < plain.size(); j++) {
                    Match b = plain.get(j);
                    if (!absorbed.contains(b.isin()) && confusable(a.isin(), b.isin())) group.add(b);
                }
                if (group.size() > 1) {
                    group.sort((x, y) -> Integer.compare(x.kind().ordinal(), y.kind().ordinal()));
                    List<String> alts = group.stream().map(Match::isin).toList();
                    String sources = String.join(" / ", group.stream().map(Match::source).distinct().toList());
                    replacement.put(a.isin(), new Match(alts.get(0), Kind.AMBIGUOUS, sources, alts));
                    for (Match g : group) if (g != a) absorbed.add(g.isin());
                }
            }

            List<String> confidentIsins = new ArrayList<>();
            for (Match m : plain) if (m.kind().confident()) confidentIsins.add(m.isin());

            List<Match> out = new ArrayList<>();
            for (Match m : byKey.values()) {
                // a suggestion that largely overlaps an already confirmed ISIN is almost always the same
                // ISIN read "off-axis" (a window shifted across merged words): drop it
                if (!m.kind().confident() && confidentIsins.stream().anyMatch(c -> overlaps(c, m.source(), 7))) continue;
                if (m.kind() == Kind.AMBIGUOUS) {
                    if (m.alternatives().stream().anyMatch(seen::contains)) continue;
                    out.add(m);
                } else if (!absorbed.contains(m.isin())) {
                    out.add(replacement.getOrDefault(m.isin(), m));
                }
            }
            return out;
        }
    }

    /** Shortcut for a single text. */
    public static List<Match> extract(String text) {
        Collector c = new Collector();
        c.addText(text);
        return c.results();
    }
}
