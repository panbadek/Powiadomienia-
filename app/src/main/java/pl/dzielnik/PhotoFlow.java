package pl.dzielnik;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.File;

/**
 * Droga zdjęcia od przycisku do usługi, bez ekranu: przygotowanie intencji aparatu i obsługa tego, co aparat zwróci.
 * Osobno od MainActivity, żeby dało się to sprawdzić testem bez ekranu. Stan (które zdjęcie i z jakim pytaniem
 * czeka na aparat) leży w ustawieniach, bo aparat potrafi wyprzeć aplikację z pamięci.
 */
final class PhotoFlow {

    /** Nic nie czekało na aparat (spóźnione albo podwójne wywołanie). */
    static final int NOTHING = 0;
    /** Zrezygnowano w aparacie. */
    static final int CANCELED = 1;
    /** Aparat nie zapisał zdjęcia. */
    static final int MISSING = 2;
    /** Zdjęcie poszło do Gemini. */
    static final int SENT = 3;
    /** Nie udało się uruchomić usługi, która wysyła zdjęcie. */
    static final int NOT_STARTED = 4;

    private PhotoFlow() {}

    /**
     * Intencja otwierająca aparat: zdjęcie ma zostać zapisane pod adresem naszego PhotoProvider. Zapamiętuje, które
     * zdjęcie i z jakim pytaniem czeka. Samo uruchomienie aparatu należy do ekranu.
     */
    static Intent beginCapture(Context c, String prompt) {
        Photo.cleanOld(c);
        long now = System.currentTimeMillis();
        String name = PhotoMath.fileName(now);
        // Każde zdjęcie ma własny plik. Dwa w tej samej milisekundzie się nie zdarzą, ale pilnowanie tego nic nie kosztuje.
        while (Photo.file(c, name).exists()) name = PhotoMath.fileName(++now);
        Uri target = Photo.uri(name);
        Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        camera.putExtra(MediaStore.EXTRA_OUTPUT, target);
        // Prawo zapisu do tego jednego adresu dostaje aplikacja aparatu wybrana przez system.
        camera.setClipData(ClipData.newRawUri("", target));
        camera.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        Prefs.setPhotoPending(c, name, prompt == null ? "" : prompt);
        return camera;
    }

    /** Aparat nie chciał się otworzyć: zdjęcie nie czeka. */
    static void abortCapture(Context c) {
        Prefs.clearPhotoPending(c);
    }

    /**
     * Aparat skończył. Gdy zdjęcie jest, idzie do usługi (która je zmniejszy i wyśle do Gemini), a gdy nie ma
     * albo zrezygnowano, plik jest sprzątany.
     *
     * @param cameraOk czy aparat odpowiedział „OK” (a nie „anulowano”)
     * @return jedna ze stałych NOTHING, CANCELED, MISSING, SENT, NOT_STARTED
     */
    static int finishCapture(Context c, boolean cameraOk) {
        String name = Prefs.photoFile(c);
        if (name.isEmpty()) return NOTHING;
        String prompt = Prefs.photoPrompt(c);
        Prefs.clearPhotoPending(c);
        File file = Photo.file(c, name);
        if (file == null) return MISSING;
        if (!cameraOk) {
            file.delete();
            return CANCELED;
        }
        if (!file.isFile() || file.length() == 0) {
            file.delete();
            return MISSING;
        }
        if (GeminiService.startPhoto(c, prompt, name)) return SENT;
        file.delete();
        return NOT_STARTED;
    }
}
