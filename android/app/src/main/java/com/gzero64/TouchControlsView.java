package com.gzero64;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.input.InputManager;
import android.util.SparseIntArray;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import java.io.File;
import java.io.IOException;

/**
 * On-screen N64 controller for port 1 (adapted from NFSU-X's race controls), in two schemes:
 * - Standard: analog stick (a finger that lands on it keeps it until lifted, wherever it slides)
 *   and the N64 buttons. Used in menus and whenever the racing scheme is off.
 * - Racing (controller layout "Racing", during a race): steering arrows at each side, a drift
 *   button above each arrow, boost and air brake; the machine accelerates by itself.
 * Each scheme has its own layout file and presets. Empty screen space belongs to SDL. Shown
 * according to the quick menu's mode: automatic (hidden while a controller is connected), always
 * or never.
 */
final class TouchControlsView extends View implements InputManager.InputDeviceListener {
    static final int MODE_AUTO = 0, MODE_ALWAYS = 1, MODE_NEVER = 2;
    private static final int STANDARD = 0, RACING = 1;
    private static final int STICK_RANGE = 80; // full N64 deflection
    private static final int BUTTON_A = 0x8000;

    private final TouchControlArtwork artwork;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SparseIntArray pointers = new SparseIntArray();
    private final InputManager inputManager;
    private final TouchLayoutStore[] stores = new TouchLayoutStore[2];
    private final TouchLayout[] layouts = new TouchLayout[2];
    private final RectF[][] boundsSets = new RectF[2][];
    private int scheme = STANDARD;      // what is drawn and pressed now
    private boolean racingSetting = true; // controller layout "Racing" (quick menu)
    private boolean inRace;
    private TouchLayout originalLayout;
    private boolean editing;
    private boolean snapToGrid;
    private int mode;
    private boolean controllerConnected;
    private int held;
    private float scale;
    private int stickPointer = -1;
    private float stickX, stickY; // -1..1, y up
    private int dragPointer = -1;
    private int dragButton = -1;
    private int selectedButton = -1;
    private float dragOffsetX, dragOffsetY;
    private Runnable selectionChanged;

    TouchControlsView(Context context, File directory) {
        super(context);
        artwork = new TouchControlArtwork(context.getResources());
        inputManager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        stores[STANDARD] = new TouchLayoutStore(directory, "touch-controls.xml", TouchLayout.STANDARD);
        stores[RACING] = new TouchLayoutStore(directory, "touch-controls-racing.xml", TouchLayout.RACING);
        for (int s = 0; s < 2; s++) {
            layouts[s] = stores[s].load();
            boundsSets[s] = new RectF[layouts[s].IDS.length];
            for (int i = 0; i < boundsSets[s].length; i++) boundsSets[s][i] = new RectF();
        }
        android.content.SharedPreferences prefs = context.getSharedPreferences("touch", Context.MODE_PRIVATE);
        mode = prefs.getInt("mode", MODE_AUTO);
        racingSetting = prefs.getBoolean("racing", true);
        controllerConnected = findController();
    }

