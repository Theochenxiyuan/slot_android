package fyi.slot.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Test APK only. Provides synthetic bytes, never exposes any user file. */
public final class ImportFixtureProvider extends ContentProvider {
    private void authorize() {
        try {
            int targetUid = getContext().getPackageManager().getApplicationInfo("fyi.slot.android", 0).uid;
            if (Binder.getCallingUid() != targetUid && Binder.getCallingUid() != android.os.Process.myUid()) throw new SecurityException();
        } catch (android.content.pm.PackageManager.NameNotFoundException error) { throw new SecurityException(error); }
    }
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { authorize(); return "application/octet-stream"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        authorize();
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        String report = uri.getQueryParameter("reported");
        cursor.addRow(new Object[]{uri.getQueryParameter("name"), "unknown".equals(report) ? null : Long.parseLong(report == null ? "512" : report)});
        return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        authorize();
        if (!"r".equals(mode) || "denied".equals(uri.getQueryParameter("mode"))) throw new FileNotFoundException();
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            int size = Integer.parseInt(uri.getQueryParameter("bytes") == null ? "512" : uri.getQueryParameter("bytes"));
            if (size < 0 || size > 65 * 1024 * 1024) throw new IOException();
            boolean slow = "slow".equals(uri.getQueryParameter("mode"));
            new Thread(() -> {
                try (var output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    byte[] block = new byte[16384];
                    for (int count = 0; count < size; count += block.length) {
                        output.write(block, 0, Math.min(block.length, size - count));
                        if (slow) android.os.SystemClock.sleep(15);
                    }
                } catch (IOException ignored) { /* Cancellation closes the reader. */ }
            }, "synthetic-import").start();
            return pipe[0];
        } catch (IOException error) { throw new FileNotFoundException(error.toString()); }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
