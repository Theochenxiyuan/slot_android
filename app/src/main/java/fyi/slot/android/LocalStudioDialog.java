package fyi.slot.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full-screen local editor; leaving the activity releases its page and listening socket. */
final class LocalStudioDialog {
    static final int PICK_IMAGE = 45;
    private static final int MAX_PICKED_BYTES = 32 * 1024 * 1024;
    private final Activity activity;
    private final AppLanguage language;
    private final LibraryRepository library;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Dialog dialog;
    private WebView web;
    private TextView status;
    private LocalStudioSession session;
    private RomRecord target;
    private boolean pickingFile;
    private int generation;
    private int pressedKey;
    private boolean released;

    LocalStudioDialog(Activity activity, AppLanguage language, LibraryRepository library) {
        this.activity = activity; this.language = language; this.library = library;
    }

    void show(RomRecord rom) {
        if (released) return;
        dismiss(); target = rom; pressedKey = KeyEvent.KEYCODE_UNKNOWN;
        dialog = new Dialog(activity) {
            // Android 12 fallback; newer systems use the window dispatcher registered below.
            @SuppressLint("GestureBackNavigation")
            @SuppressWarnings("deprecation") @Override public void onBackPressed() { back(); }
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                int key = event.getKeyCode();
                if (SlotView.isBackKey(key)) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) pressedKey = key;
                    else if (event.getAction() == KeyEvent.ACTION_UP) {
                        boolean matched = pressedKey == key && !event.isCanceled();
                        pressedKey = KeyEvent.KEYCODE_UNKNOWN;
                        if (matched) back();
                    }
                    return true;
                }
                if (AppsDrawer.isConfirm(key)) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        if (event.getRepeatCount() > 0) return true;
                        pressedKey = key;
                        if (key == InteractionPreferences.primaryConfirmKey() && web != null && web.hasFocus()) {
                            web.dispatchKeyEvent(new KeyEvent(event.getDownTime(), event.getEventTime(),
                                    KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0));
                            return true;
                        }
                        return key == InteractionPreferences.primaryConfirmKey() || super.dispatchKeyEvent(event);
                    }
                    if (event.getAction() != KeyEvent.ACTION_UP) {
                        pressedKey = KeyEvent.KEYCODE_UNKNOWN;
                        return true;
                    }
                    // Ignore the button release from the menu row that opened this dialog.
                    boolean matched = pressedKey == key && !event.isCanceled();
                    pressedKey = KeyEvent.KEYCODE_UNKNOWN;
                    if (!matched) return true;
                    if (key != InteractionPreferences.primaryConfirmKey()) return super.dispatchKeyEvent(event);
                    View focus = getCurrentFocus();
                    if (web != null && web.hasFocus()) {
                        web.dispatchKeyEvent(new KeyEvent(event.getDownTime(), event.getEventTime(), event.getAction(),
                                KeyEvent.KEYCODE_ENTER, 0));
                    } else if (focus != null && focus.isEnabled() && focus.isClickable()) focus.performClick();
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(false);
        LinearLayout content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(0xff1b1b21);
        LinearLayout bar = new LinearLayout(activity); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(PanelStyle.rounded(PanelStyle.CARD, 0, 0, 0));
        TextView back = text(new InteractionPreferences(activity).backBadge() + "  " + language.text(R.string.back));
        back.setFocusable(true); back.setOnClickListener(view -> back());
        bar.addView(back, new LinearLayout.LayoutParams(dp(104), -2));
        TextView title = text(language.text(rom == null ? R.string.cart_studio : R.string.edit_appearance));
        title.setGravity(Gravity.CENTER); bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView spacer = text(""); spacer.setWidth(dp(104));
        bar.addView(spacer);
        content.addView(bar, new LinearLayout.LayoutParams(-1, dp(48)));
        FrameLayout body = new FrameLayout(activity);
        status = text(language.text(R.string.apps_loading)); status.setGravity(Gravity.CENTER); status.setTextColor(Color.WHITE);
        status.setOnClickListener(view -> load()); status.setFocusable(true);
        body.addView(status, new FrameLayout.LayoutParams(-1, -1));
        content.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            int imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
            bar.setVisibility(imeBottom > 0 ? View.GONE : View.VISIBLE);
            view.setPadding(0, 0, 0, imeBottom);
            return insets;
        });
        dialog.setContentView(content);
        Dialog shown = dialog;
        dialog.setOnDismissListener(ignored -> { if (dialog == shown) cleanup(); });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            window.setLayout(-1, -1); window.setDecorFitsSystemWindows(false);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
            if (Build.VERSION.SDK_INT >= 33) window.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, this::back);
        }
        load();
    }

    private TextView text(String value) {
        TextView view = new TextView(activity); view.setText(value); view.setTextSize(16); view.setTextColor(PanelStyle.INK);
        view.setPadding(dp(14), dp(8), dp(14), dp(8)); view.setBackground(PanelStyle.rowBackground(dp(4)));
        return view;
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    private void load() {
        if (!isShowing()) return;
        int request = ++generation;
        clearPage();
        status.setVisibility(View.VISIBLE); status.setText(language.text(R.string.apps_loading));
        status.setEnabled(false); status.setFocusable(false);
        File root = library.root();
        worker.execute(() -> {
            LocalStudioSession next = new LocalStudioSession(() -> available(root), path -> activity.getAssets().open("studio/" + path),
                    LibraryEvents::post, ArtProxy.network());
            try {
                if (available(root) == null) throw new java.io.IOException("Unavailable library");
                next.start();
                activity.runOnUiThread(() -> {
                    if (released || request != generation || !isShowing()) { dispose(next); return; }
                    session = next;
                    try { openPage(next); }
                    catch (RuntimeException error) { clearPage(); failed(); }
                });
            } catch (java.io.IOException | RuntimeException error) {
                next.close();
                activity.runOnUiThread(() -> { if (request == generation && isShowing()) failed(); });
            }
        });
    }

    private File available(File selected) {
        if (selected == null || !selected.equals(library.root())) return null;
        return (LibraryStorage.allowed() || LibraryPaths.within(activity.getFilesDir(), selected))
                && LibraryStorage.online(activity, selected) && selected.isDirectory() ? selected : null;
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void openPage(LocalStudioSession current) {
        WebView page = new WebView(activity); web = page;
        page.setBackgroundColor(0xff1b1b21);
        WebSettings options = page.getSettings();
        options.setJavaScriptEnabled(true); options.setDomStorageEnabled(true);
        options.setAllowFileAccess(false); options.setAllowContentAccess(false);
        options.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        options.setSupportMultipleWindows(false); options.setJavaScriptCanOpenWindowsAutomatically(false);
        page.addJavascriptInterface(new Object() {
            @JavascriptInterface public void close() {
                activity.runOnUiThread(() -> { if (web == page) dismiss(); });
            }
            @JavascriptInterface public void pickImage() {
                activity.runOnUiThread(() -> { if (web == page) startImagePicker(); });
            }
        }, "AndroidStudio");
        page.setWebChromeClient(new WebChromeClient());
        page.setWebViewClient(new WebViewClient() {
            private boolean failed;
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Only the bundled page may navigate in this bridge-enabled WebView.
                return !localPage(request.getUrl(), current.origin());
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (web == page && !failed) { status.setVisibility(View.GONE); page.requestFocus(); }
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && web == page) { failed = true; page.setVisibility(View.GONE); failed(); }
            }
            // A renderer crash (rare, e.g. after a cold wake) kills the WebView content. Destroying
            // the view ourselves and returning true keeps the app alive; load() restarts the page.
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (web == page) { clearPage(); load(); }
                return true;
            }
        });
        FrameLayout body = (FrameLayout) status.getParent();
        body.addView(page, 0, new FrameLayout.LayoutParams(-1, -1));
        page.requestFocus();
        page.loadUrl(current.url(language.locale().getLanguage(), target));
    }

    static boolean localPage(Uri uri, String origin) {
        return "http".equals(uri.getScheme()) && origin.equals(uri.getScheme() + "://" + uri.getEncodedAuthority())
                && "/".equals(uri.getPath());
    }
    private void failed() {
        status.setVisibility(View.VISIBLE); status.setText(language.text(R.string.studio_failed));
        status.setEnabled(true); status.setFocusable(true); status.requestFocus();
    }
    void back() {
        WebView page = web;
        if (page == null || page.getVisibility() != View.VISIBLE) { dismiss(); return; }
        page.evaluateJavascript("typeof window.slotStudioBack === 'function' && window.slotStudioBack()", result -> {
            if (web == page && !"true".equals(result)) dismiss();
        });
    }
    private boolean startImagePicker() {
        Intent openDoc = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        try {
            pickingFile = true;
            activity.startActivityForResult(openDoc, PICK_IMAGE);
            return true;
        } catch (android.content.ActivityNotFoundException | SecurityException ignored) {
            pickingFile = false;
        }
        Intent getContent = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        try {
            pickingFile = true;
            activity.startActivityForResult(getContent, PICK_IMAGE);
            return true;
        } catch (android.content.ActivityNotFoundException | SecurityException error) {
            pickingFile = false;
            android.widget.Toast.makeText(activity, language.text(R.string.wallpaper_picker_unavailable), android.widget.Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    void onImagePicked(int resultCode, Intent data) {
        pickingFile = false;
        if (released || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        int request = generation;
        worker.execute(() -> {
            byte[] bytes = readPickedBytes(uri);
            String base64 = bytes == null ? "" : android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
            String mime = activity.getContentResolver().getType(uri);
            String safeMime = mime != null && mime.startsWith("image/") ? mime : "image/png";
            activity.runOnUiThread(() -> {
                if (released || request != generation || web == null) return;
                web.evaluateJavascript("window.slotStudioPickResult && window.slotStudioPickResult('"
                        + base64 + "','picked.png','" + safeMime.replace("'", "") + "')", null);
            });
        });
    }

    private byte[] readPickedBytes(Uri uri) {
        try (java.io.InputStream in = activity.getContentResolver().openInputStream(uri);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            if (in == null) return null;
            byte[] buffer = new byte[16 * 1024];
            int total = 0, n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > MAX_PICKED_BYTES) return null;
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (java.io.IOException | RuntimeException error) {
            return null;
        }
    }

    private void cancelFilePick() { pickingFile = false; }

    boolean isPickingFile() { return pickingFile; }

    private void clearPage() {
        cancelFilePick();
        if (web != null) {
            WebView page = web; web = null;
            ((FrameLayout) page.getParent()).removeView(page); page.stopLoading();
            page.removeJavascriptInterface("AndroidStudio");
            try { page.destroy(); } catch (RuntimeException alreadyDead) { /* renderer already gone */ }
        }
        if (session != null) { LocalStudioSession old = session; session = null; dispose(old); }
    }
    private void dispose(LocalStudioSession old) {
        try { worker.execute(old::close); }
        catch (java.util.concurrent.RejectedExecutionException stopped) { old.close(); }
    }
    private void cleanup() { generation++; clearPage(); dialog = null; }
    void dismiss() {
        if (dialog == null) return;
        Dialog old = dialog;
        cleanup();
        old.dismiss();
    }
    boolean isShowing() { return dialog != null && dialog.isShowing(); }
    WebView page() { return web; }
    void release() { dismiss(); released = true; worker.shutdown(); }
}