    private TouchLayout layout() { return layouts[scheme]; }
    private RectF[] bounds() { return boundsSets[scheme]; }
    private String id(int index) { return layout().IDS[index]; }
    private int indexOf(String id) {
        String[] ids = layout().IDS;
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(id)) return i;
        return -1;
    }

    // ── Visibility and scheme ───────────────────────────────────────────────────────────────────

    int getMode() { return mode; }

    void setMode(int value) {
        mode = value;
        getContext().getSharedPreferences("touch", Context.MODE_PRIVATE).edit().putInt("mode", value).apply();
        cancelInput();
    }

    /** The quick menu's controller layout: the racing scheme is used in races when it is on. */
    void setRacingLayout(boolean racing) {
        racingSetting = racing;
        getContext().getSharedPreferences("touch", Context.MODE_PRIVATE).edit().putBoolean("racing", racing).apply();
        updateScheme();
    }

    boolean editingRacing() { return scheme == RACING; }

    private boolean shown() {
        return editing || mode == MODE_ALWAYS || (mode == MODE_AUTO && !controllerConnected);
    }

    private void updateScheme() {
        int wanted = editing ? scheme : (racingSetting && inRace ? RACING : STANDARD);
        if (wanted != scheme) {
            scheme = wanted;
            cancelInput();
            invalidate();
        } else {
            send(); // the racing scheme's automatic gas follows visibility too
        }
    }

    // Whether a race is running, from the game (polled: no callback from the game thread).
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            boolean racing;
            try {
                racing = GameBridge.nativeInRace();
            } catch (UnsatisfiedLinkError error) {
                racing = false;
            }
            if (racing != inRace) {
                inRace = racing;
                updateScheme();
            }
            postDelayed(this, 250);
        }
    };

    private boolean findController() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || device.isVirtual()) continue;
            int sources = device.getSources();
            if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                return true;
            }
        }
        return false;
    }

    @Override public void onInputDeviceAdded(int deviceId) { refreshController(); }
    @Override public void onInputDeviceRemoved(int deviceId) { refreshController(); }
    @Override public void onInputDeviceChanged(int deviceId) { refreshController(); }

    private void refreshController() {
        boolean connected = findController();
        if (connected != controllerConnected) {
            controllerConnected = connected;
            cancelInput();
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (inputManager != null) inputManager.registerInputDeviceListener(this, null);
        refreshController();
        post(poll);
    }

    @Override protected void onDetachedFromWindow() {
        if (inputManager != null) inputManager.unregisterInputDeviceListener(this);
        removeCallbacks(poll);
        cancelInput();
        super.onDetachedFromWindow();
    }

    // ── Editing ─────────────────────────────────────────────────────────────────────────────────

    void setSnapToGrid(boolean enabled) {
        snapToGrid = enabled;
        if (editing && enabled) snapAllButtons();
        invalidate();
    }

    void setSelectionChanged(Runnable listener) { selectionChanged = listener; }
    int getSelectedButton() { return selectedButton; }
    String getSelectedId() { return selectedButton < 0 ? null : id(selectedButton); }
    float getSelectedSize() { return selectedButton < 0 ? 1 : layout().getSize(selectedButton); }

    void resizeSelected(float size) {
        if (!editing || selectedButton < 0) return;
        RectF r = bounds()[selectedButton];
        layout().setPosition(selectedButton, r.centerX() / getWidth(), r.centerY() / getHeight());
        layout().setSize(selectedButton, size);
        arrangeButtons(getWidth(), getHeight());
        invalidate();
    }

    void setLayout(TouchLayout value) {
        layouts[scheme] = value.copy();
        arrangeButtons(getWidth(), getHeight());
        invalidate();
    }

    TouchLayout getLayout() { return layout().copy(); }

    void saveLayout() throws IOException { stores[scheme].save(layout()); }

    /** Edits the scheme the controller layout setting picks: racing when it is on. */
    void beginEditing() {
        cancelInput();
        scheme = racingSetting ? RACING : STANDARD;
        selectedButton = -1;
        originalLayout = layout().copy();
        editing = true;
        arrangeButtons(getWidth(), getHeight());
        invalidate();
    }

    void endEditing(boolean save) {
        if (!editing) return;
        editing = false;
        dragPointer = dragButton = -1;
        if (!save) setLayout(originalLayout);
        originalLayout = null;
        cancelInput();
        updateScheme();
    }

    void resetLayout() {
        setLayout(new TouchLayout(layout().IDS));
        if (selectionChanged != null) selectionChanged.run();
    }

    private void moveEditedButton(int index, float x, float y) {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        RectF r = bounds()[index];
        if (snapToGrid) {
            float step = TouchGrid.STEP * scale;
            x = TouchGrid.snap(x, r.width() / 2, getWidth() - r.width() / 2, step);
            y = TouchGrid.snap(y, r.height() / 2, getHeight() - r.height() / 2, step);
        }
        moveButton(index, x, y);
        layout().setPosition(index, r.centerX() / getWidth(), r.centerY() / getHeight());
    }

    private void snapAllButtons() {
        RectF[] bounds = bounds();
        for (int i = 0; i < bounds.length; i++) moveEditedButton(i, bounds[i].centerX(), bounds[i].centerY());
    }

    // ── Layout ──────────────────────────────────────────────────────────────────────────────────

    void cancelInput() {
        pointers.clear();
        held = 0;
        stickPointer = -1;
        stickX = stickY = 0;
        send();
        invalidate();
    }

    private void panel(String id, float x, float y, float width, float height) {
        int index = indexOf(id);
        if (index >= 0) bounds()[index].set(x, y, x + width, y + height);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        cancelInput();
        int current = scheme;
        for (int s = 0; s < 2; s++) {
            scheme = s;
            arrangeButtons(w, h);
        }
        scheme = current;
    }

    private void arrangeButtons(int w, int h) {
        if (w <= 0 || h <= 0) return;
        // Designed on 960x540; fits 16:9, 4:3 and wider screens.
        scale = Math.min(w / 960f, h / 540f);
        float s = scale;
        float edge = 18 * s;
        float bottom = h - edge;
        if (scheme == STANDARD) {
            // Left: the stick, Z above it. Right: accelerate, brake, boost, R above.
            panel("stick", edge, bottom - 230 * s, 230 * s, 230 * s);
            panel("drift-left", edge, bottom - 380 * s, 96 * s, 96 * s);
            panel("accelerate", w - edge - 120 * s, bottom - 120 * s, 120 * s, 120 * s);
            panel("brake", w - edge - 230 * s, bottom - 96 * s, 96 * s, 96 * s);
            panel("boost", w - edge - 110 * s, bottom - 235 * s, 100 * s, 100 * s);
            panel("drift-right", w - edge - 96 * s, bottom - 380 * s, 96 * s, 96 * s);
        } else {
            // Each side: its steering arrow, its drift above it. Boost under the right thumb,
            // air brake under the left one.
            panel("steer-left", edge, bottom - 160 * s, 150 * s, 160 * s);
            panel("steer-right", w - edge - 150 * s, bottom - 160 * s, 150 * s, 160 * s);
            panel("drift-left", edge + 25 * s, bottom - 290 * s, 100 * s, 100 * s);
            panel("drift-right", w - edge - 125 * s, bottom - 290 * s, 100 * s, 100 * s);
            panel("boost", w - edge - 275 * s, bottom - 115 * s, 105 * s, 105 * s);
            panel("brake", edge + 170 * s, bottom - 100 * s, 90 * s, 90 * s);
        }
        panel("start", w / 2f - 32 * s, edge, 64 * s, 64 * s);
        panel("camera", w - edge - 64 * s, edge, 64 * s, 64 * s);
        RectF[] bounds = bounds();
        for (int i = 0; i < bounds.length; i++) {
            RectF r = bounds[i];
            float size = layout().getSize(i);
            float width = Math.min(w, r.width() * size);
            float height = Math.min(h, r.height() * size);
            bounds[i].set(r.centerX() - width / 2, r.centerY() - height / 2,
                    r.centerX() + width / 2, r.centerY() + height / 2);
            moveButton(i, r.centerX(), r.centerY());
            if (layout().hasPosition(i)) moveButton(i, layout().getX(i) * w, layout().getY(i) * h);
        }
        if (editing && snapToGrid) snapAllButtons();
    }

    private void moveButton(int index, float centerX, float centerY) {
        RectF r = bounds()[index];
        float width = r.width(), height = r.height();
        float x = Math.max(0, Math.min(getWidth() - width, centerX - width / 2));
        float y = Math.max(0, Math.min(getHeight() - height, centerY - height / 2));
        r.set(x, y, x + width, y + height);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (!shown()) return;
        if (editing) {
            canvas.drawColor(0xBB000000);
            if (snapToGrid) drawGrid(canvas);
        }
        RectF[] bounds = bounds();
        for (int i = 0; i < bounds.length; i++) {
            boolean down = (held & (1 << i)) != 0 || (editing && selectedButton == i);
            artwork.draw(canvas, id(i), bounds[i], down, editing ? 255 : 170);
        }
        int stick = indexOf("stick");
        if (stick >= 0) {
            RectF base = bounds[stick];
            float travel = base.width() * 0.3f;
            artwork.drawKnob(canvas, base.centerX() + stickX * travel, base.centerY() - stickY * travel,
                    base.width() * 0.42f, editing || stickPointer >= 0 ? 255 : 190);
        }
    }

    private void drawGrid(Canvas canvas) {
        float step = TouchGrid.STEP * scale;
        if (step <= 0) return;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        paint.setColor(0x343E9CB8);
        for (float x = 0; x < getWidth(); x += step) canvas.drawLine(x, 0, x, getHeight(), paint);
        for (float y = 0; y < getHeight(); y += step) canvas.drawLine(0, y, getWidth(), y, paint);
    }

    // ── Input ───────────────────────────────────────────────────────────────────────────────────

    private int hit(float x, float y) {
        RectF[] bounds = bounds();
        for (int i = 0; i < bounds.length; i++) {
            if (bounds[i].contains(x, y)) return i;
        }
        return -1;
    }

    private static int n64Button(String id) {
        switch (id) {
            case "start": return 0x1000;
            case "accelerate": return BUTTON_A;
            case "brake": return 0x0004;       // C-Down: air brake
            case "boost": return 0x4000;       // B
            case "drift-left": return 0x2000;  // Z
            case "drift-right": return 0x0010; // R
            case "camera": return 0x0001;      // C-Right
            default: return 0;                 // stick and steering arrows move the stick
        }
    }

    /** The held buttons as N64 buttons and stick, to the game (port/gdx_android_bridge.cpp). */
    private void send() {
        int buttons = 0;
        float x = stickX, y = stickY;
        for (int i = 0; i < layout().IDS.length; i++) {
            if ((held & (1 << i)) == 0) continue;
            buttons |= n64Button(id(i));
            if (id(i).equals("steer-left")) x -= 1;
            if (id(i).equals("steer-right")) x += 1;
        }
        // The racing scheme accelerates by itself while it is on screen.
        if (scheme == RACING && !editing && shown()) buttons |= BUTTON_A;
        try {
            GameBridge.nativeSetTouch(buttons, Math.round(Math.max(-1, Math.min(1, x)) * STICK_RANGE),
                    Math.round(y * STICK_RANGE));
        } catch (UnsatisfiedLinkError ignored) {
            // Game library not loaded yet.
        }
    }

    private boolean editTouch(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            dragPointer = event.getPointerId(0);
            dragButton = hit(event.getX(), event.getY());
            selectedButton = dragButton;
            if (selectionChanged != null) selectionChanged.run();
            invalidate();
            if (dragButton >= 0) {
                dragOffsetX = event.getX() - bounds()[dragButton].centerX();
                dragOffsetY = event.getY() - bounds()[dragButton].centerY();
            }
        } else if (action == MotionEvent.ACTION_MOVE && dragButton >= 0) {
            int pointer = event.findPointerIndex(dragPointer);
            if (pointer >= 0) {
                moveEditedButton(dragButton, event.getX(pointer) - dragOffsetX, event.getY(pointer) - dragOffsetY);
                invalidate();
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || (action == MotionEvent.ACTION_POINTER_UP
                    && event.getPointerId(event.getActionIndex()) == dragPointer)) {
            dragPointer = dragButton = -1;
        }
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (editing) return editTouch(event);
        if (!shown()) return false;
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN && hit(event.getX(), event.getY()) < 0) return false;
        if (action == MotionEvent.ACTION_CANCEL) {
            cancelInput();
            return true;
        }
        int stick = indexOf("stick");
        for (int i = 0; i < event.getPointerCount(); i++) {
            int id = event.getPointerId(i);
            boolean lifted = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) && i == index;
            boolean landed = (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) && i == index;
            if (landed && stick >= 0 && stickPointer < 0 && hit(event.getX(i), event.getY(i)) == stick) stickPointer = id;
            if (id == stickPointer) {
                if (lifted) {
                    stickPointer = -1;
                    stickX = stickY = 0;
                } else {
                    // Distance from the base's centre, full at 30% of its width; y grows upwards.
                    RectF base = bounds()[stick];
                    float travel = base.width() * 0.3f;
                    float dx = (event.getX(i) - base.centerX()) / travel;
                    float dy = (base.centerY() - event.getY(i)) / travel;
                    float length = (float) Math.hypot(dx, dy);
                    if (length > 1) {
                        dx /= length;
                        dy /= length;
                    }
                    stickX = Math.abs(dx) < 0.12f ? 0 : dx; // small dead zone
                    stickY = Math.abs(dy) < 0.12f ? 0 : dy;
                }
                continue;
            }
            if (lifted) {
                pointers.delete(id);
            } else {
                // Sliding between buttons changes the held control at once (e.g. left to right).
                pointers.put(id, hit(event.getX(i), event.getY(i)));
            }
        }
        held = 0;
        for (int i = 0; i < pointers.size(); i++) {
            int button = pointers.valueAt(i);
            if (button >= 0 && button != stick) held |= 1 << button;
        }
        send();
        invalidate();
        return true;
    }
}
