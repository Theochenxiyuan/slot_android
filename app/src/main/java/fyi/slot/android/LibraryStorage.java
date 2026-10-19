package fyi.slot.android;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.Settings;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class LibraryStorage {
    static final int PICK_FOLDER = 42;
    private static final String PROVIDER = "com.android.externalstorage.documents";
    private static final String PRIMARY = "primary";

    record Location(String name, String key, File directory) {}
    static boolean allowed() { return Environment.isExternalStorageManager(); }
    static boolean request(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName()))); return true;
        } catch (ActivityNotFoundException error) {
            try { activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); return true; }
            catch (ActivityNotFoundException unavailable) { return false; }
        }
    }
    static List<Location> volumes(Context context) {
        List<Location> out = new ArrayList<>();
        StorageManager manager = context.getSystemService(StorageManager.class);
        if (manager != null) for (StorageVolume volume : manager.getStorageVolumes()) {
            if (!Environment.MEDIA_MOUNTED.equals(volume.getState()) && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(volume.getState())) continue;
            File directory = volume.getDirectory();
            String key = volume.isPrimary() ? PRIMARY : volume.getUuid();
            if (directory != null && key != null && directory.isDirectory()) out.add(new Location(volume.getDescription(context), key, directory));
        }
        return out;
    }
    /** Libraries already on storage: a volume root, Slot, Roms or Emulation/roms folder. */
    static List<File> discover(Activity activity) throws IOException {
        List<File> out = new ArrayList<>();
        for (Location location : volumes(activity)) {
            File root = location.directory();
            if (LibraryPaths.hasGamesLayout(root)) out.add(root);
            File slot = LibraryPaths.child(root, "Slot");
            if (LibraryPaths.hasGamesLayout(slot)) out.add(slot);
            File roms = LibraryPaths.child(root, "Roms");
            if (LibraryPaths.hasGamesLayout(roms)) out.add(roms);
            File emulationRoms = LibraryPaths.child(LibraryPaths.child(root, "Emulation"), "roms");
            if (LibraryPaths.hasGamesLayout(emulationRoms)) out.add(emulationRoms);
        }
        return out;
    }
    static boolean online(Context activity, File root) {
        for (Location location : volumes(activity)) if (relative(location, root) != null) return true;
        // App-private roots are used only by instrumentation fixtures.
        return LibraryPaths.within(activity.getFilesDir(), root) && root.isDirectory();
    }

    /** System folder picker, opened at the current library when it is on shared storage. */
    static boolean pick(Activity activity, File current) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.putExtra("android.content.extra.SHOW_ADVANCED", true);
        if (current != null) for (Location location : volumes(activity)) {
            String path = relative(location, current);
            if (path == null) continue;
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(PROVIDER, location.key() + ":" + path));
            break;
        }
        try { activity.startActivityForResult(intent, PICK_FOLDER); return true; }
        catch (ActivityNotFoundException | SecurityException error) { return false; }
    }

    /**
     * Maps a picked shared-storage tree to its library root; other providers are unsupported.
     */
    static File resolve(Activity activity, Uri tree) {
        if (tree == null || !PROVIDER.equals(tree.getAuthority())) return null;
        String id;
        try { id = DocumentsContract.getTreeDocumentId(tree); } catch (IllegalArgumentException error) { return null; }
        int split = id.indexOf(':');
        if (split < 0) return null;
        String key = id.substring(0, split), path = id.substring(split + 1);
        for (Location location : volumes(activity)) {
            if (!location.key().equalsIgnoreCase(key)) continue;
            return path.isEmpty() ? location.directory() : new File(location.directory(), path);
        }
        return null;
    }

    /** Volume name followed by the folder path, for display. */
    static String describe(Context context, AppLanguage language, File folder) {
        for (Location location : volumes(context)) {
            String path = relative(location, folder);
            if (path == null) continue;
            String name = PRIMARY.equals(location.key()) ? language.text(R.string.internal_storage) : location.name();
            return path.isEmpty() ? name : name + " › /" + path.replace("/", " / ");
        }
        return folder.getAbsolutePath();
    }

    /** An empty folder becomes a library with one games folder per platform. */
    static void prepare(File root) throws IOException {
        File[] children = root.listFiles();
        if (children == null) throw new IOException("Cannot enumerate " + root);
        if (children.length > 0) return;
        for (Cartridge.Platform platform : Cartridge.Platform.values()) {
            File games = LibraryPaths.games(root, platform);
            if (!games.isDirectory() && !games.mkdirs()) throw new IOException("Cannot create library");
        }
    }

    private static String relative(Location location, File folder) {
        String base = location.directory().getAbsolutePath(), path = folder.getAbsolutePath();
        if (path.equals(base)) return "";
        return path.startsWith(base + File.separator) ? path.substring(base.length() + 1) : null;
    }
}
