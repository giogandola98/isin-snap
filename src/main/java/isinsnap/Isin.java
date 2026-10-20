package isinsnap;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Formal ISIN validation (ISO 6166): shape, country prefix, check digit. */
public final class Isin {
    private Isin() {}

    /** ISO 3166-1 alpha-2 + special prefixes used by numbering agencies. */
    private static final Set<String> PREFIXES = new HashSet<>(Arrays.asList((
        "AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW BY BZ "
      + "CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE EG EH ER ES ET FI FJ FK FM FO FR "
      + "GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU ID IE IL IM IN IO IQ IR IS IT JE JM JO JP "
      + "KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML MM MN MO MP MQ MR MS MT "
      + "MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM PN PR PS PT PW PY QA RE RO RS RU RW "
      + "SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ TC TD TF TG TH TJ TK TL TM TN TO TR TT TV TW TZ UA UG "
      + "UM US UY UZ VA VC VE VG VI VN VU WF WS YE YT ZA ZM ZW "
      // special: international/eurobonds, EU, CUSIP substitutes, Kosovo, Netherlands Antilles (legacy)
      + "XS EU XA XB XC XD XK QS AN").split(" ")));

    static boolean isLetter(char c) { return c >= 'A' && c <= 'Z'; }
    static boolean isDigit(char c)  { return c >= '0' && c <= '9'; }

    /** 2 letters + 9 alphanumerics + 1 digit. */
    public static boolean hasValidShape(String s) {
        if (s == null || s.length() != 12) return false;
        for (int i = 0; i < 12; i++) {
            char c = s.charAt(i);
            if (i < 2) { if (!isLetter(c)) return false; }
            else if (i == 11) { if (!isDigit(c)) return false; }
            else if (!isLetter(c) && !isDigit(c)) return false;
        }
        return true;
    }

    public static boolean hasKnownPrefix(String s) {
        return s != null && s.length() >= 2 && PREFIXES.contains(s.substring(0, 2));
    }

    /** Check digit: letters -> 10..35, then Luhn on the resulting digit string. */
    public static int checkDigit(String first11) {
        StringBuilder digits = new StringBuilder(22);
        for (int i = 0; i < first11.length(); i++) {
            char c = first11.charAt(i);
            if (isDigit(c)) digits.append(c);
            else digits.append(c - 'A' + 10);
        }
        int sum = 0;
        boolean doubleIt = true; // the rightmost digit is doubled (the check digit is appended after it)
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) { d *= 2; if (d > 9) d -= 9; }
            sum += d;
            doubleIt = !doubleIt;
        }
        return (10 - sum % 10) % 10;
    }

    public static boolean isValid(String s) {
        return hasValidShape(s)
            && hasKnownPrefix(s)
            && checkDigit(s.substring(0, 11)) == s.charAt(11) - '0';
    }
}
