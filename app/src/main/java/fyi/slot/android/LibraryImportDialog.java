package fyi.slot.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;
import java.util.List;
import java.util.stream.Collectors;

/** A single modal window changes from batch progress to a disposable result. */
final class LibraryImportDialog {
    private static final int PAGE_SIZE = 30;
    private final Activity activity;
    private final AppLanguage language;
    private final LibraryImportSession imports;
    private AlertDialog dialog;
    private TextView summary, current;
    private ProgressBar progress;
    private LinearLayout failures;
    private ScrollView scroll;
    private boolean foreground, requested;
    private int offset;
    private LibraryImportSession.State rendered;
    private String renderedLanguage;

    LibraryImportDialog(Activity activity, AppLanguage language, LibraryImportSession imports) {
        this.activity = activity; this.language = language; this.imports = imports;
    }
    void show() { requested = true; offset = 0; update(); }
    void resume() { foreground = true; update(); }
    void pause() { foreground = false; dismissWindow(); }
    void hide() { requested = false; dismissWindow(); }
    boolean requested() { return requested; }
    void restore(boolean requested) { this.requested = requested; }
    boolean isShowing() { return dialog != null && dialog.isShowing(); }
    AlertDialog window() { return dialog; }

    void update() {
        LibraryImportSession.State state = imports.state();
        if (state.root() == null) { hide(); return; }
        if (!requested || !foreground || activity.isFinishing()) return;
        if (dialog == null) create();
        boolean running = state.running();
        dialog.setTitle(language.text(running ? R.string.import_progress : state.cancelled()
                ? R.string.import_cancelled : R.string.import_results));
        int added = state.count(R.string.import_added), skipped = state.count(R.string.import_skipped);
        summary.setText(running ? language.text(R.string.import_count, state.items().size(), state.total())
                : state.items().isEmpty() ? language.text(R.string.import_no_files)
                : language.text(R.string.import_summary, added, skipped,
                        state.items().size() - added - skipped - state.count(R.string.import_cancelled)));
        current.setVisibility(running ? View.VISIBLE : View.GONE);
        current.setText(state.current().isEmpty() ? language.text(R.string.apps_loading) : state.current());
        progress.setVisibility(running ? View.VISIBLE : View.GONE);
        progress.setIndeterminate(state.size() <= 0);
        if (state.size() > 0) {
            double fraction = Math.min(1d, (double) state.bytes() / state.size());
            progress.setProgress((int) (1000d * (state.items().size() + fraction) / Math.max(1, state.total())));
        }
        Button button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        button.setText(language.text(running ? imports.cancellationRequested() ? R.string.import_cancelling
                : R.string.cancel_import : R.string.close));
        button.setEnabled(!running || !imports.cancellationRequested());
        String locale = language.text(R.string.close);
        if (rendered != state || !locale.equals(renderedLanguage)) {
            rendered = state; renderedLanguage = locale; bindFailures(state);
        }
    }

    private void create() {
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24); body.setPadding(pad, dp(8), pad, dp(8));
        summary = text(16, 0xffeeeeee); body.addView(summary);
        current = text(14, 0xffb9b9c0); current.setMaxLines(2);
        current.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams fileParams = new LinearLayout.LayoutParams(-1, -2); fileParams.topMargin = dp(12);
        body.addView(current, fileParams);
        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(1000);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(8)); progressParams.topMargin = dp(12);
        body.addView(progress, progressParams);
        failures = new LinearLayout(activity); failures.setOrientation(LinearLayout.VERTICAL);
        scroll = new ScrollView(activity) {
            @Override protected void onMeasure(int width, int height) {
                int max = Math.round(getResources().getDisplayMetrics().heightPixels * .35f);
                super.onMeasure(width, View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST));
            }
        };
        scroll.setFillViewport(false); scroll.addView(failures); body.addView(scroll);
        dialog = new AlertDialog.Builder(activity).setTitle(language.text(R.string.import_progress)).setView(body)
                .setPositiveButton(language.text(R.string.cancel_import), null).create();
        dialog.setCancelable(false); dialog.setCanceledOnTouchOutside(false);
        dialog.setOnKeyListener((shown, key, event) -> {
            if (SlotView.isBackKey(key) || AppsDrawer.isConfirm(key)) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    View focused = dialog.getCurrentFocus();
                    if (!SlotView.isBackKey(key) && focused instanceof Button button) {
                        if (button.isEnabled()) button.performClick();
                    } else activate();
                }
                return true;
            }
            return SlotView.entryForKey(key) != null || key == KeyEvent.KEYCODE_MENU
                    || key == KeyEvent.KEYCODE_BUTTON_L1 || key == KeyEvent.KEYCODE_BUTTON_R1
                    || key == KeyEvent.KEYCODE_Q || key == KeyEvent.KEYCODE_E;
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> activate());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
        if (Build.VERSION.SDK_INT >= 33) dialog.getWindow().getOnBackInvokedDispatcher()
                .registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::activate);
    }
    private void bindFailures(LibraryImportSession.State state) {
        failures.removeAllViews();
        List<LibraryImportSession.Item> problems = state.running() ? List.of() : state.items().stream()
                .filter(item -> item.message() != R.string.import_added && item.message() != R.string.import_skipped)
                .collect(Collectors.toList());
        scroll.setVisibility(problems.isEmpty() ? View.GONE : View.VISIBLE);
        offset = Math.min(offset, problems.isEmpty() ? 0 : (problems.size() - 1) / PAGE_SIZE * PAGE_SIZE);
        for (int i = offset; i < Math.min(offset + PAGE_SIZE, problems.size()); i++) {
            LibraryImportSession.Item item = problems.get(i);
            TextView row = text(14, 0xffb9b9c0); row.setPadding(0, dp(12), 0, 0);
            row.setText(language.text(R.string.import_file_issue, item.name(), language.text(item.message()))); failures.addView(row);
        }
        if (offset > 0) pageButton(R.string.previous_page, -PAGE_SIZE);
        if (offset + PAGE_SIZE < problems.size()) pageButton(R.string.next_page, PAGE_SIZE);
    }
    private void pageButton(int title, int step) {
        Button button = new Button(activity); button.setText(language.text(title));
        button.setOnClickListener(view -> { offset += step; bindFailures(imports.state()); scroll.scrollTo(0, 0); });
        failures.addView(button);
    }
    void activate() {
        if (imports.state().running()) { imports.cancel(); update(); }
        else { hide(); imports.clear(); }
    }
    boolean dispatchKey(KeyEvent event) {
        if (!isShowing()) return false;
        dialog.dispatchKeyEvent(event); return true;
    }
    private TextView text(int size, int color) {
        TextView view = new TextView(activity); view.setTextSize(size); view.setTextColor(color); return view;
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private void dismissWindow() {
        if (dialog != null) { dialog.dismiss(); dialog = null; }
        rendered = null; renderedLanguage = null;
    }
    void release() { hide(); foreground = false; }
}
