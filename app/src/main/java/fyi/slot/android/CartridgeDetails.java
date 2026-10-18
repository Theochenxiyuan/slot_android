package fyi.slot.android;

import android.app.Activity;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Cartridge menu and static ROM details, shown in the floating bay card off the main thread. */
final class CartridgeDetails {
    private final Activity activity;
    private final AppLanguage language;
    private final LibraryRepository repository;
    private final GameCoreDialog cores;
    private final ShelfCollections collections;
    private final Runnable favoriteChanged;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private PanelDialog dialog;
    private boolean released;
    private boolean showingDetails;
    private java.util.function.Consumer<RomRecord> edit = rom -> {};
    void setEditor(java.util.function.Consumer<RomRecord> edit) { this.edit = edit; }

    CartridgeDetails(Activity activity, AppLanguage language, LibraryRepository repository, GameCoreDialog cores,
                     ShelfCollections collections, Runnable favoriteChanged) {
        this.activity = activity; this.language = language; this.repository = repository;
        this.cores = cores; this.collections = collections; this.favoriteChanged = favoriteChanged;
    }

    void menu(Cartridge cartridge) {
        dismiss();
        showingDetails = false;
        boolean favorite = cartridge.rom != null && collections.isFavorite(cartridge.rom);
        List<String> actions = new ArrayList<>();
        actions.add(language.text(R.string.game_details));
        if (cartridge.rom != null) {
            actions.add(language.text(R.string.edit_appearance));
            actions.add(language.text(R.string.game_core));
            actions.add(language.text(favorite ? R.string.unfavorite_game : R.string.favorite_game));
            actions.add(language.text(R.string.hide_game));
        }
        PanelDialog panel = new PanelDialog(activity, language, 460f);
        dialog = panel;
        int[] icons = cartridge.rom == null ? new int[]{R.drawable.menu_info}
                : new int[]{R.drawable.menu_info, R.drawable.menu_edit, R.drawable.menu_core,
                        favorite ? R.drawable.menu_favorite_filled : R.drawable.menu_favorite, R.drawable.menu_hide};
        panel.setTitle(cartridge.title).setRows(actions, -1, icons)
                .setPick(index -> selectAction(cartridge, index))
                .setCancelled(this::dismiss);
        panel.show();
    }

    private void selectAction(Cartridge cartridge, int index) {
        if (index == 3 && cartridge.rom != null) {
            // Settle the collection before the card closes so the shelf keeps its current choice.
            collections.toggleFavorite(cartridge.rom);
            favoriteChanged.run();
            dismiss();
            return;
        }
        dismiss();
        if (index == 0) { if (cartridge.rom != null) show(cartridge); }
        else if (index == 1 && cartridge.rom != null) edit.accept(cartridge.rom);
        else if (index == 2) cores.show(cartridge);
        else if (index == 4 && cartridge.rom != null) repository.setHidden(cartridge.rom, true);
    }

    void show(Cartridge cartridge) {
        dismiss();
        showingDetails = true;
        RomRecord rom = cartridge.rom;
        LinearLayout content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(activity.getResources().getDisplayMetrics().density * 20);
        content.setPadding(0, Math.round(pad * .4f), 0, Math.round(pad * .4f));
        ImageView label = new ImageView(activity);
        label.setAdjustViewBounds(true);
        label.setMaxHeight(Math.round(activity.getResources().getDisplayMetrics().heightPixels * .30f));
        content.addView(label, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView metadata = new TextView(activity); metadata.setTextSize(15); metadata.setTextColor(PanelStyle.INK);
        String description = language.text(R.string.game_summary, rom.platform.name(),
                android.text.format.Formatter.formatShortFileSize(activity, rom.size))
                + (rom.tags.isEmpty() ? "" : "\n" + rom.tags) + "\n" + rom.filename
                + (rom.headerTitle().isEmpty() ? "" : "\n" + rom.headerTitle())
                + (rom.code().isEmpty() ? "" : " · " + rom.code());
        metadata.setText(description);
        content.addView(metadata);
        TextView crc = new TextView(activity); crc.setTextSize(13); crc.setTextColor(PanelStyle.MUTED);
        crc.setGravity(Gravity.START);
        crc.setText(language.text(R.string.game_crc, language.text(R.string.apps_loading)));
        content.addView(crc);
        PanelDialog panel = new PanelDialog(activity, language, 580f);
        dialog = panel;
        panel.setTitle(cartridge.title).setBody(content).setCancelled(this::dismiss);
        panel.show();
        PanelDialog shown = panel;
        repository.crc(rom, value -> { if (dialog == shown && shown.isShowing()) crc.setText(language.text(R.string.game_crc, value)); });
        worker.execute(() -> {
            Bitmap image = CartridgeCache.decode(cartridge.label, 512, 300);
            activity.runOnUiThread(() -> {
                if (!released && dialog == shown && shown.isShowing()) {
                    if (image != null) label.setImageBitmap(image); else label.setVisibility(android.view.View.GONE);
                } else if (image != null) image.recycle();
            });
        });
    }

    void dismiss() { if (dialog != null) { dialog.dismiss(); dialog = null; } cores.dismiss(); }
    boolean isShowing() { return dialog != null && dialog.isShowing() || cores.isShowing(); }
    boolean isDetailsPage() { return showingDetails && isShowing(); }
    /** Keys that reach the desktop before the card's window takes focus still drive the card. */
    boolean dispatchKey(KeyEvent event) {
        if (cores.isShowing()) return cores.dispatchKey(event);
        return dialog != null && dialog.handleKey(event);
    }
    PanelDialog panel() { return dialog; }
    void release() { released = true; dismiss(); worker.shutdown(); }
}
