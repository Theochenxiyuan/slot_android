package fyi.slot.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import fyi.slot.android.Cartridge.Platform;
import fyi.slot.android.MachinePanelController.Content;

/** A directly manipulated cartridge shelf; deliberately independent of emulation. */
public final class SlotView extends View {
    static final float WIDTH = HomeLayout.REFERENCE_WIDTH, HEIGHT = HomeLayout.REFERENCE_HEIGHT;
    static final float COMMIT = .64f;
    static final float SWIPE_DISTANCE = 48f;
    private static final int INK = Color.rgb(224, 224, 225);
    private static final long BROWSE_HAPTIC_INTERVAL = 100;
    private static final int MAX_POSITION_DOTS = 9;
    private static final float CATEGORY_HALF = 68f, CATEGORY_TOP = 64f;
    private static final float CATEGORY_WIDTH = 136f, CATEGORY_ROW_HEIGHT = 44f, CATEGORY_PADDING = 6f;

    /** Receives side-entry presses and layered back requests while the machine is lifted. */
    interface PanelHost {
        void onEntry(Content content, boolean viaKey);
        void onPanelBack();
        default void onLibrary() {}
        default void onCartridgeMenu(Cartridge cartridge) {}
    }

    interface GameLauncher { boolean launch(RomRecord rom); }
    private GameLauncher gameLauncher = rom -> true;
    private boolean launchRequested, launchingFeedback;
    private final SeatedPresentation seatedPresentation = new SeatedPresentation();
    private final Runnable launchGame = this::dispatchGameLaunch;

