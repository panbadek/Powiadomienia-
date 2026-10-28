package pl.dzielnik;

/**
 * Rachunki na zdjęciu z aparatu, bez Androida: o ile zmniejszyć, jak obrócić według EXIF, jak nazwać plik.
 * Czysta Java, dzięki temu da się to sprawdzić zwykłymi testami (Photo tylko podaje te rachunki Androidowi).
 */
final class PhotoMath {

    /** Dłuższy bok zdjęcia wysyłanego do Gemini, w pikselach. Zdjęcie z aparatu (12 Mpx i więcej) jest zbędnie duże. */
    static final int MAX_SIDE = 2048;

    /** Jakość JPEG po zmniejszeniu: napisy na zdjęciu zostają czytelne, a plik ma zwykle poniżej 1 MB. */
    static final int JPEG_QUALITY = 85;

    /** Początek nazwy pliku ze zdjęciem; po nim idzie czas zrobienia zdjęcia w milisekundach. */
    private static final String PREFIX = "zdjecie-";
    private static final String SUFFIX = ".jpg";

    private PhotoMath() {}

    /**
     * O ile (potęga dwójki) wolno zmniejszyć zdjęcie już przy wczytywaniu z pliku, żeby oszczędzić pamięć,
     * a dłuższy bok nadal miał co najmniej maxSide pikseli. Dokładne zmniejszenie robi potem {@link #fit}.
     */
    static int sampleSize(int w, int h, int maxSide) {
        if (maxSide < 1) throw new IllegalArgumentException("maxSide < 1");
        int longest = Math.max(w, h);
        int s = 1;
        while (longest / (s * 2) >= maxSide) s *= 2;
        return s;
    }

    /**
     * Rozmiar po zmniejszeniu: dłuższy bok najwyżej maxSide, proporcje zachowane, żaden bok nie spada poniżej 1.
     * Zdjęcie, które już się mieści, zostaje bez zmian (nie powiększam).
     */
    static int[] fit(int w, int h, int maxSide) {
        if (w < 1 || h < 1 || maxSide < 1) throw new IllegalArgumentException("zły rozmiar: " + w + "x" + h);
        int longest = Math.max(w, h);
        if (longest <= maxSide) return new int[]{w, h};
        return new int[]{scale(w, maxSide, longest), scale(h, maxSide, longest)};
    }

    /** value * to / from, zaokrąglone do najbliższej liczby całkowitej, ale nigdy mniej niż 1. */
    private static int scale(int value, int to, int from) {
        long v = (2L * value * to + from) / (2L * from);
        return (int) Math.max(1, v);
    }

    /** Czy obrót według tego kodu EXIF zamienia szerokość z wysokością (kody 5–8: zdjęcie zrobione „na stojąco”). */
    static boolean swapsSides(int exif) {
        return exif >= 5 && exif <= 8;
    }

    /**
     * Przekłada piksele zapisane w pliku na obraz taki, jak widzi go człowiek, według kodu orientacji EXIF
     * (1 = bez zmian, 2 = odbicie poziome, 3 = obrót o 180°, 4 = odbicie pionowe, 5 = transpozycja,
     * 6 = obrót o 90° w prawo, 7 = transwersja, 8 = obrót o 90° w lewo). Przy kodzie spoza 2–8 zwraca ten sam
     * zbiór pikseli bez zmian. Wynik ma rozmiar w × h, a przy {@link #swapsSides} h × w. Piksele leżą wierszami.
     */
    static int[] orient(int[] src, int w, int h, int exif) {
        if (w < 1 || h < 1 || src.length != w * h) throw new IllegalArgumentException("zły rozmiar: " + w + "x" + h);
        if (exif < 2 || exif > 8) return src;
        int[] out = new int[src.length];
        int outW = swapsSides(exif) ? h : w;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int dx;
                int dy;
                switch (exif) {
                    case 2:
                        dx = w - 1 - x;
                        dy = y;
                        break;
                    case 3:
                        dx = w - 1 - x;
                        dy = h - 1 - y;
                        break;
                    case 4:
                        dx = x;
                        dy = h - 1 - y;
                        break;
                    case 5:
                        dx = y;
                        dy = x;
                        break;
                    case 6:
                        dx = h - 1 - y;
                        dy = x;
                        break;
                    case 7:
                        dx = h - 1 - y;
                        dy = w - 1 - x;
                        break;
                    default: // 8
                        dx = y;
                        dy = w - 1 - x;
                        break;
                }
                out[dy * outW + dx] = src[row + x];
            }
        }
        return out;
    }

    /** Nazwa pliku ze zdjęciem zrobionym o danej porze. Każde zdjęcie ma własny plik, więc dwa nie wchodzą sobie w drogę. */
    static String fileName(long millis) {
        return PREFIX + Math.max(0, millis) + SUFFIX;
    }

    /**
     * Czy to nazwa, jaką sama nadaję zdjęciom. Tylko takie nazwy wolno otworzyć (z aparatu i z usługi),
     * więc nikt nie podejrzy innych plików aplikacji przez podanie ścieżki z „..” albo ukośnikiem.
     */
    static boolean validName(String name) {
        if (name == null) return false;
        if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) return false;
        int digits = name.length() - PREFIX.length() - SUFFIX.length();
        if (digits < 1 || digits > 19) return false;
        for (int i = PREFIX.length(); i < PREFIX.length() + digits; i++) {
            char ch = name.charAt(i);
            if (ch < '0' || ch > '9') return false;
        }
        return true;
    }
}
