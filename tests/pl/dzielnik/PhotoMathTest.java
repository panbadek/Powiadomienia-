package pl.dzielnik;

import java.util.Arrays;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * Test rachunków na zdjęciu bez Androida: zmniejszanie, obrót według EXIF, nazwy plików.
 * Obrót sprawdzam na dwa niezależne sposoby: z tabeli normy EXIF (po której stronie leży wiersz 0 i kolumna 0)
 * oraz ze składania prostych ruchów (odbicie, obrót o 90° w prawo) na tablicy dwuwymiarowej.
 *
 * javac -encoding UTF-8 -d out app/src/main/java/pl/dzielnik/PhotoMath.java tests/pl/dzielnik/PhotoMathTest.java
 * java -cp out pl.dzielnik.PhotoMathTest
 */
public class PhotoMathTest {

    static int checks = 0;

    static void eq(Object expected, Object actual, String what) {
        checks++;
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) throw new AssertionError(what + "\n  oczekiwano: " + expected + "\n  jest:       " + actual);
    }

    static void ok(boolean cond, String what) {
        checks++;
        if (!cond) throw new AssertionError(what);
    }

    static void throwsIllegal(Runnable r, String what) {
        checks++;
        try {
            r.run();
        } catch (IllegalArgumentException e) {
            return;
        }
        throw new AssertionError("powinien być IllegalArgumentException: " + what);
    }

    // ------------------------------------------------------------ rozmiar

    static void sizeTests() {
        // typowe aparaty: 12 Mpx, 48 Mpx, 50 Mpx, 200 Mpx
        eq(1, PhotoMath.sampleSize(4000, 3000, 2048), "12 Mpx: bez zmniejszania przy wczytywaniu");
        eq(1, PhotoMath.sampleSize(3000, 4000, 2048), "12 Mpx na stojąco");
        eq(2, PhotoMath.sampleSize(4096, 3072, 2048), "dokładnie dwa razy dłuższy bok");
        eq(1, PhotoMath.sampleSize(4095, 3072, 2048), "o piksel za mało na zmniejszenie");
        eq(2, PhotoMath.sampleSize(8000, 6000, 2048), "48 Mpx");
        eq(4, PhotoMath.sampleSize(16000, 12000, 2048), "200 Mpx");
        eq(1, PhotoMath.sampleSize(640, 480, 2048), "małe zdjęcie");
        eq(1, PhotoMath.sampleSize(1, 1, 2048), "jeden piksel");
        eq(1, PhotoMath.sampleSize(0, 0, 2048), "zerowy rozmiar nie psuje rachunku");
        throwsIllegal(() -> PhotoMath.sampleSize(10, 10, 0), "maxSide 0");

        // zdjęcie po wczytaniu ma dłuższy bok >= maxSide, a kolejne zmniejszenie byłoby już za duże
        Random rnd = new Random(1);
        for (int i = 0; i < 100000; i++) {
            int w = 1 + rnd.nextInt(30000), h = 1 + rnd.nextInt(30000), m = 1 + rnd.nextInt(5000);
            int s = PhotoMath.sampleSize(w, h, m);
            int longest = Math.max(w, h);
            ok(s >= 1 && (s & (s - 1)) == 0, "potęga dwójki: " + s);
            ok(s == 1 || longest / s >= m, "po zmniejszeniu bok nadal >= maxSide: " + w + "x" + h + " m=" + m + " s=" + s);
            ok(longest / (s * 2) < m, "większe zmniejszenie zeszłoby poniżej maxSide: " + w + "x" + h + " m=" + m + " s=" + s);
            eq(s, PhotoMath.sampleSize(h, w, m), "symetria względem obrotu");
        }

        int[][] table = {
                {4000, 3000, 2048, 2048, 1536},
                {3000, 4000, 2048, 1536, 2048},
                {2048, 2048, 2048, 2048, 2048},
                {2049, 2049, 2048, 2048, 2048},
                {2049, 1, 2048, 2048, 1},
                {100, 50, 2048, 100, 50},
                {4096, 1, 2048, 2048, 1},
                {1, 4096, 2048, 1, 2048},
                {5000, 3, 2048, 2048, 1},
                {3, 5000, 2048, 1, 2048},
                {4032, 3024, 2048, 2048, 1536},
                {4000, 3000, 1000, 1000, 750},
                {1000, 999, 10, 10, 10},
        };
        for (int[] t : table) {
            int[] got = PhotoMath.fit(t[0], t[1], t[2]);
            eq(Arrays.asList(t[3], t[4]), Arrays.asList(got[0], got[1]), "fit " + t[0] + "x" + t[1] + " do " + t[2]);
        }
        throwsIllegal(() -> PhotoMath.fit(0, 10, 100), "fit: zerowa szerokość");
        throwsIllegal(() -> PhotoMath.fit(10, -1, 100), "fit: ujemna wysokość");
        throwsIllegal(() -> PhotoMath.fit(10, 10, 0), "fit: maxSide 0");

        for (int i = 0; i < 200000; i++) {
            int w = 1 + rnd.nextInt(20000), h = 1 + rnd.nextInt(20000), m = 16 + rnd.nextInt(4081);
            if (rnd.nextInt(4) == 0) h = 1 + rnd.nextInt(40);
            int[] f = PhotoMath.fit(w, h, m);
            int longest = Math.max(w, h);
            ok(f[0] >= 1 && f[1] >= 1, "żaden bok nie spada poniżej 1");
            ok(f[0] <= w && f[1] <= h, "nie powiększam: " + w + "x" + h + " -> " + f[0] + "x" + f[1]);
            ok(Math.max(f[0], f[1]) <= m, "dłuższy bok najwyżej maxSide");
            if (longest <= m) {
                ok(f[0] == w && f[1] == h, "mieści się, więc bez zmian");
            } else {
                eq(m, Math.max(f[0], f[1]), "dłuższy bok dokładnie maxSide: " + w + "x" + h + " m=" + m);
                eq((int) Math.max(1, Math.round((double) w * m / longest)), f[0], "szerokość " + w + "x" + h + " m=" + m);
                eq((int) Math.max(1, Math.round((double) h * m / longest)), f[1], "wysokość " + w + "x" + h + " m=" + m);
                // proporcje: błąd zaokrągleń najwyżej pół piksela na bok
                ok(Math.abs((long) f[0] * h - (long) f[1] * w) <= (long) w + h, "proporcje " + w + "x" + h + " -> " + f[0] + "x" + f[1]);
            }
        }
        // największe możliwe liczby nie przepełniają
        int[] big = PhotoMath.fit(Integer.MAX_VALUE, Integer.MAX_VALUE - 1, 2048);
        eq(Arrays.asList(2048, 2048), Arrays.asList(big[0], big[1]), "ogromne zdjęcie");
    }

    // ------------------------------------------------------------- obrót

    /** Piksele z unikalnymi wartościami: wartość mówi, skąd pochodzi (wiersz, kolumna). */
    static int[] grid(int w, int h) {
        int[] a = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) a[y * w + x] = y * 1000 + x;
        return a;
    }

    // Norma EXIF: po której stronie obrazu widzianego przez człowieka leży wiersz 0 i kolumna 0.
    static final String[] ROW0 = {null, "góra", "góra", "dół", "dół", "lewo", "prawo", "prawo", "lewo"};
    static final String[] COL0 = {null, "lewo", "prawo", "prawo", "lewo", "góra", "góra", "dół", "dół"};

    /** Wzorzec z normy: wynik jako tablica wierszy widzianych przez człowieka. */
    static int[][] bySpec(int[] src, int w, int h, int o) {
        boolean rowsAreHorizontal = ROW0[o].equals("góra") || ROW0[o].equals("dół");
        int ow = rowsAreHorizontal ? w : h;
        int oh = rowsAreHorizontal ? h : w;
        int[][] out = new int[oh][ow];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int dx, dy;
                if (rowsAreHorizontal) {
                    dy = ROW0[o].equals("góra") ? y : h - 1 - y;      // wiersze rosną od swojej strony ku przeciwnej
                    dx = COL0[o].equals("lewo") ? x : w - 1 - x;
                } else {
                    dx = ROW0[o].equals("lewo") ? y : h - 1 - y;
                    dy = COL0[o].equals("góra") ? x : w - 1 - x;
                }
                out[dy][dx] = src[y * w + x];
            }
        }
        return out;
    }

    static int[][] rows(int[] a, int w, int h) {
        int[][] r = new int[h][w];
        for (int y = 0; y < h; y++) System.arraycopy(a, y * w, r[y], 0, w);
        return r;
    }

    static int[][] flipH(int[][] a) {
        int[][] b = new int[a.length][];
        for (int y = 0; y < a.length; y++) {
            b[y] = new int[a[y].length];
            for (int x = 0; x < a[y].length; x++) b[y][x] = a[y][a[y].length - 1 - x];
        }
        return b;
    }

    /** Obrót o 90° w prawo: pierwsza kolumna staje się pierwszym wierszem, czytana od dołu do góry. */
    static int[][] rot90(int[][] a) {
        int h = a.length, w = a[0].length;
        int[][] b = new int[w][h];
        for (int r = 0; r < w; r++) for (int c = 0; c < h; c++) b[r][c] = a[h - 1 - c][r];
        return b;
    }

    /** Wzorzec ze składania ruchów: najpierw ewentualne odbicie poziome, potem obrót w prawo o 0/90/180/270. */
    static int[][] byMoves(int[] src, int w, int h, int o) {
        int[][] a = rows(src, w, h);
        boolean flip = o == 2 || o == 4 || o == 5 || o == 7;
        int turns;
        switch (o) {
            case 3:
            case 4:
                turns = 2;
                break;
            case 5:
            case 8:
                turns = 3;
                break;
            case 6:
            case 7:
                turns = 1;
                break;
            default:
                turns = 0;
        }
        if (flip) a = flipH(a);
        for (int i = 0; i < turns; i++) a = rot90(a);
        return a;
    }

    static void orientTests() {
        int[][] shapes = {{1, 1}, {1, 5}, {5, 1}, {2, 3}, {3, 2}, {4, 4}, {7, 5}, {5, 7}, {16, 9}, {9, 16}};
        for (int[] sh : shapes) {
            int w = sh[0], h = sh[1];
            int[] src = grid(w, h);
            int[] copy = src.clone();
            for (int o = 1; o <= 8; o++) {
                String what = "kod " + o + " dla " + w + "x" + h;
                int[] got = PhotoMath.orient(src, w, h, o);
                ok(Arrays.equals(copy, src), what + ": wejście nie zostało zmienione");
                int ow = PhotoMath.swapsSides(o) ? h : w;
                int oh = PhotoMath.swapsSides(o) ? w : h;
                eq(w * h, got.length, what + ": liczba pikseli");
                int[][] spec = bySpec(src, w, h, o);
                int[][] moves = byMoves(src, w, h, o);
                eq(oh, spec.length, what + ": wzorzec z normy ma tę samą wysokość");
                eq(oh, moves.length, what + ": wzorzec z ruchów ma tę samą wysokość");
                for (int y = 0; y < oh; y++) {
                    ok(Arrays.equals(spec[y], rows(got, ow, oh)[y]), what + ": wiersz " + y + " według normy EXIF\n  norma: "
                            + Arrays.toString(spec[y]) + "\n  jest:  " + Arrays.toString(rows(got, ow, oh)[y]));
                    ok(Arrays.equals(moves[y], rows(got, ow, oh)[y]), what + ": wiersz " + y + " według składania ruchów");
                }
            }
        }

        // kody, które nic nie robią, oddają te same piksele
        int[] g = grid(3, 2);
        for (int o : new int[]{Integer.MIN_VALUE, -1, 0, 1, 9, 10, 255, Integer.MAX_VALUE}) {
            ok(PhotoMath.orient(g, 3, 2, o) == g, "kod " + o + ": bez zmian");
            ok(!PhotoMath.swapsSides(o), "kod " + o + ": bez zamiany boków");
        }
        for (int o = 2; o <= 8; o++) ok(PhotoMath.orient(g, 3, 2, o) != g, "kod " + o + ": nowa tablica");

        // znane przykłady z życia: telefon trzymany pionowo zapisuje kod 6 (obrót w prawo), do góry nogami kod 3
        // obraz 3x2:  A B C / D E F  (A = lewy górny)
        int[] abc = {'A', 'B', 'C', 'D', 'E', 'F'};
        eq(Arrays.toString(new int[]{'D', 'A', 'E', 'B', 'F', 'C'}), Arrays.toString(PhotoMath.orient(abc, 3, 2, 6)), "kod 6: obrót w prawo");
        eq(Arrays.toString(new int[]{'C', 'F', 'B', 'E', 'A', 'D'}), Arrays.toString(PhotoMath.orient(abc, 3, 2, 8)), "kod 8: obrót w lewo");
        eq(Arrays.toString(new int[]{'F', 'E', 'D', 'C', 'B', 'A'}), Arrays.toString(PhotoMath.orient(abc, 3, 2, 3)), "kod 3: obrót o 180");
        eq(Arrays.toString(new int[]{'C', 'B', 'A', 'F', 'E', 'D'}), Arrays.toString(PhotoMath.orient(abc, 3, 2, 2)), "kod 2: odbicie poziome");
        eq(Arrays.toString(new int[]{'D', 'E', 'F', 'A', 'B', 'C'}), Arrays.toString(PhotoMath.orient(abc, 3, 2, 4)), "kod 4: odbicie pionowe");
        eq(Arrays.toString(new int[]{'A', 'D', 'B', 'E', 'C', 'F'}), Arrays.toString(PhotoMath.orient(abc, 3, 2, 5)), "kod 5: transpozycja");
        eq(Arrays.toString(new int[]{'F', 'C', 'E', 'B', 'D', 'A'}), Arrays.toString(PhotoMath.orient(abc, 3, 2, 7)), "kod 7: transwersja");

        // własności: ruchy odwrotne się znoszą
        Random rnd = new Random(5);
        for (int i = 0; i < 300; i++) {
            int w = 1 + rnd.nextInt(40), h = 1 + rnd.nextInt(40);
            int[] src = new int[w * h];
            for (int k = 0; k < src.length; k++) src[k] = rnd.nextInt();
            for (int o : new int[]{2, 3, 4}) {
                ok(Arrays.equals(src, PhotoMath.orient(PhotoMath.orient(src, w, h, o), w, h, o)), "kod " + o + " dwa razy to nic");
            }
            for (int o : new int[]{5, 7}) {
                ok(Arrays.equals(src, PhotoMath.orient(PhotoMath.orient(src, w, h, o), h, w, o)), "kod " + o + " dwa razy to nic");
            }
            ok(Arrays.equals(src, PhotoMath.orient(PhotoMath.orient(src, w, h, 6), h, w, 8)), "6 potem 8 to nic");
            ok(Arrays.equals(src, PhotoMath.orient(PhotoMath.orient(src, w, h, 8), h, w, 6)), "8 potem 6 to nic");
            // cztery obroty w prawo to nic
            int[] r = src;
            int cw = w, ch = h;
            for (int t = 0; t < 4; t++) {
                r = PhotoMath.orient(r, cw, ch, 6);
                int tmp = cw;
                cw = ch;
                ch = tmp;
            }
            ok(Arrays.equals(src, r), "cztery obroty w prawo to nic");
            // piksele tylko zmieniają miejsce
            for (int o = 2; o <= 8; o++) {
                int[] a = PhotoMath.orient(src, w, h, o).clone();
                int[] b = src.clone();
                Arrays.sort(a);
                Arrays.sort(b);
                ok(Arrays.equals(a, b), "kod " + o + ": te same piksele");
            }
        }

        throwsIllegal(() -> PhotoMath.orient(new int[5], 2, 3, 6), "zła długość tablicy");
        throwsIllegal(() -> PhotoMath.orient(new int[0], 0, 0, 6), "pusty obraz");
        throwsIllegal(() -> PhotoMath.orient(new int[5], 2, 2, 1), "zła długość też przy kodzie 1");

        // duże zdjęcie: tyle pikseli, ile daje aparat po zmniejszeniu do 2048
        int w = 2048, h = 1536;
        int[] big = new int[w * h];
        for (int k = 0; k < big.length; k++) big[k] = k;
        long t0 = System.nanoTime();
        int[] turned = PhotoMath.orient(big, w, h, 6);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        eq(big[0], turned[h - 1], "duże zdjęcie: lewy górny piksel trafia w prawy górny róg");
        eq(big[w * h - 1], turned[(w - 1) * h], "duże zdjęcie: prawy dolny piksel trafia w lewy dolny róg");
        ok(ms < 3000, "duże zdjęcie obracam w rozsądnym czasie (" + ms + " ms)");
    }

    // -------------------------------------------------------------- nazwy

    static void nameTests() {
        eq("zdjecie-1730000000000.jpg", PhotoMath.fileName(1730000000000L), "nazwa z czasu");
        eq("zdjecie-0.jpg", PhotoMath.fileName(0), "nazwa dla zera");
        eq("zdjecie-0.jpg", PhotoMath.fileName(-5), "ujemny czas daje zero");
        eq("zdjecie-9223372036854775807.jpg", PhotoMath.fileName(Long.MAX_VALUE), "największy czas");
        for (long v : new long[]{0, 1, 9, 10, 1730000000000L, Long.MAX_VALUE, -1, Long.MIN_VALUE}) {
            ok(PhotoMath.validName(PhotoMath.fileName(v)), "własna nazwa jest dobra: " + v);
        }

        String[] bad = {
                null, "", "zdjecie-.jpg", "zdjecie-1.jpeg", "Zdjecie-1.jpg", "zdjecie-1.JPG", "zdjecie-1.jpg ", " zdjecie-1.jpg",
                "zdjecie-1.jpg\n", "\nzdjecie-1.jpg", "../zdjecie-1.jpg", "foto/zdjecie-1.jpg", "/zdjecie-1.jpg",
                "zdjecie-1/../x.jpg", "zdjecie-12a.jpg", "zdjecie--1.jpg", "zdjecie-+1.jpg", "zdjecie-1.5.jpg",
                "zdjecie-12345678901234567890.jpg", "zdjecie-١٢٣.jpg", "zdjecie-１２３.jpg", "zdjecie-1.jpg.jpg",
                "zdjecie-1.jp", "zdjecie1.jpg", "zdjecie-1jpg", "x", "zdjecie-", ".jpg", "zdjecie-1\u0000.jpg",
                "zdjecie-1.jpg\\..\\x", "praca-1.jpg", "debug.keystore", "..", "."
        };
        for (String b : bad) ok(!PhotoMath.validName(b), "zła nazwa: " + (b == null ? "null" : b.replace("\n", "\\n")));

        // losowe napisy z samych „groźnych” znaków: zgodność z wyrażeniem regularnym
        Pattern pattern = Pattern.compile("zdjecie-[0-9]{1,19}\\.jpg");
        String[] pieces = {"zdjecie-", ".jpg", "0", "5", "9", "a", "/", "..", "\\", "\n", " ", "-", "٣", "jpg", "\u0000"};
        Random rnd = new Random(3);
        int accepted = 0;
        for (int i = 0; i < 300000; i++) {
            StringBuilder b = new StringBuilder();
            int n = 1 + rnd.nextInt(6);
            for (int k = 0; k < n; k++) b.append(pieces[rnd.nextInt(pieces.length)]);
            if (rnd.nextInt(3) == 0) b.insert(0, "zdjecie-");
            if (rnd.nextInt(3) == 0) b.append(".jpg");
            String s = b.toString();
            boolean expected = pattern.matcher(s).matches();
            if (expected) accepted++;
            eq(expected, PhotoMath.validName(s), "zgodność z wyrażeniem regularnym: " + s.replace("\n", "\\n"));
        }
        ok(accepted > 1000, "losowanie trafia też w dobre nazwy (" + accepted + ")");
    }

    public static void main(String[] args) {
        sizeTests();
        orientTests();
        nameTests();
        System.out.println("OK: " + checks + " sprawdzeń");
    }
}
