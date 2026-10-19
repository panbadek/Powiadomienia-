package pl.dzielnik;

import java.util.ArrayList;
import java.util.List;

/**
 * Dzieli tekst na części o co najwyżej {@code limit} znakach.
 *
 * Zasady:
 * - zdanie kończy się kropką (także ! lub ?), a następne zaczyna się od wielkiej litery,
 * - cięcie następuje tylko między zdaniami,
 * - zdanie, które nie mieści się w bieżącej części, trafia w całości do następnej,
 * - zdanie dłuższe niż limit dzielę po słowach (gdy brak spacji, w dowolnym miejscu),
 *   żeby cały tekst został wysłany.
 *
 * Klasa nie korzysta z niczego z Androida, więc da się ją testować zwykłą Javą.
 */
public final class Splitter {

    public static final int MIN_LIMIT = 10;

    private Splitter() {}

    public static List<String> split(String text, int limit) {
        if (limit < MIN_LIMIT) {
            throw new IllegalArgumentException("limit musi wynosić co najmniej " + MIN_LIMIT);
        }
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String t = text.replace("\r\n", "\n").replace('\r', '\n');

        // jednostki do pakowania: {początek, koniec, 1 = musi zaczynać nową część}
        List<int[]> units = new ArrayList<>();
        for (int[] s : sentenceSpans(t)) {
            if (s[1] - s[0] <= limit) units.add(new int[]{s[0], s[1], 0});
            else wrap(t, s[0], s[1], limit, units);
        }

        int cs = -1, ce = -1;
        for (int[] u : units) {
            if (cs < 0) {
                cs = u[0];
                ce = u[1];
            } else if (u[2] == 0 && u[1] - cs <= limit) {
                ce = u[1];
            } else {
                out.add(t.substring(cs, ce));
                cs = u[0];
                ce = u[1];
            }
        }
        if (cs >= 0) out.add(t.substring(cs, ce));
        return out;
    }

    /** Zwraca przedziały {początek, koniec} kolejnych zdań (bez białych znaków na brzegach). */
    static List<int[]> sentenceSpans(String t) {
        List<int[]> r = new ArrayList<>();
        int n = t.length();
        int i = 0;
        while (i < n && ws(t.charAt(i))) i++;
        int start = i;
        while (i < n) {
            char c = t.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                int j = i + 1;
                while (j < n && isEndMark(t.charAt(j))) j++;
                int k = j;
                while (k < n && ws(t.charAt(k))) k++;
                if (k > j && k < n) {
                    int m = k;
                    while (m < n && isOpener(t.charAt(m))) m++;
                    if (m < n && Character.isUpperCase(t.charAt(m))) {
                        r.add(new int[]{start, j});
                        start = k;
                        i = k;
                        continue;
                    }
                }
                i = j;
            } else {
                i++;
            }
        }
        int e = n;
        while (e > start && ws(t.charAt(e - 1))) e--;
        if (e > start) r.add(new int[]{start, e});
        return r;
    }

    /** Dzieli zdanie dłuższe niż limit na kawałki po słowach. */
    private static void wrap(String t, int s, int e, int limit, List<int[]> units) {
        int p = s;
        while (p < e) {
            if (e - p <= limit) {
                units.add(new int[]{p, e, 1});
                return;
            }
            int cut = -1;
            for (int k = p + limit; k > p; k--) {
                if (ws(t.charAt(k))) {
                    cut = k;
                    break;
                }
            }
            int end, next;
            if (cut > 0) {
                end = cut;
                while (end > p && ws(t.charAt(end - 1))) end--;
                next = cut;
                while (next < e && ws(t.charAt(next))) next++;
            } else {
                end = p + limit;
                if (Character.isLowSurrogate(t.charAt(end)) && Character.isHighSurrogate(t.charAt(end - 1))) end--;
                next = end;
            }
            units.add(new int[]{p, end, 1});
            p = next;
        }
    }

    private static boolean ws(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /** Znaki, które mogą stać tuż za kropką i nadal należą do tego zdania: "...", "?!", ”, ), * itp. */
    private static boolean isEndMark(char c) {
        return ".!?…”\"’'»)]*_".indexOf(c) >= 0;
    }

    /** Znaki, które mogą stać przed wielką literą na początku zdania: „, (, myślnik, punktor listy. */
    private static boolean isOpener(char c) {
        return "„“\"«‘'([*_—–- \t".indexOf(c) >= 0;
    }
}
