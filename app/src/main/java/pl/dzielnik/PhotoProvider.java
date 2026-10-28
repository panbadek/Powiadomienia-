package pl.dzielnik;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * Daje aplikacji aparatu miejsce na jedno zdjęcie w pamięci podręcznej tej aplikacji. Dzięki temu aplikacja
 * nie potrzebuje pozwolenia na aparat ani dostępu do galerii: aparat dostaje od systemu dostęp tylko do tego
 * jednego adresu i tylko na czas zrobienia zdjęcia. To odpowiednik FileProvider z biblioteki AndroidX,
 * której ta aplikacja nie używa. Obsługuje wyłącznie pliki o nazwach nadawanych przez {@link PhotoMath#fileName}.
 */
public class PhotoProvider extends ContentProvider {

    /** Taki sam adres jest w AndroidManifest.xml. */
    static final String AUTHORITY = "pl.dzielnik.zdjecia";

    @Override
    public boolean onCreate() {
        return true;
    }

    /** Plik z adresu content://pl.dzielnik.zdjecia/foto/zdjecie-123.jpg; każdy inny adres jest odrzucany. */
    private File fileFor(Uri uri) throws FileNotFoundException {
        Context c = getContext();
        List<String> path = uri == null ? null : uri.getPathSegments();
        if (c == null || path == null || path.size() != 2 || !Photo.DIR.equals(path.get(0))) {
            throw new FileNotFoundException("Nieznany adres");
        }
        File f = Photo.file(c, path.get(1));
        if (f == null) throw new FileNotFoundException("Nieznany adres");
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileFor(uri);
        try {
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode));
        } catch (IllegalArgumentException e) {
            throw new FileNotFoundException("Nieznany tryb dostępu: " + mode);
        }
    }

    /** Niektóre aparaty pytają o nazwę i rozmiar pliku, zanim w nim zapiszą. */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] columns = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) {
                row[i] = f.getName();
            } else if (OpenableColumns.SIZE.equals(columns[i])) {
                row[i] = f.length();
            }
        }
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return Gemini.IMAGE_MIME;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Dodawanie nie jest obsługiwane");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
