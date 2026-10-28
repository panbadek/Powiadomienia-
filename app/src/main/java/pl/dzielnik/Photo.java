package pl.dzielnik;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/**
 * Zdjęcie z aparatu telefonu w drodze do Gemini. Aparat zapisuje zdjęcie w pamięci podręcznej aplikacji
 * (przez PhotoProvider, więc nie trzeba żadnego pozwolenia), a tutaj robię z niego mały, prosto obrócony JPEG.
 * Rachunki (rozmiar, obrót) są w PhotoMath, żeby dało się je przetestować bez Androida.
 */
final class Photo {

    /** Podkatalog pamięci podręcznej na zdjęcia; ten sam jest w adresach PhotoProvider. */
    static final String DIR = "foto";

    /** Zdjęcia starsze niż doba to resztki po przerwanym zdjęciu (aparat zabity, aplikacja zamknięta). */
    private static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;

    private Photo() {}

    /** Gotowy do wysłania JPEG albo opis błędu po polsku. */
    static final class Prepared {
        final byte[] jpeg;
        final String error;

        private Prepared(byte[] jpeg, String error) {
            this.jpeg = jpeg;
            this.error = error;
        }

        static Prepared ok(byte[] jpeg) {
            return new Prepared(jpeg, null);
        }

        static Prepared fail(String error) {
            return new Prepared(null, error);
        }
    }

    /** Katalog na zdjęcia; tworzę go, bo aparat sam tego nie zrobi. */
    static File dir(Context c) {
        File d = new File(c.getCacheDir(), DIR);
        d.mkdirs();
        return d;
    }

    /** Plik o podanej nazwie albo null, gdy to nie jest nazwa, jaką nadaję zdjęciom. */
    static File file(Context c, String name) {
        return PhotoMath.validName(name) ? new File(dir(c), name) : null;
    }

    /** Adres, pod którym aparat zapisze zdjęcie. */
    static Uri uri(String name) {
        return Uri.parse("content://" + PhotoProvider.AUTHORITY + "/" + DIR + "/" + name);
    }

    /** Usuwa stare, porzucone zdjęcia. */
    static void cleanOld(Context c) {
        File[] files = dir(c).listFiles();
        if (files == null) return;
        long limit = System.currentTimeMillis() - MAX_AGE_MS;
        for (File f : files) {
            if (f.lastModified() < limit) {
                f.delete();
            }
        }
    }

    /**
     * Wczytuje zdjęcie z aparatu, zmniejsza je (dłuższy bok najwyżej {@link PhotoMath#MAX_SIDE}), obraca według
     * znacznika orientacji i zapisuje jako JPEG. Nowy JPEG nie ma już danych EXIF, więc nie niesie lokalizacji
     * ani modelu telefonu. Plik z aparatu jest po tym kasowany, także gdy coś się nie uda.
     */
    static Prepared prepare(Context c, String name) {
        File f = file(c, name);
        try {
            if (f == null || !f.isFile() || f.length() == 0) {
                return Prepared.fail("Aparat nie zapisał zdjęcia. Zrób je jeszcze raz.");
            }
            return encode(f);
        } catch (OutOfMemoryError e) {
            return Prepared.fail("Za mało pamięci, żeby przetworzyć zdjęcie. Zamknij inne aplikacje i spróbuj jeszcze raz.");
        } finally {
            if (f != null) {
                f.delete();
            }
        }
    }

    private static Prepared encode(File f) {
        String path = f.getAbsolutePath();

        // Najpierw sam rozmiar, żeby przy wczytywaniu od razu zmniejszyć duże zdjęcie (oszczędność pamięci).
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return Prepared.fail("Nie udało się odczytać zdjęcia z aparatu.");
        }
        BitmapFactory.Options load = new BitmapFactory.Options();
        load.inSampleSize = PhotoMath.sampleSize(bounds.outWidth, bounds.outHeight, PhotoMath.MAX_SIDE);
        Bitmap loaded = BitmapFactory.decodeFile(path, load);
        if (loaded == null) return Prepared.fail("Nie udało się odczytać zdjęcia z aparatu.");

        int orientation = readOrientation(path);

        Bitmap small = loaded;
        int[] size = PhotoMath.fit(loaded.getWidth(), loaded.getHeight(), PhotoMath.MAX_SIDE);
        if (size[0] != loaded.getWidth() || size[1] != loaded.getHeight()) {
            small = Bitmap.createScaledBitmap(loaded, size[0], size[1], true);
            if (small != loaded) loaded.recycle();
        }

        // Aparat zapisuje piksele tak, jak widzi je czujnik, a w EXIF dopisuje, jak obrócić zdjęcie.
        // Gemini nie czyta EXIF, więc obracam piksele sam.
        Bitmap upright = small;
        if (orientation >= 2 && orientation <= 8) {
            int w = small.getWidth();
            int h = small.getHeight();
            int[] pixels = new int[w * h];
            small.getPixels(pixels, 0, w, 0, 0, w, h);
            int[] turned = PhotoMath.orient(pixels, w, h, orientation);
            boolean swap = PhotoMath.swapsSides(orientation);
            upright = Bitmap.createBitmap(turned, swap ? h : w, swap ? w : h, Bitmap.Config.ARGB_8888);
            small.recycle();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
        boolean written = upright.compress(Bitmap.CompressFormat.JPEG, PhotoMath.JPEG_QUALITY, out);
        upright.recycle();
        if (!written || out.size() == 0) return Prepared.fail("Nie udało się przygotować zdjęcia do wysłania.");
        return Prepared.ok(out.toByteArray());
    }

    /** Kod orientacji z EXIF (1–8); gdy go nie ma albo plik nie ma EXIF, 1 czyli bez obrotu. */
    private static int readOrientation(String path) {
        try {
            return new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException | RuntimeException e) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }
}
