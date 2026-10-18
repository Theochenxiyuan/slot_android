package fyi.slot.android;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.window.OnBackInvokedDispatcher;

public final class MainActivity extends Activity {
    // Set only by in-process instrumentation; never exposed through intents or preferences.
    static ShelfModel testShelf;
    SlotView slotView;
    HomeCoordinator home;
    private LibraryImportSession imports;
    private boolean resumed;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        slotView = testShelf == null ? new SlotView(this) : new SlotView(this, testShelf);
        imports = getLastNonConfigurationInstance() instanceof LibraryImportSession retained ? retained : new LibraryImportSession(this);
        home = new HomeCoordinator(this, slotView, imports);
        setContentView(home.createContent());
        slotView.requestFocus();
        if (savedInstanceState != null) {
            slotView.restore(savedInstanceState);
            home.restore(savedInstanceState);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> home.goBack());
        }
        immerse();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (Intent.ACTION_MAIN.equals(intent.getAction()) && intent.hasCategory(Intent.CATEGORY_HOME)) {
            home.onHome(resumed);
        }
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        slotView.language().refresh(configuration);
        home.onLanguageChanged();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immerse();
        else if (slotView != null) slotView.loseFocus();
    }

    private void immerse() {
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.systemBars());
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        slotView.save(outState);
        home.save(outState);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onStart() {
        super.onStart();
        home.start();
    }

    @Override protected void onStop() {
        home.stop();
        super.onStop();
    }

    @Override protected void onPause() {
        resumed = false;
        slotView.pause();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        slotView.resume();
        home.resume();
    }

    @Override protected void onDestroy() {
        home.release();
        if (!isChangingConfigurations()) imports.release();
        super.onDestroy();
    }
    @Override public Object onRetainNonConfigurationInstance() { return imports; }

    @SuppressWarnings("deprecation")
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == HomeRole.REQUEST_CODE) home.onRoleResult();
        else if (requestCode == LibraryStorage.PICK_FOLDER) home.onLibraryFolder(resultCode, data);
        else if (requestCode == LibraryPanel.PICK_GAMES) home.onGamesPicked(resultCode, data);
        else if (requestCode == WallpaperCropDialog.PICK_WALLPAPER) home.onWallpaperPicked(resultCode, data);
        else if (requestCode == LocalStudioDialog.PICK_IMAGE) home.onStudioImagePicked(resultCode, data);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        // Route before a focused View consumes Enter or DPAD_CENTER.
        if (home != null && home.dispatchKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (slotView.handleKeyDown(keyCode, event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (slotView.handleKeyUp(keyCode, event)) return true;
        return super.onKeyUp(keyCode, event);
    }

    // Android 13+ uses the native OnBackInvoked callback registered in onCreate.
    // This override handles Android 12 back navigation.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @SuppressWarnings("deprecation") @Override public void onBackPressed() {
        home.goBack();
    }
}
