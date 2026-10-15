package life.andre.message487;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.IOException;
import java.util.ArrayList;

/** Lives only in the test APK. Java keeps the separate provider process independent of the target APK's Kotlin runtime. */
public class BlockingImageTestProvider extends ContentProvider {
    private final ArrayList<ParcelFileDescriptor> writers = new ArrayList<>();
    private int opened;
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "image/png"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    @Override public synchronized ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            writers.add(pipe[1]); opened++;
            return pipe[0];
        } catch (IOException error) { throw new java.io.FileNotFoundException(error.toString()); }
    }
    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        if ("release".equals(method)) {
            for (ParcelFileDescriptor writer : writers) { try { writer.close(); } catch (IOException ignored) { } }
            writers.clear(); opened = 0;
        }
        Bundle result = new Bundle(); result.putInt("count", opened); return result;
    }
}