    enum Mode { SHELF, PREVIEW }
    private enum Gesture { NONE, UNDECIDED, SHELF, INSERT, EJECT, BUTTON, START_BUTTON, ENTRY, PANEL, LIBRARY, CATEGORY_BUTTON, CATEGORY_MENU,
        CATEGORY_PREVIOUS, CATEGORY_NEXT }
    static final float EJECT_TOUCH_RIGHT = 132f, START_TOUCH_WIDTH = 156f, EJECT_TOUCH_BOTTOM = 70f;
    private final InteractionPreferences preferences;
    private final AppLanguage language;
    private final MachinePanelController panel;
    private final BackdropRenderer backdrop;
    private final MachineRenderer machine;
    private PanelHost host;
    private Content pressedEntry;
    private boolean categoryMenuOpen, categoryFadingIn;
    private int categoryRow, categoryPressedRow = -1;
    private ShelfModel.Category pendingCategory;
    private float categoryOpacity = 1f;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path labelClip = new Path();
    private final RectF box = new RectF();
    private final CartridgeRenderer cartridgeRenderer;
    private final CartridgeCache cartridgeCache;
    private final ShelfArtwork shelfArtwork = new ShelfArtwork();
    private final ShelfLibraryBinding libraryShelf;
    private final Runnable longPress = () -> {
        Cartridge selected = selectedCartridge();
        if (this.gesture == Gesture.UNDECIDED && this.activeCard && canBrowse() && selected != null && selected.rom != null) {
            cancelInteraction(); host.onCartridgeMenu(selected);
        }
    };
    private final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
    private final Typeface labelFace;
    private final ShelfModel shelves;
    private final ShelfKeyRepeat keyRepeat = new ShelfKeyRepeat();
    private final ShelfLetterHint letterHint = new ShelfLetterHint();
    private final Map<String, Bitmap> cartridges = new HashMap<>();
    private final Map<String, Bitmap> pictures = new HashMap<>();
    private final CartridgeAudio sounds;
    private final WifiConnectionStatus wifi;
    private final ShelfStatusObserver status;
    private final Runnable redraw = this::invalidate;
    private final ConnectionState.Listener connectionListener = this::invalidate;
    private final float touchSlop;
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.ROOT);

    private HomeLayout layout = HomeLayout.reference();
    private Mode mode = Mode.SHELF;
    private Gesture gesture = Gesture.NONE;
    private int pointer = -1;
    private int selectedPosition;
    private float shelf, shelfTarget, shelfVelocity;
    private long nextBrowseHapticAt;
    private int browseHapticCount;
    private float insertion, insertionTarget, insertionVelocity;
    private final CartridgeMotion motion = new CartridgeMotion();
    private float seatedHold;
    private boolean insertCueStarted, ejectCueStarted;
    private long insertCueAt, ejectCueAt;
    private float reveal, revealTarget, revealVelocity;
    private float fingerX, downX, downY, initialShelf, initialInsertion;
    private boolean activeCard, returning, railCaught, paused;
    private long lastFrame;
    private VelocityTracker tracker;

    public SlotView(Context context) {
        this(context, new ShelfModel(List.of()));
    }

    SlotView(Context context, ShelfModel shelves) {
        super(context);
        this.shelves = shelves;
        selectedPosition = shelves.current().selectedIndex();
        shelf = shelfTarget = Math.max(0, selectedPosition);
        preferences = new InteractionPreferences(context);
        language = new AppLanguage(context);
        backdrop = new BackdropRenderer(context);
        machine = new MachineRenderer();
        machine.setLight(preferences.lightTheme);
        cartridgeRenderer = new CartridgeRenderer(context.getAssets());
        cartridgeCache = new CartridgeCache(context, cartridgeRenderer, this::invalidate);
        libraryShelf = new ShelfLibraryBinding(shelves, this::canApplyLibrary, this::resetForLibrarySnapshot,
                this::restoreLibraryState, this::updateDescription);
        panel = new MachinePanelController(language);
        panel.addListener(changed -> {
            if (!changed.isClosed()) letterHint.clear();
            updateDescription(); invalidate();
        });
        host = new PanelHost() {
            @Override public void onEntry(Content content, boolean viaKey) {
                if (panel.isTargetOpen() && panel.content() == content) panel.close(); else panel.open(content);
            }
            @Override public void onPanelBack() { panel.close(); }
        };
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        requestFocus();
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        labelFace = Typeface.createFromAsset(context.getAssets(), "label.ttf");
        sounds = new CartridgeAudio(context);
        wifi = new WifiConnectionStatus(context, this::invalidate);
        status = new ShelfStatusObserver(context, this::onStatusChanged);
        DemoScenes scenes = new DemoScenes();
        for (Cartridge cartridge : shelves.shelf(ShelfModel.Category.ALL).cartridges) {
            if (cartridge.rom != null) continue;
            Bitmap picture = scenes.picture(cartridge);
            pictures.put(cartridge.id, picture);
            cartridges.put(cartridge.id, makeCartridge(cartridge, picture));
        }
        updateDescription();
    }

    void setPanelHost(PanelHost host) { this.host = host; }
    void setGameLauncher(GameLauncher launcher) { gameLauncher = launcher; }
    MachinePanelController panel() { return panel; }
    AppLanguage language() { return language; }
    void onLanguageChanged() { updateDescription(); invalidate(); }
    HomeLayout homeLayout() { return layout; }

    void updateLibrary(LibraryRepository.Status status, List<Cartridge> snapshot) {
        libraryShelf.update(status, snapshot); invalidate();
    }

    void updateCollections(Set<String> favorites, List<String> recents, String launchedId) {
        libraryShelf.updateCollections(favorites, recents, launchedId);
        persistSelection();
        invalidate();
    }

    private boolean canApplyLibrary() {
        return mode == Mode.SHELF && insertion <= .001f && insertionTarget == 0 && !returning && !motion.isRunning()
                && gesture == Gesture.NONE && !isCategoryTransitioning() && !keyRepeat.isHeld()
                && Math.abs(shelf - shelfTarget) <= .001f;
    }
    private void resetForLibrarySnapshot() {
        letterHint.clear();
        selectedPosition = selectedIndex(); shelf = shelfTarget = Math.max(0, selectedPosition); shelfVelocity = 0;
        categoryMenuOpen = false;
        persistSelection();
    }
    void restoreLibrarySelection(Bundle saved) { libraryShelf.defer(saved); libraryShelf.apply(); }
    Cartridge currentCartridge() { return selectedCartridge(); }
    CartridgeCache cache() { return cartridgeCache; }
    int libraryCount() { return shelves.shelf(ShelfModel.Category.ALL).size(); }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { updateLayout(); }

    @Override public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        updateLayout();
        return super.onApplyWindowInsets(insets);
    }

    private void updateLayout() {
        layout = HomeLayout.forView(this);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 1f / 60 : Math.min((now - lastFrame) / 1000f, .032f);
        lastFrame = now;
        if (!paused) {
            if (keyRepeat.isHeld()) {
                if (canBrowse() && shelves.current().size() > 1) {
                    int direction = keyRepeat.poll(now);
                    if (direction != 0) choose(selectedPosition + direction);
                } else clearHeldKeys();
            }
            advance(dt);
        }
        if (!paused) shelfArtwork.prepare(cartridgeCache, shelves, shelf, selectedPosition,
                firstVisiblePosition(), lastVisiblePosition(), gesture == Gesture.SHELF || keyRepeat.isHeld()
                        || isCategoryTransitioning() || Math.abs(shelf - shelfTarget) > .001f,
                canBrowse() && gesture == Gesture.NONE, now);
        canvas.drawColor(Color.BLACK);
        canvas.save();
        canvas.translate(layout.originX, layout.originY);
        canvas.scale(layout.scale, layout.scale);
        canvas.clipRect(0, 0, layout.width, layout.height);
        machine.setLight(preferences.lightTheme);
        float scrim = !hasPreviewScene() && selectedCartridge() != null && insertion > 0f
                ? BackdropRenderer.SCRIM * insertionPhase() : 0f;
        if (categoryMenuOpen) scrim = Math.max(scrim, .45f);
        backdrop.draw(canvas, layout, preferences, scrim);
        drawShelf(canvas);
        if (canBrowse()) letterHint.draw(canvas, layout, now);
        if (mode == Mode.PREVIEW || reveal > .001f) {
            if (hasPreviewScene()) drawPreview(canvas);
            else if (mode == Mode.PREVIEW) drawSeatedRom(canvas);
        }
        if (panel.progress() > 0) drawLiftedMachine(canvas);
        if (categoryMenuOpen) drawCategoryMenu(canvas);
        canvas.restore();
        scheduleNextFrame(now);
    }

    private void scheduleNextFrame(long now) {
        // Each draw replaces the one pending callback, including draws requested by the panel animator.
        removeCallbacks(redraw);
        boolean moving = gesture != Gesture.SHELF && !categoryMenuOpen && !isCategoryTransitioning()
                    && (Math.abs(shelf - shelfTarget) > .0005f || Math.abs(shelfVelocity) > .005f)
                || gesture != Gesture.INSERT && gesture != Gesture.EJECT
                    && (Math.abs(insertion - insertionTarget) > .0005f || Math.abs(insertionVelocity) > .005f)
                || gesture != Gesture.EJECT
                    && (Math.abs(reveal - revealTarget) > .0005f || Math.abs(revealVelocity) > .005f)
                || motion.isRunning() || returning || seatedPresentation.isAnimating()
                || mode == Mode.SHELF && insertionTarget == 1 || isCategoryTransitioning();
        if (!moving) lastFrame = 0;
        if (paused || !isAttachedToWindow()) return;
        long delay = moving ? 0 : Math.min(keyRepeat.nextFrameDelay(now),
                canBrowse() ? letterHint.nextFrameDelay(now) : Long.MAX_VALUE);
        delay = Math.min(delay, shelfArtwork.nextFrameDelay());
        if (delay == 0) postOnAnimation(redraw);
        else if (delay != Long.MAX_VALUE) postDelayed(redraw, delay);
    }

    private void advance(float dt) {
        if (pendingCategory != null) {
            categoryOpacity = Math.max(0, categoryOpacity - dt / .10f);
            if (categoryOpacity == 0) {
                applyCategory(pendingCategory);
                pendingCategory = null; categoryFadingIn = true;
            }
        } else if (categoryFadingIn) {
            categoryOpacity = Math.min(1, categoryOpacity + dt / .14f);
            if (categoryOpacity == 1) categoryFadingIn = false;
        }
        if (gesture != Gesture.SHELF && !categoryMenuOpen && !isCategoryTransitioning()) {
            shelfVelocity += ((shelfTarget - shelf) * 280f - shelfVelocity * 31f) * dt;
            shelf += shelfVelocity * dt;
            if (Math.abs(shelf - shelfTarget) < .0005f && Math.abs(shelfVelocity) < .005f) {
                shelf = shelfTarget; shelfVelocity = 0;
                normalizeShelf();
            }
        }
        if (Math.abs(shelf) > 4096f) normalizeShelf();
        // Let the last horizontal selection arrive before seating it vertically.
        boolean waitingForCenter = insertionTarget == 1 && Math.abs(shelf - shelfTarget) > .01f;
        if (gesture != Gesture.INSERT && gesture != Gesture.EJECT && !waitingForCenter && motion.isRunning()) {
            insertion = motion.advance(dt, layout.cartridgeGeometry(selectedCartridge().platform));
            insertionVelocity = 0;
        } else if (gesture != Gesture.INSERT && gesture != Gesture.EJECT && !waitingForCenter && insertionTarget == 0) {
            motion.stop();
            insertionVelocity += ((insertionTarget - insertion) * 300f - insertionVelocity * 32f) * dt;
            insertion = clamp(insertion + insertionVelocity * dt, 0, 1);
            if (Math.abs(insertion - insertionTarget) < .0007f && Math.abs(insertionVelocity) < .01f) {
                insertion = insertionTarget; insertionVelocity = 0;
            }
        }
        if (gesture != Gesture.EJECT) {
            revealVelocity += ((revealTarget - reveal) * 170f - revealVelocity * 26f) * dt;
            reveal = clamp(reveal + revealVelocity * dt, 0, 1);
            if (Math.abs(reveal - revealTarget) < .001f && Math.abs(revealVelocity) < .01f) {
                reveal = revealTarget; revealVelocity = 0;
            }
        }
        if (mode == Mode.SHELF && insertionTarget == 0 && insertCueStarted) {
            sounds.stop(CartridgeAudio.Effect.INSERT); insertCueStarted = false;
        }
        if (mode == Mode.SHELF && insertionTarget == 1) {
            if (!waitingForCenter && !insertCueStarted
                    && motion.secondsToSeat() <= CartridgeAudio.Effect.INSERT.leadSeconds) {
                insertCueStarted = true; insertCueAt = SystemClock.uptimeMillis();
                playCue(CartridgeAudio.Effect.INSERT);
            }
            if (insertion == 1) seatedHold += dt;
            if (insertion == 1 && seatedHold >= CartridgeMotion.SEAT_HOLD_SECONDS && insertCueStarted
                    && SystemClock.uptimeMillis() - insertCueAt >= CartridgeAudio.Effect.INSERT.leadMillis) {
                insertion = 1; insertionVelocity = 0;
                mode = Mode.PREVIEW; revealTarget = hasPreviewScene() ? 1 : 0;
                insertCueStarted = false;
                haptic(HapticFeedbackConstants.CONFIRM); updateDescription();
                if (preferences.autoLaunchOnInsert && selectedCartridge().rom != null) {
                    seatedPresentation.hideForAutomaticLaunch();
                    requestGameLaunch();
                }
            }
        }
        if (returning && reveal <= .002f) {
            if (!ejectCueStarted) {
                ejectCueStarted = true; ejectCueAt = SystemClock.uptimeMillis();
                playCue(CartridgeAudio.Effect.EJECT);
            }
            if (SystemClock.uptimeMillis() - ejectCueAt >= CartridgeAudio.Effect.EJECT.leadMillis) {
                returning = false; mode = Mode.SHELF; insertionTarget = 0;
                motion.start(insertion, layout.cartridgeGeometry(selectedCartridge().platform), false);
                haptic(HapticFeedbackConstants.CONFIRM); updateDescription();
            }
        }
        seatedPresentation.advance(dt, mode == Mode.PREVIEW && !hasPreviewScene() && !returning);
        // The final motion frame can become idle here. Commit before frame scheduling stops.
        libraryShelf.apply();
    }

    private void startInsertion() {
        launchRequested = false; launchingFeedback = false;
        seatedPresentation.reset(false);
        insertionTarget = 1; insertionVelocity = 0; seatedHold = 0;
        motion.start(insertion, layout.cartridgeGeometry(selectedCartridge().platform), true);
    }

    private void requestGameLaunch() {
        Cartridge cartridge = selectedCartridge();
        if (paused || returning || mode != Mode.PREVIEW || launchRequested || cartridge == null || cartridge.rom == null) return;
        launchRequested = true; launchingFeedback = true; invalidate();
        // Dispatch after the completed seating frame, never from inside Canvas rendering.
        post(launchGame);
    }

    private void dispatchGameLaunch() {
        Cartridge cartridge = selectedCartridge();
        if (!paused && mode == Mode.PREVIEW && !returning && cartridge != null && cartridge.rom != null
                && !gameLauncher.launch(cartridge.rom)) {
            launchingFeedback = false; eject();
        }
    }

    private boolean slotCenterContains(float x, float y) {
        return Math.abs(x - layout.centerX()) <= layout.cardWidth(selectedCartridge().platform) / 2f
                && y >= layout.machineClosedTop() - 36 && y <= layout.height;
    }

    private void drawShelf(Canvas c) {
        float cx = layout.centerX(), width = layout.width;
        if (insertion < .05f) {
            text(c, clock.format(new Date()), 28, 42, 18, INK, Typeface.MONOSPACE, Paint.Align.LEFT);
            drawBattery(c);
            drawWifi(c);
            if (ConnectionState.phase() == ConnectionState.Phase.RUNNING) drawConnection(c);
            if (categoryMenuOpen) {
                paint.setColor(0xff34343c);
                box.set(cx - CATEGORY_HALF, 12, cx + CATEGORY_HALF, 60);
                c.drawRoundRect(box, 8, 8, paint);
            }
            drawKeyBadge(c, preferences.leftShoulderBadge(), cx - 94, 36, 28, 18, false);
            drawKeyBadge(c, preferences.rightShoulderBadge(), cx + 94, 36, 28, 18, false);
            drawCategoryHeader(c, cx);
        }
        boolean resting = panel.progress() == 0;
        if (pictures.isEmpty() && insertion < .05f) libraryShelf.draw(c, layout, language);
        if (resting) machine.drawBack(c, layout, layout.machineClosedTop());
        float clipBottom = layout.slotClipBottom();
        c.saveLayerAlpha(0, 0, width, clipBottom, (int) (categoryOpacity * 255));
        // Non-selected cartridges go behind the manipulated one.
        for (int position = firstVisiblePosition(); position <= lastVisiblePosition(); position++) {
            if (position != selectedPosition) drawCartAt(c, position);
        }
        c.save();
        c.clipRect(0, 0, width, clipBottom);
        if (shelves.current().size() > 0) drawCartAt(c, selectedPosition);
        c.restore();
        drawShelfPosition(c);
        c.restore();
        if (resting) {
            float top = layout.machineClosedTop();
            machine.drawFront(c, layout, top);
            if (insertion < .05f) machine.drawEntries(c, layout, top, entryLabel(Content.SETTINGS),
                    entryLabel(Content.APPS), gesture == Gesture.ENTRY ? pressedEntry : null);
        }
    }

    private boolean libraryActionContains(float x, float y) {
        return libraryShelf.actionContains(layout, x, y);
    }

    private void drawLiftedMachine(Canvas c) {
        float progress = panel.progress();
        paint.setColor(Color.argb((int) (178 * progress), 0, 0, 0));
        c.drawRect(0, 0, layout.width, layout.height, paint);
        float top = layout.machineTop(progress);
        machine.drawBack(c, layout, top);
        machine.drawFront(c, layout, top);
        machine.drawBay(c, layout, top, panel.title(), panel.previousTitle(),
                panel.leavingAlpha(), panel.enteringAlpha(), panel.swap());
        machine.drawEntries(c, layout, top, entryLabel(Content.SETTINGS), entryLabel(Content.APPS),
                gesture == Gesture.ENTRY ? pressedEntry : null);
    }

    private MachineRenderer.Entry entryLabel(Content content) {
        if (panel.isTargetOpen() && panel.content() == content) {
            return new MachineRenderer.Entry(preferences.backBadge(), language.text(R.string.back));
        }
        return content == Content.SETTINGS ? new MachineRenderer.Entry(preferences.settingsBadge(), language.text(R.string.settings))
                : new MachineRenderer.Entry(preferences.appsBadge(), language.text(R.string.apps));
    }

    String categoryLabel(ShelfModel.Category category) {
        return switch (category) {
            case ALL -> language.text(R.string.all_categories);
            case FAVORITES -> language.text(R.string.favorites_category);
            case RECENTS -> language.text(R.string.recents_category);
            default -> category.name();
        };
    }

    private void drawCategoryHeader(Canvas c, float cx) {
        String label = categoryLabel(shelves.category());
        paint.setTypeface(bold);
        float size = 21f;
        paint.setTextSize(size);
        float textWidth = paint.measureText(label);
        float textX = cx - 6f, textY = 43f, arrowLeft = cx + 31f;
        if (textX + textWidth / 2f + 8f > arrowLeft) {
            while (size > 15f && textWidth > CATEGORY_WIDTH - 40f) {
                size -= 1f;
                paint.setTextSize(size);
                textWidth = paint.measureText(label);
            }
            textX = cx - 7f;
            textY = 36f - (paint.ascent() + paint.descent()) / 2f;
            arrowLeft = textX + textWidth / 2f + 6f;
        }
        text(c, label, textX, textY, size, INK, bold, Paint.Align.CENTER);
        Path arrow = new Path();
        arrow.moveTo(arrowLeft, 33);
        arrow.lineTo(arrowLeft + 8f, 33);
        arrow.lineTo(arrowLeft + 4f, 38);
        arrow.close();
        paint.setColor(0xffb9b9c0);
        c.drawPath(arrow, paint);
    }

    String entryText(Content content) { return entryLabel(content).label; }
    String entryKey(Content content) { return entryLabel(content).key; }
    String ejectKey() { return preferences.backBadge(); }
    String startKey() { return preferences.confirmBadge(); }
    String leftShoulderKey() { return preferences.leftShoulderBadge(); }
    String rightShoulderKey() { return preferences.rightShoulderBadge(); }

    private void drawShelfPosition(Canvas c) {
        int count = shelves.current().size();
        if (insertion >= .08f || gesture == Gesture.INSERT && !preferences.swipeToInsert) return;
        int alpha = (int) (255 * (1f - insertion / .08f));
        float cx = layout.centerX(), y = layout.dotsY();
        if (preferences.showTitles) {
            Cartridge selected = selectedCartridge();
            if (selected == null) return;
            String title = selected.title;
            float maxWidth = Math.min(560f, layout.width - 48f);
            paint.setTypeface(bold);
            float size = 22f;
            paint.setTextSize(size);
            while (size > 13f && paint.measureText(title) > maxWidth) {
                size -= 1f;
                paint.setTextSize(size);
            }
            float bottom = layout.machineClosedTop() - 10f;
            float baseline = bottom - paint.descent();
            text(c, title, cx + 1f, baseline + 1f, size, ((alpha * 180 / 255) << 24), bold, Paint.Align.CENTER);
            text(c, title, cx, baseline, size, (alpha << 24) | 0x00f6f4ef, bold, Paint.Align.CENTER);
            paint.setAlpha(255);
            return;
        }
        if (count <= 1) return;
        if (count <= MAX_POSITION_DOTS) {
            for (int i = 0; i < count; i++) {
                paint.setColor(i == selectedIndex() ? 0xffb9b9c0 : 0xff36363e);
                paint.setAlpha(alpha);
                c.drawCircle(cx + (i - (count - 1) / 2f) * 13, y, i == selectedIndex() ? 2.4f : 1.7f, paint);
            }
        } else {
            paint.setTypeface(Typeface.MONOSPACE); paint.setTextSize(15);
            float baseline = y - (paint.ascent() + paint.descent()) / 2;
            text(c, shelfCounter(), cx, baseline, 15,
                    (alpha << 24) | 0x00b9b9c0, Typeface.MONOSPACE, Paint.Align.CENTER);
        }
        paint.setAlpha(255);
    }

    private float categoryLeft() { return layout.centerX() - CATEGORY_HALF; }

    private void drawCategoryMenu(Canvas c) {
        float left = categoryLeft();
        paint.setColor(0xff202025);
        box.set(left, CATEGORY_TOP, left + CATEGORY_WIDTH,
                CATEGORY_TOP + CATEGORY_PADDING * 2 + ShelfModel.Category.values().length * CATEGORY_ROW_HEIGHT);
        c.drawRoundRect(box, 8, 8, paint);
        for (ShelfModel.Category category : ShelfModel.Category.values()) {
            int row = category.ordinal();
            float top = CATEGORY_TOP + CATEGORY_PADDING + row * CATEGORY_ROW_HEIGHT;
            boolean enabled = shelves.shelf(category).size() > 0;
            boolean current = category == shelves.category();
            box.set(left + CATEGORY_PADDING, top, left + CATEGORY_WIDTH - CATEGORY_PADDING,
                    top + CATEGORY_ROW_HEIGHT);
            if (current || row == categoryRow) {
                paint.setColor(row == categoryRow ? 0xff44444e : 0xff34343c);
                c.drawRoundRect(box, 5, 5, paint);
            }
            String label = categoryLabel(category);
            Typeface rowFace = current ? bold : Typeface.DEFAULT;
            paint.setTypeface(rowFace);
            float size = 20f;
            paint.setTextSize(size);
            float y = top + 29f;
            if (paint.measureText(label) > CATEGORY_WIDTH - 44f) {
                while (size > 14f && paint.measureText(label) > CATEGORY_WIDTH - 44f) {
                    size -= 1f;
                    paint.setTextSize(size);
                }
                y = top + CATEGORY_ROW_HEIGHT / 2f - (paint.ascent() + paint.descent()) / 2f;
            }
            text(c, label, left + 17, y, size,
                    enabled ? INK : 0xff777780, rowFace, Paint.Align.LEFT);
            if (current) {
                paint.setColor(0xffc4ab59);
                c.drawCircle(left + CATEGORY_WIDTH - 19, top + CATEGORY_ROW_HEIGHT / 2, 3, paint);
            }
        }
    }

    private boolean categoryLabelContains(float x, float y) {
        return Math.abs(x - layout.centerX()) <= CATEGORY_HALF && y >= 12 && y <= 60;
    }

    private boolean categoryShoulderContains(float x, float y, int direction) {
        float center = layout.centerX() + direction * 94;
        return Math.abs(x - center) <= 22 && y >= 12 && y <= 60;
    }

    /** Side entry under a point on the machine lip, at the lip's current height. */
    private Content entryAt(float x, float y) {
        float top = layout.machineTop(panel.progress());
        if (y < top + HomeLayout.ENTRY_TOP || y > top + HomeLayout.ENTRY_BOTTOM) return null;
        float span = HomeLayout.ENTRY_INSET + HomeLayout.ENTRY_WIDTH;
        if (x >= HomeLayout.ENTRY_INSET && x <= span) return Content.SETTINGS;
        if (x >= layout.width - span && x <= layout.width - HomeLayout.ENTRY_INSET) return Content.APPS;
        return null;
    }

    private int categoryRowAt(float x, float y) {
        float left = categoryLeft();
        if (x < left + CATEGORY_PADDING || x > left + CATEGORY_WIDTH - CATEGORY_PADDING) return -1;
        int row = (int) Math.floor((y - CATEGORY_TOP - CATEGORY_PADDING) / CATEGORY_ROW_HEIGHT);
        return row >= 0 && row < ShelfModel.Category.values().length ? row : -1;
    }

    private boolean categoryMenuContains(float x, float y) {
        float left = categoryLeft();
        return x >= left && x <= left + CATEGORY_WIDTH && y >= CATEGORY_TOP
                && y <= CATEGORY_TOP + CATEGORY_PADDING * 2 + ShelfModel.Category.values().length * CATEGORY_ROW_HEIGHT;
    }

    private void openCategoryMenu() {
        if (!shelfNavigationAvailable() || gesture != Gesture.NONE) return;
        cancelInteraction(); categoryMenuOpen = true;
        categoryRow = (pendingCategory == null ? shelves.category() : pendingCategory).ordinal();
        categoryPressedRow = -1; updateDescription(); invalidate();
    }

    private void closeCategoryMenu() {
        categoryMenuOpen = false; categoryPressedRow = -1;
        cancelInteraction(); updateDescription(); invalidate();
    }

    private ShelfModel.Category adjacentCategory(ShelfModel.Category from, int direction) {
        ShelfModel.Category[] categories = ShelfModel.Category.values();
        for (int offset = 1; offset <= categories.length; offset++) {
            ShelfModel.Category category = categories[Math.floorMod(from.ordinal() + direction * offset, categories.length)];
            if (shelves.shelf(category).size() > 0) return category;
        }
        return from;
    }

    private void confirmCategory() {
        ShelfModel.Category category = ShelfModel.Category.values()[categoryRow];
        if (shelves.shelf(category).size() == 0) return;
        closeCategoryMenu(); switchCategory(category);
    }

    private int firstVisiblePosition() {
        return shelves.current().size() <= 1 ? 0 : (int) Math.ceil(shelf - layout.visibleReach());
    }

    private int lastVisiblePosition() {
        return shelves.current().size() == 0 ? -1 : shelves.current().size() == 1 ? 0
                : (int) Math.floor(shelf + layout.visibleReach());
    }

    private float stepPitch(int slot) {
        Cartridge a = shelves.current().cartridgeAtPosition(slot);
        Cartridge b = shelves.current().cartridgeAtPosition(slot + 1);
        return layout.pitch(a == null ? null : a.platform, b == null ? null : b.platform);
    }

    private float shelfXAt(int position) {
        float cx = layout.centerX();
        if (shelves.current().size() <= 1) return cx + (position - shelf) * layout.pitch();
        int base = (int) Math.floor(shelf);
        float fraction = shelf - base;
        float x = cx - fraction * stepPitch(base);
        if (position >= base) {
            for (int s = base; s < position; s++) x += stepPitch(s);
        } else {
            for (int s = base - 1; s >= position; s--) x -= stepPitch(s);
        }
        return x;
    }

    private void cartBoundsAt(int position, RectF bounds) {
        Platform platform = shelves.current().cartridgeAtPosition(position).platform;
        float baseWidth = layout.cardWidth(platform), baseHeight = layout.cardHeight(platform);
        float restY = layout.restY(platform);
        float distance = position - shelf;
        float focus = Math.max(0, 1 - Math.abs(distance));
        float size = .78f + focus * .22f;
        float x = shelfXAt(position);
        float y = restY + (1 - size) * baseHeight;
        if (position == selectedPosition) {
            float p = insertion;
            CartridgeMotion.Geometry geometry = layout.cartridgeGeometry(platform);
            size *= geometry.widthAtProgress(p) / baseWidth;
            x += fingerX * (1 - smooth(p));
            y = geometry.topAtProgress(p) + (y - restY) * (1 - p);
        } else x += Math.signum(distance) * (1 + Math.abs(distance)) * CartridgeMotion.SIDE_PART * insertionPhase();
        float w = baseWidth * size, h = baseHeight * size;
        bounds.set(x - w / 2, y, x + w / 2, y + h);
    }

    private void drawCartAt(Canvas c, int position) {
        Cartridge cartridge = shelves.current().cartridgeAtPosition(position);
        if (cartridge == null) return;
        float focus = Math.max(0, 1 - Math.abs(position - shelf));
        int alpha = (int) (255 * (.47f + focus * .53f));
        if (position != selectedPosition) alpha *= 1 - insertionPhase();
        cartBoundsAt(position, box);
        if (alpha <= 0 || box.right < 0 || box.left > layout.width) return;
        c.save();
        if (position == selectedPosition && gesture == Gesture.INSERT) {
            c.rotate(fingerX * .04f * (1 - insertion), box.centerX(), box.centerY());
        }
        paint.setAlpha(alpha);
        Bitmap bitmap = cartridge.rom == null ? cartridges.get(cartridge.id) : cartridgeCache.get(cartridge);
        if (bitmap != null) {
            if (position != selectedPosition && alpha < 255) {
                c.drawBitmap(cartridgeRenderer.shadow(cartridge.shape), null, box, paint);
            }
            if (cartridge.finish != CartridgeRenderer.Finish.SOLID) {
                Bitmap interior = cartridge.rom == null
                        ? cartridgeRenderer.interior(cartridge.shape, cartridge.plastic)
                        : cartridgeCache.interior(cartridge);
                if (interior != null) c.drawBitmap(interior, null, box, paint);
                c.drawBitmap(cartridgeRenderer.backing(cartridge.shape), null, box, paint);
            }
            c.drawBitmap(bitmap, null, box, paint);
        } else {
            paint.setColor(0xff35353a); paint.setAlpha(alpha); c.drawRoundRect(box, 9, 9, paint);
            String title = cartridge.title;
            paint.setTypeface(Typeface.DEFAULT); paint.setTextSize(16);
            while (title.length() > 1 && paint.measureText(title) > box.width() - 24) title = title.substring(0, title.length() - 1);
            text(c, title, box.centerX(), box.centerY(), 16, INK, Typeface.DEFAULT, Paint.Align.CENTER);
        }
        paint.setAlpha(255);
        c.restore();
    }

    private void drawBattery(Canvas c) {
        float start = layout.width - 129;
        if (status.isCharging()) {
            Path bolt = new Path();
            bolt.moveTo(start - 11, 28); bolt.lineTo(start - 16, 36); bolt.lineTo(start - 12, 36);
            bolt.lineTo(start - 15, 44); bolt.lineTo(start - 6, 33); bolt.lineTo(start - 10, 33); bolt.close();
            paint.setColor(INK); c.drawPath(bolt, paint);
        }
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.2f); paint.setColor(INK);
        box.set(start, 31, start + 21, 41); c.drawRect(box, paint);
        paint.setStyle(Paint.Style.FILL);
        fill(c, start + 21, 34, start + 23, 38, INK);
        fill(c, start + 2, 33, start + 2 + 17 * status.batteryPercent() / 100f, 39, INK);
        text(c, status.batteryPercent() + "%", start + 29, 42, 16, INK, bold, Paint.Align.LEFT);
    }

    /** A small monitor left of the battery while the computer connection is on. */
    private void drawConnection(Canvas c) {
        float right = layout.width - 129 - (status.isCharging() ? 24 : 10), left = right - 17;
        paint.setColor(INK); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.6f);
        box.set(left, 29, right, 40); c.drawRoundRect(box, 1.5f, 1.5f, paint);
        paint.setStyle(Paint.Style.FILL);
        fill(c, left + 5, 42, right - 5, 43.6f, INK);
    }

    private void drawWifi(Canvas c) {
        float x = layout.width - 37, y = 43;
        paint.setColor(wifi.isConnected() ? INK : 0xff777780);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2.2f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (float radius : new float[]{7, 12, 17}) {
            box.set(x - radius, y - radius, x + radius, y + radius);
            c.drawArc(box, 225, 90, false, paint);
        }
        if (!wifi.isConnected()) c.drawLine(x - 12, y - 19, x + 10, y + 1, paint);
        paint.setStyle(Paint.Style.FILL); c.drawCircle(x, y - 1, 1.6f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawPreview(Canvas c) {
        float width = layout.width, height = layout.height, cx = layout.centerX();
        c.saveLayerAlpha(0, 0, width, height, (int) (reveal * 255));
        fill(c, 0, 0, width, height, 0xff08090d);
        float zoom = 1.045f - reveal * .045f;
        float cover = Math.max(width / DemoScenes.WIDTH, height / DemoScenes.HEIGHT);
        float pictureWidth = DemoScenes.WIDTH * cover, pictureHeight = DemoScenes.HEIGHT * cover;
        c.save(); c.scale(zoom, zoom, cx, height / 2);
        box.set((width - pictureWidth) / 2, (height - pictureHeight) / 2,
                (width + pictureWidth) / 2, (height + pictureHeight) / 2);
        c.drawBitmap(pictures.get(selectedCartridge().id), null, box, paint);
        c.restore();
        paint.setShader(new LinearGradient(0, height / 2, 0, height,
                new int[]{0x00000000, 0xb5000000}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, width, height, paint); paint.setShader(null);
        text(c, selectedCartridge().title, cx, height - 83, 38, selectedCartridge().accent,
                labelFace, Paint.Align.CENTER);
        text(c, selectedCartridge().platform.title, cx, height - 57, 11, 0xffd3d3d5,
                Typeface.MONOSPACE, Paint.Align.CENTER);
        drawEjectButton(c);
        c.restore();
    }

    private void drawSeatedRom(Canvas c) {
        Cartridge cartridge = selectedCartridge();
        float opacity = seatedPresentation.opacity();
        if (cartridge == null || opacity <= 0f) return;
        c.saveLayerAlpha(0, 0, layout.width, layout.machineClosedTop(), Math.round(opacity * 255));
        drawEjectButton(c);
        drawStartButton(c);
        float cx = layout.centerX();
        float areaTop = 68f;
        float areaBottom = layout.machineClosedTop() - 16f;
        float availableHeight = Math.max(160f, areaBottom - areaTop);
        CartridgeRenderer.Spec spec = cartridgeRenderer.spec(cartridge.shape);
        // Box art is a square picture; without it the printed label's own shape sets the card.
        boolean boxed = cartridge.boxart != null && !cartridgeCache.boxartFailed(cartridge);
        Bitmap boxBitmap = boxed ? cartridgeCache.boxart(cartridge) : null;
        float labelAspect = boxed ? 1f : (float) spec.labelWidth / spec.labelHeight;
        float maxCardHeight = boxed ? Math.min(availableHeight * .56f, 188f * layout.cardScale())
                : Math.min(availableHeight * .50f, (cartridge.platform == Platform.GBA ? 116f : 142f) * layout.cardScale());
        float maxCardWidth = boxed ? Math.min(layout.width - 96f, 188f * layout.cardScale())
                : Math.min(layout.width - 96f, 236f * layout.cardScale());
        float cardRadius = boxed ? 8f : 10f;
        float cardWidth = maxCardHeight * labelAspect;
        float cardHeight = maxCardHeight;
        if (cardWidth > maxCardWidth) {
            cardWidth = maxCardWidth;
            cardHeight = cardWidth / labelAspect;
        }
        android.text.TextPaint titlePaint = new android.text.TextPaint(paint);
        titlePaint.setColor(0xfff4f1ea);
        titlePaint.setTypeface(bold);
        int maxTitleWidth = Math.max(120, Math.round(Math.min(520f, layout.width - 64f)));
        android.text.StaticLayout titleLayout = null;
        for (int size = 24; size >= 14; size--) {
            titlePaint.setTextSize(size);
            titleLayout = android.text.StaticLayout.Builder.obtain(
                            cartridge.title, 0, cartridge.title.length(), titlePaint, maxTitleWidth)
                    .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .setMaxLines(2)
                    .setEllipsize(android.text.TextUtils.TruncateAt.END)
                    .build();
            if (titleLayout.getEllipsisCount(titleLayout.getLineCount() - 1) == 0) break;
        }
        float titleHeight = titleLayout.getHeight();
        float statusSpace = launchingFeedback ? 22f : 0f;
        float totalHeight = cardHeight + 16f + titleHeight + 8f + 14f + statusSpace;
        float startY = areaTop + Math.max(0f, (availableHeight - totalHeight) / 2f);
        float cardLeft = cx - cardWidth / 2f, cardTop = startY;

        // A box art still being decoded leaves its place empty rather than flashing the label first.
        if (!boxed || boxBitmap != null) {
            box.set(cardLeft, cardTop + 4f, cardLeft + cardWidth, cardTop + cardHeight + 4f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x58000000);
            c.drawRoundRect(box, cardRadius, cardRadius, paint);

            box.set(cardLeft, cardTop, cardLeft + cardWidth, cardTop + cardHeight);
            Bitmap labelBitmap = boxed ? boxBitmap : cartridgeCache.label(cartridge);
            if (!boxed && labelBitmap == null) cartridgeCache.get(cartridge);
            c.save();
            labelClip.reset();
            labelClip.addRoundRect(box, cardRadius, cardRadius, Path.Direction.CW);
            c.clipPath(labelClip);
            if (labelBitmap != null && !labelBitmap.isRecycled()) {
                paint.setAlpha(255);
                c.drawBitmap(labelBitmap, null, box, paint);
            } else {
                int hue = Math.floorMod(cartridge.title.hashCode(), 360);
                paint.setColor(Color.HSVToColor(new float[]{hue, .35f, .58f}));
                c.drawRect(box, paint);
            }
            c.restore();

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.2f);
            paint.setColor(0x40ffffff);
            c.drawRoundRect(box, cardRadius, cardRadius, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        float titleTop = cardTop + cardHeight + 16f;
        c.save();
        c.translate(cx - maxTitleWidth / 2f, titleTop);
        titleLayout.draw(c);
        c.restore();

        float platformBaseline = titleTop + titleHeight + 18f;
        text(c, cartridge.platform.title, cx, platformBaseline, 12f, 0xffc6c6ce, Typeface.MONOSPACE, Paint.Align.CENTER);
        if (launchingFeedback) {
            text(c, language.text(R.string.launching_game), cx, platformBaseline + 21f, 13f, 0xffc4ab59, Typeface.DEFAULT, Paint.Align.CENTER);
        }
        c.restore();
    }

    private void drawEjectButton(Canvas c) {
        drawHeaderPill(c, 24f, false, preferences.backBadge(), language.text(R.string.eject));
    }

    private void drawStartButton(Canvas c) {
        drawHeaderPill(c, layout.width - 24f, true, preferences.confirmBadge(), language.text(R.string.start_game));
    }

    private void drawHeaderPill(Canvas c, float anchorX, boolean alignRight, String key, String label) {
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(16);
        float textWidth = paint.measureText(label);
        float contentWidth = 20f + 8f + textWidth;
        float pillWidth = Math.max(92f, contentWidth + 28f);
        float left = alignRight ? anchorX - pillWidth : anchorX;
        float top = 20f, bottom = 58f, cy = (top + bottom) / 2f;
        box.set(left, top, left + pillWidth, bottom);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xd9242429);
        c.drawRoundRect(box, 19f, 19f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(0x38ffffff);
        c.drawRoundRect(box, 19f, 19f, paint);
        paint.setStyle(Paint.Style.FILL);
        float startX = left + (pillWidth - contentWidth) / 2f;
        drawKeyBadge(c, key, startX + 10f, cy, 20f, 20f, true);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(16);
        float baseline = cy - (paint.ascent() + paint.descent()) / 2f;
        text(c, label, startX + 28f, baseline, 16, INK, Typeface.DEFAULT, Paint.Align.LEFT);
    }

    private boolean startButtonContains(float x, float y) {
        return !hasPreviewScene() && x > layout.width - START_TOUCH_WIDTH && y < EJECT_TOUCH_BOTTOM;
    }

    private void drawKeyBadge(Canvas c, String key, float x, float y, float width, float height, boolean round) {
        paint.setColor(INK);
        box.set(x - width / 2, y - height / 2, x + width / 2, y + height / 2);
        float radius = round ? height / 2 : 4;
        c.drawRoundRect(box, radius, radius, paint);
        paint.setTypeface(bold); paint.setTextSize(round ? 12 : 10);
        float baseline = y - (paint.ascent() + paint.descent()) / 2;
        text(c, key, x, baseline, round ? 12 : 10, 0xff17171c, bold, Paint.Align.CENTER);
    }

    /** Applies feedback for a setting changed from the panel. */
    void onPreferenceChanged(int row) {
        tick(); updateDescription(); invalidate();
        if (row == 2 && !preferences.sound) sounds.stopAll();
    }

    BackdropRenderer backdrop() { return backdrop; }

    void setCustomWallpaper(Bitmap wallpaper) {
        backdrop.setCustomWallpaper(wallpaper);
        invalidate();
    }

    private Bitmap makeCartridge(Cartridge sample, Bitmap picture) {
        CartridgeRenderer.Spec spec = cartridgeRenderer.spec(sample.platform);
        Bitmap label = Bitmap.createBitmap(spec.labelWidth, spec.labelHeight, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(label);
        // Slot's cover path fills the label panel with a centered image crop.
        float cover = Math.max((float) spec.labelWidth / picture.getWidth(),
                (float) spec.labelHeight / picture.getHeight());
        float w = picture.getWidth() * cover, h = picture.getHeight() * cover;
        box.set((spec.labelWidth - w) / 2, (spec.labelHeight - h) / 2,
                (spec.labelWidth + w) / 2, (spec.labelHeight + h) / 2);
        c.drawBitmap(picture, null, box, paint);
        paint.setShader(new LinearGradient(0, spec.labelHeight * .3f, 0, spec.labelHeight,
                0x00000000, 0xb0000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, spec.labelWidth, spec.labelHeight, paint); paint.setShader(null);
        String[] lines = sample.title.split(" ");
        float size = sample.platform == Platform.GBA ? 33 : 36;
        paint.setTypeface(labelFace); paint.setTextSize(size);
        for (String line : lines) {
            float measured = paint.measureText(line);
            if (measured > spec.labelWidth - 24) size *= (spec.labelWidth - 24) / measured;
            paint.setTextSize(size);
        }
        float baseline = spec.labelHeight * .58f - (lines.length - 1) * size * .6f;
        for (int j = 0; j < lines.length; j++) {
            float y = baseline + j * size * 1.2f;
            text(c, lines[j], spec.labelWidth / 2f + 1, y + 1, size, 0xff18222b, labelFace, Paint.Align.CENTER);
            text(c, lines[j], spec.labelWidth / 2f, y, size, sample.accent, labelFace, Paint.Align.CENTER);
        }
        Bitmap cartridge = cartridgeRenderer.render(sample.platform, sample.plastic, sample.finish, label);
        label.recycle();
        return cartridge;
    }

    private void fill(Canvas c, float l, float t, float r, float b, int color) {
        paint.setColor(color); c.drawRect(l, t, r, b, paint);
    }

    private void text(Canvas c, String value, float x, float y, float size, int color,
                      Typeface face, Paint.Align align) {
        paint.setColor(color); paint.setTypeface(face); paint.setTextSize(size); paint.setTextAlign(align);
        c.drawText(value, x, y, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            letterHint.clear();
            clearHeldKeys();
            pressedEntry = null;
            if (paused) return true;
            downX = toX(event.getX()); downY = toY(event.getY());
            if (!panel.isClosed()) { startPanelGesture(event); return true; }
            if (isCategoryTransitioning() || returning || insertionTarget == 1 && mode == Mode.SHELF
                    || hasPreviewScene() && revealTarget == 1 && reveal < .98f) return true;
            startTracking(event);
            if (downX < 0 || downX > layout.width || downY < 0 || downY > layout.height) {
                cancelInteraction(); return true;
            }
            initialShelf = shelf; initialInsertion = insertion;
            shelfVelocity = insertionVelocity = 0;
            activeCard = false;
            if (selectedCartridge() != null) {
                cartBoundsAt(canBrowse() ? Math.round(shelf) : selectedPosition, box);
                box.inset(-12, -18);
                activeCard = box.contains(downX, downY);
            }
            Content entry = mode == Mode.SHELF && insertion < .05f ? entryAt(downX, downY) : null;
            if (categoryMenuOpen) {
                if (!categoryMenuContains(downX, downY)) { closeCategoryMenu(); return true; }
                categoryPressedRow = categoryRowAt(downX, downY);
                gesture = Gesture.CATEGORY_MENU;
            } else if (canBrowse() && categoryLabelContains(downX, downY)) {
                gesture = Gesture.CATEGORY_BUTTON;
            } else if (canBrowse() && categoryShoulderContains(downX, downY, -1)) {
                gesture = Gesture.CATEGORY_PREVIOUS;
            } else if (canBrowse() && categoryShoulderContains(downX, downY, 1)) {
                gesture = Gesture.CATEGORY_NEXT;
            } else if (mode == Mode.PREVIEW && downX < EJECT_TOUCH_RIGHT && downY < EJECT_TOUCH_BOTTOM) {
                gesture = Gesture.BUTTON;
            } else if (mode == Mode.PREVIEW && startButtonContains(downX, downY)) {
                gesture = Gesture.START_BUTTON;
            } else if (entry != null) {
                gesture = canOpenPanel() ? Gesture.ENTRY : Gesture.PANEL;
                pressedEntry = entry; invalidate();
            } else if (libraryActionContains(downX, downY)) gesture = Gesture.LIBRARY;
            else {
                gesture = Gesture.UNDECIDED;
                if (activeCard) postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
            }
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) { cancelInteraction(); return true; }
        if (action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.getActionIndex()) == pointer) {
            cancelInteraction(); return true;
        }
        int index = event.findPointerIndex(pointer);
        if (index < 0 || gesture == Gesture.NONE) return true;
        tracker.addMovement(event);
        float x = toX(event.getX(index)), y = toY(event.getY(index));
        float dx = x - downX, dy = y - downY;
        if (action == MotionEvent.ACTION_MOVE) {
            if (Math.hypot(dx, dy) > touchSlop / layout.scale) removeCallbacks(longPress);
            if (gesture == Gesture.UNDECIDED && Math.hypot(dx, dy) > touchSlop / layout.scale) {
                if (mode == Mode.PREVIEW) {
                    if (dy < 0 && Math.abs(dy) > Math.abs(dx) * 1.2f) gesture = Gesture.EJECT;
                } else if (canBrowse() && shelves.current().size() > 1 && Math.abs(dx) > Math.abs(dy) * 1.15f) {
                    gesture = Gesture.SHELF;
                } else if (canBrowse() && (activeCard || preferences.swipeToInsert
                        && downY >= HomeLayout.HEADER_BOTTOM && downY <= layout.swipeBottom())
                        && dy > 0 && Math.abs(dy) > Math.abs(dx) * 1.15f) {
                    clearHeldKeys();
                    selectPosition(Math.round(shelf));
                    gesture = Gesture.INSERT;
                    shelf = shelfTarget = selectedPosition;
                    if (!preferences.swipeToInsert) haptic(HapticFeedbackConstants.KEYBOARD_TAP);
                }
            }
            if (gesture == Gesture.ENTRY && entryAt(x, y) != pressedEntry && Math.hypot(dx, dy) >= 20) {
                pressedEntry = null; gesture = Gesture.PANEL;
            }
            if (gesture == Gesture.SHELF) {
                shelf = initialShelf - dx / layout.pitch();
                selectPosition(Math.round(shelf));
            } else if (gesture == Gesture.INSERT) {
                // Swipes only recognize intent; direct manipulation belongs to drag mode.
                if (!preferences.swipeToInsert) {
                    insertion = clamp(initialInsertion + dy / layout.insertionDistance(selectedCartridge().platform), 0, 1);
                    fingerX = clamp(dx * .2f, -28, 28);
                    boolean ready = insertion >= COMMIT;
                    if (ready && !railCaught) { railCaught = true; tick(); }
                    if (!ready) railCaught = false;
                }
            } else if (gesture == Gesture.EJECT) {
                if (!preferences.swipeToInsert && hasPreviewScene()) {
                    reveal = clamp(1 + dy / 190f, 0, 1);
                    revealVelocity = 0;
                }
            }
            invalidate(); return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            removeCallbacks(longPress);
            tracker.computeCurrentVelocity(1000);
            float velocityX = tracker.getXVelocity(pointer) / layout.scale;
            Gesture completed = gesture;
            Content pressed = pressedEntry;
            gesture = Gesture.NONE; pointer = -1; railCaught = false; fingerX = 0; pressedEntry = null;
            if (completed == Gesture.SHELF) {
                int target = Math.round(shelf);
                if (Math.abs(velocityX) > 420) {
                    target = velocityX < 0 ? (int) Math.floor(shelf) + 1 : (int) Math.ceil(shelf) - 1;
                }
                choose(target);
            } else if (completed == Gesture.INSERT) {
                boolean commit = preferences.swipeToInsert
                        ? dy >= SWIPE_DISTANCE && dy > Math.abs(dx) * 1.15f : insertion >= COMMIT;
                insertionTarget = commit ? 1 : 0;
                if (commit) {
                    startInsertion();
                    if (preferences.swipeToInsert) tick();
                } else motion.stop();
            } else if (completed == Gesture.EJECT) {
                boolean commit = preferences.swipeToInsert
                        ? -dy >= SWIPE_DISTANCE && -dy > Math.abs(dx) * 1.2f : -dy > 190f * .38f;
                if (commit) eject(); else revealTarget = hasPreviewScene() ? 1 : 0;
            } else if (completed == Gesture.CATEGORY_BUTTON && categoryLabelContains(x, y) && Math.hypot(dx, dy) < 20) {
                performClick(); openCategoryMenu();
            } else if ((completed == Gesture.CATEGORY_PREVIOUS || completed == Gesture.CATEGORY_NEXT)
                    && Math.hypot(dx, dy) < 20) {
                int direction = completed == Gesture.CATEGORY_PREVIOUS ? -1 : 1;
                if (categoryShoulderContains(x, y, direction)) {
                    performClick(); switchCategory(adjacentCategory(shelves.category(), direction));
                }
            } else if (completed == Gesture.CATEGORY_MENU && Math.hypot(dx, dy) < 20) {
                int row = categoryRowAt(x, y);
                if (row >= 0 && row == categoryPressedRow) {
                    categoryRow = row; performClick(); confirmCategory();
                }
            } else if (completed == Gesture.BUTTON && Math.hypot(dx, dy) < 20 && x < EJECT_TOUCH_RIGHT && y < EJECT_TOUCH_BOTTOM) {
                performClick(); eject();
            } else if (completed == Gesture.START_BUTTON && Math.hypot(dx, dy) < 20 && startButtonContains(x, y)) {
                performClick(); requestGameLaunch();
            } else if (completed == Gesture.UNDECIDED && mode == Mode.PREVIEW && Math.hypot(dx, dy) < 10
                    && slotCenterContains(downX, downY) && slotCenterContains(x, y)) {
                performClick(); requestGameLaunch();
            } else if (completed == Gesture.ENTRY && Math.hypot(dx, dy) < 20 && pressed != null
                    && entryAt(x, y) == pressed && (panel.isClosed() ? canOpenPanel() : true)) {
                performClick(); host.onEntry(pressed, false);
            } else if (completed == Gesture.LIBRARY && libraryActionContains(x, y) && Math.hypot(dx, dy) < 20) {
                performClick(); host.onLibrary();
            } else if (completed == Gesture.UNDECIDED && canBrowse() && Math.hypot(dx, dy) < 10) {
                for (int position = firstVisiblePosition(); position <= lastVisiblePosition(); position++) {
                    cartBoundsAt(position, box);
                    if (position != selectedPosition && box.contains(x, y)) {
                        choose(position); performClick(); break;
                    }
                }
            }
            stopTracker(); invalidate(); return true;
        }
        return true;
    }

    private void startTracking(MotionEvent event) {
        stopTracker(); tracker = VelocityTracker.obtain(); tracker.addMovement(event);
        pointer = event.getPointerId(0);
    }

    /** While the machine is lifted it covers the frame and the shelf only receives entry taps. */
    private void startPanelGesture(MotionEvent event) {
        cancelTouchInteraction();
        startTracking(event);
        Content entry = entryAt(downX, downY);
        if (entry != null) {
            gesture = Gesture.ENTRY; pressedEntry = entry; invalidate();
        } else gesture = Gesture.PANEL;
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    private boolean isHandledKey(int key) {
        return key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_UP
                || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_BUTTON_A
                || key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_SPACE || key == KeyEvent.KEYCODE_BACK
                || key == KeyEvent.KEYCODE_ESCAPE || key == KeyEvent.KEYCODE_BUTTON_SELECT
                || key == KeyEvent.KEYCODE_BUTTON_START || key == KeyEvent.KEYCODE_BUTTON_THUMBR
                || entryForKey(key) != null || isCategoryKey(key);
    }

    private boolean isCategoryKey(int key) {
        return key == KeyEvent.KEYCODE_BUTTON_L1 || key == KeyEvent.KEYCODE_BUTTON_R1
                || key == KeyEvent.KEYCODE_Q || key == KeyEvent.KEYCODE_E;
    }

    /** Y/MENU/S reach settings; X and keyboard A reach apps. Start asks for the current item's menu. */
    static Content entryForKey(int key) {
        if (key == KeyEvent.KEYCODE_MENU || key == KeyEvent.KEYCODE_BUTTON_Y
                || key == KeyEvent.KEYCODE_S) return Content.SETTINGS;
        if (key == KeyEvent.KEYCODE_BUTTON_X || key == KeyEvent.KEYCODE_A) return Content.APPS;
        return null;
    }

    static boolean isBackKey(int key) {
        return key == InteractionPreferences.primaryBackKey() || key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE;
    }

    boolean handleKeyDown(int key, KeyEvent event) {
        if (!isHandledKey(key)) return false;
        if (event.getRepeatCount() > 0 || paused || returning) return true;
        if (key != KeyEvent.KEYCODE_DPAD_UP && key != KeyEvent.KEYCODE_DPAD_DOWN) letterHint.clear();
        if (!panel.isClosed()) {
            clearHeldKeys(); cancelTouchInteraction();
            Content entry = entryForKey(key);
            if (isBackKey(key)) { if (panel.isTargetOpen()) host.onPanelBack(); }
            else if (entry != null) host.onEntry(entry, true);
            return true;
        }
        if (categoryMenuOpen) {
            clearHeldKeys(); cancelTouchInteraction();
            if (isBackKey(key) || key == KeyEvent.KEYCODE_BUTTON_THUMBR) {
                closeCategoryMenu();
            } else if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                ShelfModel.Category from = pendingCategory == null ? shelves.category() : pendingCategory;
                switchFromWheel(adjacentCategory(from, key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1));
            } else if (AppsDrawer.isConfirm(key)) closeCategoryMenu();
            return true;
        }
        if (isCategoryKey(key)) {
            clearHeldKeys();
            ShelfModel.Category from = pendingCategory == null ? shelves.category() : pendingCategory;
            switchCategory(adjacentCategory(from,
                    key == KeyEvent.KEYCODE_BUTTON_L1 || key == KeyEvent.KEYCODE_Q ? -1 : 1));
            return true;
        }
        // R3 raises the category dropdown; L1/R1 and Q/E keep switching directly.
        if (key == KeyEvent.KEYCODE_BUTTON_THUMBR) {
            openCategoryMenu();
            return true;
        }
        if (isCategoryTransitioning()) return true;
        boolean horizontal = key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (!horizontal) clearHeldKeys();
        cancelTouchInteraction();
        Content entry = entryForKey(key);
        if ((key == KeyEvent.KEYCODE_MENU || key == KeyEvent.KEYCODE_BUTTON_SELECT
                        || key == KeyEvent.KEYCODE_BUTTON_START)
                && canBrowse() && selectedCartridge() != null && selectedCartridge().rom != null) {
            host.onCartridgeMenu(selectedCartridge()); return true;
        }
        if (key == KeyEvent.KEYCODE_BUTTON_SELECT || key == KeyEvent.KEYCODE_BUTTON_START) return true;
        if (selectedCartridge() == null && (key == InteractionPreferences.primaryConfirmKey() || key == KeyEvent.KEYCODE_ENTER)) {
            host.onLibrary(); return true;
        }
        if (entry != null) { if (canOpenPanel()) host.onEntry(entry, true); return true; }
        if (mode == Mode.PREVIEW) {
            if (isBackKey(key) || key == KeyEvent.KEYCODE_DPAD_UP) eject();
            else if (AppsDrawer.isConfirm(key)) requestGameLaunch();
        } else if (horizontal) {
            if (canBrowse() && shelves.current().size() > 1) {
                int direction = keyRepeat.press(key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1, SystemClock.uptimeMillis());
                if (direction != 0) choose(selectedPosition + direction);
            }
        } else if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (canBrowse()) {
                int delta = shelves.current().letterJumpDelta(key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                if (delta != 0) {
                    choose(selectedPosition + delta);
                    letterHint.show(selectedCartridge().title, SystemClock.uptimeMillis());
                }
            } else if (key == KeyEvent.KEYCODE_DPAD_UP) { insertionTarget = 0; motion.stop(); }
        } else if (isBackKey(key)) { insertionTarget = 0; motion.stop(); }
        else if (canBrowse()) { startInsertion(); tick(); }
        invalidate(); return true;
    }

    boolean handleKeyUp(int key, KeyEvent event) {
        if (!isHandledKey(key)) return false;
        if (event.isCanceled()) clearHeldKeys();
        else if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            keyRepeat.release(key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1, SystemClock.uptimeMillis());
        }
        invalidate();
        return true;
    }

    boolean canBrowse() {
        return shelfNavigationAvailable() && !categoryMenuOpen && !isCategoryTransitioning()
                && gesture != Gesture.CATEGORY_BUTTON && gesture != Gesture.CATEGORY_MENU
                && gesture != Gesture.CATEGORY_PREVIOUS && gesture != Gesture.CATEGORY_NEXT;
    }

    private boolean shelfNavigationAvailable() {
        return !paused && mode == Mode.SHELF && panel.isClosed() && !returning
                && shelves.current().size() > 0 && insertionTarget == 0 && insertion < .001f
                && reveal < .001f && gesture != Gesture.INSERT && gesture != Gesture.EJECT;
    }

    /** Entries stay reachable on an empty shelf but never during insertion, ejection or menus. */
    boolean canOpenPanel() {
        return !paused && mode == Mode.SHELF && !returning && insertionTarget == 0 && insertion < .001f
                && reveal < .001f && !categoryMenuOpen && !isCategoryTransitioning()
                && gesture != Gesture.INSERT && gesture != Gesture.EJECT && gesture != Gesture.SHELF;
    }

    boolean canSwitchCategory() { return shelfNavigationAvailable() && !categoryMenuOpen && gesture == Gesture.NONE; }

    boolean switchCategory(ShelfModel.Category category) {
        return switchCategory(category, false);
    }

    /** The open category wheel follows the highlighted row instead of waiting for a confirm. */
    private boolean switchFromWheel(ShelfModel.Category category) {
        return switchCategory(category, true);
    }

    private boolean switchCategory(ShelfModel.Category category, boolean fromWheel) {
        ShelfModel.Category destination = pendingCategory == null ? shelves.category() : pendingCategory;
        boolean allowed = fromWheel ? shelfNavigationAvailable() && gesture == Gesture.NONE : canSwitchCategory();
        if (!allowed || category == destination || shelves.shelf(category).size() == 0) return false;
        cancelInteraction();
        pendingCategory = category; categoryFadingIn = false;
        if (fromWheel) categoryRow = category.ordinal();
        updateDescription(); invalidate();
        return true;
    }

    private void applyCategory(ShelfModel.Category category) {
        shelves.selectCategory(category);
        selectedPosition = selectedIndex();
        shelf = shelfTarget = selectedPosition; shelfVelocity = 0;
        insertion = insertionVelocity = 0;
        motion.stop(); seatedHold = 0;
        updateDescription(); invalidate();
        persistSelection();
    }

    private void finishCategoryTransition() {
        if (pendingCategory != null) applyCategory(pendingCategory);
        pendingCategory = null; categoryFadingIn = false; categoryOpacity = 1;
    }

    private void clearHeldKeys() { keyRepeat.clear(); }

    void goBack() {
        if (!panel.isClosed()) { if (panel.isTargetOpen()) host.onPanelBack(); return; }
        if (categoryMenuOpen) { closeCategoryMenu(); return; }
        if (isCategoryTransitioning()) { finishCategoryTransition(); invalidate(); return; }
        cancelInteraction();
        if (mode == Mode.PREVIEW) eject(); else { insertionTarget = 0; motion.stop(); }
        invalidate();
    }

    /** Home returns to a stable resting shelf with no pending menus, gestures or lifted machine. */
    void settleForHome() {
        categoryMenuOpen = false; categoryPressedRow = -1;
        cancelInteraction(); finishCategoryTransition();
        panel.snap(false, panel.content());
        updateDescription(); invalidate();
    }

    private void choose(int position) {
        if (!canBrowse() || shelves.current().size() <= 1) return;
        letterHint.clear();
        selectPosition(position);
        shelfTarget = position;
        invalidate();
    }

    private void selectPosition(int position) {
        int index = shelves.current().indexAtPosition(position);
        boolean changed = index != selectedIndex();
        if (changed) browseTick();
        selectedPosition = position;
        shelves.current().select(position);
        updateDescription();
        if (changed) persistSelection();
    }

    private void normalizeShelf() {
        if (shelves.current().size() == 0) return;
        int offset = selectedPosition - selectedIndex();
        shelf -= offset; shelfTarget -= offset; initialShelf -= offset;
        selectedPosition -= offset;
    }

    private void eject() {
        if (returning) return;
        removeCallbacks(launchGame);
        launchingFeedback = false;
        clearHeldKeys();
        returning = true; ejectCueStarted = false; ejectCueAt = 0;
        // Real cartridges have no scene to dismiss; proceed straight to the audio lead.
        if (!hasPreviewScene()) reveal = revealVelocity = 0;
        revealTarget = 0; updateDescription(); invalidate();
    }

    private boolean hasPreviewScene() {
        Cartridge cartridge = selectedCartridge();
        return cartridge != null && pictures.containsKey(cartridge.id);
    }

    private void playCue(CartridgeAudio.Effect effect) {
        if (preferences.sound) sounds.play(effect);
    }

    private void tick() { haptic(HapticFeedbackConstants.CLOCK_TICK); }
    private void browseTick() {
        long now = SystemClock.uptimeMillis();
        if (!preferences.haptics || now < nextBrowseHapticAt) return;
        nextBrowseHapticAt = now + BROWSE_HAPTIC_INTERVAL;
        browseHapticCount++;
        haptic(HapticFeedbackConstants.CLOCK_TICK);
    }
    private void haptic(int kind) { if (preferences.haptics) performHapticFeedback(kind); }

    void cancelInteraction() {
        letterHint.clear();
        clearHeldKeys();
        cancelTouchInteraction();
    }

    private void cancelTouchInteraction() {
        removeCallbacks(longPress);
        if (gesture == Gesture.INSERT) { insertionTarget = 0; motion.stop(); }
        if (gesture == Gesture.SHELF) shelfTarget = selectedPosition;
        if (gesture == Gesture.EJECT) revealTarget = hasPreviewScene() ? 1 : 0;
        gesture = Gesture.NONE; pointer = -1; fingerX = 0; railCaught = false; pressedEntry = null;
        stopTracker(); invalidate();
    }

    void loseFocus() {
        categoryMenuOpen = false; categoryPressedRow = -1;
        cancelInteraction(); finishCategoryTransition(); updateDescription();
    }

    void pause() {
        loseFocus(); panel.finishAnimation(); paused = true;
        removeCallbacks(launchGame); launchingFeedback = false;
        removeCallbacks(redraw); status.stop(); wifi.stop(); sounds.stopAll(); lastFrame = 0;
    }

    void resume() {
        if (paused) seatedPresentation.returned();
        paused = false; lastFrame = 0; launchRequested = false; launchingFeedback = false;
        clock.setTimeZone(TimeZone.getDefault());
        if (isAttachedToWindow()) { status.start(); wifi.start(); }
        invalidate();
    }

    private void stopTracker() {
        if (tracker != null) { tracker.recycle(); tracker = null; }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!paused) { status.start(); wifi.start(); }
        ConnectionState.add(connectionListener);
    }

    @Override protected void onDetachedFromWindow() {
        ConnectionState.remove(connectionListener);
        removeCallbacks(launchGame);
        loseFocus(); panel.finishAnimation(); removeCallbacks(redraw); status.stop();
        wifi.stop(); sounds.release(); cartridgeCache.release(); super.onDetachedFromWindow();
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) loseFocus();
    }

    void save(Bundle bundle) {
        bundle.putString("shelf_category", (pendingCategory == null ? shelves.category() : pendingCategory).name());
        for (ShelfModel.Category category : ShelfModel.Category.values()) {
            Cartridge cartridge = shelves.shelf(category).selectedCartridge();
            String selectedId = category == ShelfModel.Category.RECENTS && libraryShelf.pendingLaunchedId() != null
                    ? libraryShelf.pendingLaunchedId()
                    : cartridge == null ? null : cartridge.id;
            bundle.putString("shelf_selected_" + category.name(), selectedId);
        }
        bundle.putBoolean("inserted", mode == Mode.PREVIEW && !returning);
        bundle.putBoolean("panel_open", panel.isTargetOpen());
        bundle.putString("panel_content", panel.content().name());
    }

    void restore(Bundle bundle) {
        removeCallbacks(launchGame); launchRequested = false; launchingFeedback = false;
        if (shelves.shelf(ShelfModel.Category.ALL).size() == 0) libraryShelf.defer(bundle);
        categoryMenuOpen = categoryFadingIn = false;
        categoryPressedRow = -1; pendingCategory = null; categoryOpacity = 1;
        cancelInteraction();
        sounds.stopAll(); insertCueStarted = ejectCueStarted = false;
        motion.stop(); seatedHold = 0;
        insertCueAt = ejectCueAt = 0;
        returning = false;
        shelfVelocity = insertionVelocity = revealVelocity = 0;
        fingerX = 0;
        restoreShelfState(bundle);
        Content content = Content.SETTINGS;
        for (Content item : Content.values()) if (item.name().equals(bundle.getString("panel_content"))) content = item;
        panel.snap(mode != Mode.PREVIEW && bundle.getBoolean("panel_open"), content);
        updateDescription(); invalidate();
    }

    private void restoreLibraryState(Bundle bundle) {
        shelfVelocity = insertionVelocity = revealVelocity = 0;
        restoreShelfState(bundle);
        if (mode == Mode.PREVIEW) panel.snap(false, panel.content());
        updateDescription(); invalidate();
    }

    private void restoreShelfState(Bundle bundle) {
        motion.stop(); seatedHold = 0;
        for (ShelfModel.Category category : ShelfModel.Category.values()) {
            shelves.shelf(category).restoreSelection(bundle.getString("shelf_selected_" + category.name()));
        }
        shelves.selectCategory(ShelfModel.Category.ALL);
        String categoryName = bundle.getString("shelf_category", ShelfModel.Category.ALL.name());
        for (ShelfModel.Category category : ShelfModel.Category.values()) {
            if (category.name().equals(categoryName)) shelves.selectCategory(category);
        }
        selectedPosition = selectedIndex();
        shelf = shelfTarget = Math.max(0, selectedPosition);
        nextBrowseHapticAt = 0;
        boolean inserted = bundle.getBoolean("inserted") && selectedCartridge() != null
                && selectedCartridge().id.equals(bundle.getString("shelf_selected_" + shelves.category().name()));
        if (inserted) {
            mode = Mode.PREVIEW; insertion = insertionTarget = 1;
            reveal = revealTarget = hasPreviewScene() ? 1 : 0;
        } else {
            mode = Mode.SHELF; insertion = insertionTarget = reveal = revealTarget = 0;
        }
        seatedPresentation.reset(inserted && !hasPreviewScene());
    }

    private void persistSelection() {
        if (!pictures.isEmpty()) return;
        android.content.SharedPreferences.Editor editor = getContext().getSharedPreferences("shelf", 0).edit();
        editor.putString("category", shelves.category().name());
        for (ShelfModel.Category category : ShelfModel.Category.values()) {
            Cartridge selected = shelves.shelf(category).selectedCartridge();
            String selectedId = category == ShelfModel.Category.RECENTS && libraryShelf.pendingLaunchedId() != null
                    ? libraryShelf.pendingLaunchedId()
                    : selected == null ? null : selected.id;
            if (selectedId != null) editor.putString(category.name(), selectedId);
        }
        editor.apply();
    }

    private void onStatusChanged() {
        clock.setTimeZone(TimeZone.getDefault());
        if (!paused && insertion < .05f) invalidate();
    }

    private void updateDescription() {
        setContentDescription(categoryMenuOpen ? language.text(R.string.category_description,
                categoryLabel(ShelfModel.Category.values()[categoryRow]))
                : panel != null && panel.isTargetOpen() ? panel.title()
                : selectedCartridge() == null ? language.text(R.string.shelf_description)
                : language.text(mode == Mode.PREVIEW ? R.string.inserted_description : R.string.cartridge_description,
                        selectedCartridge().title, selectedCartridge().platform.name()));
    }

    private float toX(float x) { return layout.toLogicalX(x); }
    private float toY(float y) { return layout.toLogicalY(y); }
    private static float smooth(float x) { return x * x * (3 - 2 * x); }
    private static float clamp(float x, float low, float high) { return Math.max(low, Math.min(high, x)); }
    // Package-private observations used by on-device interaction checks.
    private Cartridge selectedCartridge() { return shelves.current().selectedCartridge(); }
    int selectedIndex() { return shelves.current().selectedIndex(); }
    String selectedCartridgeId() { return selectedCartridge().id; }
    ShelfModel.Category currentCategory() { return shelves.category(); }
    String shelfCounter() { return (selectedIndex() + 1) + " / " + shelves.current().size(); }
    boolean isCategoryMenuOpen() { return categoryMenuOpen; }
    int categoryMenuRow() { return categoryRow; }
    boolean isCategoryTransitioning() { return pendingCategory != null || categoryFadingIn; }
    float categoryOpacity() { return categoryOpacity; }
    boolean isNavigationKeyHeld() { return keyRepeat.isHeld(); }
    String letterHintText() { return letterHint.text(SystemClock.uptimeMillis()); }
    float shelfTargetPosition() { return shelfTarget; }
    int browseHapticCount() { return browseHapticCount; }
    Mode currentMode() { return mode; }
    float insertionProgress() { return insertion; }
    float insertionPhase() { return layout.cartridgeGeometry(selectedCartridge().platform).phaseAtProgress(insertion); }
    void selectedCardBounds(RectF bounds) { cartBoundsAt(selectedPosition, bounds); }
    void adjacentCardBounds(int offset, RectF bounds) { cartBoundsAt(selectedPosition + offset, bounds); }
    float shelfPosition() { return shelf; }
    float revealProgress() { return reveal; }
    boolean isSettingsOpen() { return panel.isTargetOpen() && panel.content() == Content.SETTINGS; }
    boolean isAppsOpen() { return panel.isTargetOpen() && panel.content() == Content.APPS; }
    InteractionPreferences interactionPreferences() { return preferences; }
    CartridgeAudio cartridgeAudio() { return sounds; }
    ShelfStatusObserver statusObserver() { return status; }
    Platform selectedPlatform() { return selectedCartridge().platform; }
    float selectedCardWidth() { return layout.cardWidth(selectedCartridge().platform); }
    float selectedCardHeight() { return layout.cardHeight(selectedCartridge().platform); }
    float selectedCardTop() { return layout.restY(selectedCartridge().platform); }
    boolean isLaunchingFeedbackShown() { return launchingFeedback; }
    float seatedInformationOpacity() { return seatedPresentation.opacity(); }
    float screenX(float x) { return layout.toScreenX(x); }
    float screenY(float y) { return layout.toScreenY(y); }
}
