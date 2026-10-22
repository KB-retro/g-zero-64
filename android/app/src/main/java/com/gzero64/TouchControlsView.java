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

/**
 * On-screen N64 controller for port 1 (adapted from NFSU-X's race controls). Buttons press N64
 * buttons; the four arrows push the stick. Empty screen space belongs to SDL. Shown according to
 * the quick menu's mode: automatic (hidden while a controller is connected), always or never.
 */
final class TouchControlsView extends View implements InputManager.InputDeviceListener {
    static final int MODE_AUTO = 0, MODE_ALWAYS = 1, MODE_NEVER = 2;

    // N64 buttons in TouchLayout.IDS order; 0 for the stick arrows.
    private static final int[] N64_BUTTONS = {
        0x1000 /* Start */, 0x8000 /* A */, 0x0004 /* C-Down: air brake */, 0x4000 /* B: boost */,
        0, 0, 0, 0, 0x2000 /* Z */, 0x0010 /* R */, 0x0001 /* C-Right: camera */
    };
    private static final int LEFT = 4, RIGHT = 5, UP = 6, DOWN = 7;
    private static final int STICK = 80; // full N64 deflection

    private final RectF[] bounds = new RectF[TouchLayout.IDS.length];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TouchControlArtwork artwork;
    private final SparseIntArray pointers = new SparseIntArray();
    private final InputManager inputManager;
    private TouchLayout layout = new TouchLayout();
    private TouchLayout originalLayout;
    private boolean editing;
    private boolean snapToGrid;
    private int mode;
    private boolean controllerConnected;
    private int held;
    private float scale;
    private int dragPointer = -1;
    private int dragButton = -1;
    private int selectedButton = -1;
    private float dragOffsetX, dragOffsetY;
    private Runnable selectionChanged;

    TouchControlsView(Context context) {
        super(context);
        artwork = new TouchControlArtwork(context.getResources());
        inputManager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        for (int i = 0; i < bounds.length; i++) bounds[i] = new RectF();
        mode = context.getSharedPreferences("touch", Context.MODE_PRIVATE).getInt("mode", MODE_AUTO);
        controllerConnected = findController();
    }

    // ── Visibility ──────────────────────────────────────────────────────────────────────────────

    int getMode() { return mode; }

    void setMode(int value) {
        mode = value;
        getContext().getSharedPreferences("touch", Context.MODE_PRIVATE).edit().putInt("mode", value).apply();
        cancelInput();
    }

    private boolean shown() {
        return editing || mode == MODE_ALWAYS || (mode == MODE_AUTO && !controllerConnected);
    }

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
    }

    @Override protected void onDetachedFromWindow() {
        if (inputManager != null) inputManager.unregisterInputDeviceListener(this);
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
    float getSelectedSize() { return selectedButton < 0 ? 1 : layout.getSize(selectedButton); }

    void resizeSelected(float size) {
        if (!editing || selectedButton < 0) return;
        RectF r = bounds[selectedButton];
        layout.setPosition(selectedButton, r.centerX() / getWidth(), r.centerY() / getHeight());
        layout.setSize(selectedButton, size);
        arrangeButtons(getWidth(), getHeight());
        invalidate();
    }

    void setLayout(TouchLayout value) {
        layout = value.copy();
        arrangeButtons(getWidth(), getHeight());
        invalidate();
    }

    TouchLayout getLayout() { return layout.copy(); }

    void beginEditing() {
        cancelInput();
        selectedButton = -1;
        originalLayout = layout.copy();
        editing = true;
        invalidate();
    }

    void endEditing(boolean save) {
        if (!editing) return;
        editing = false;
        dragPointer = dragButton = -1;
        if (!save) setLayout(originalLayout);
        originalLayout = null;
        cancelInput();
    }

    void resetLayout() {
        setLayout(new TouchLayout());
        if (selectionChanged != null) selectionChanged.run();
    }

    private void moveEditedButton(int index, float x, float y) {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        RectF r = bounds[index];
        if (snapToGrid) {
            float step = TouchGrid.STEP * scale;
            x = TouchGrid.snap(x, r.width() / 2, getWidth() - r.width() / 2, step);
            y = TouchGrid.snap(y, r.height() / 2, getHeight() - r.height() / 2, step);
        }
        moveButton(index, x, y);
        layout.setPosition(index, r.centerX() / getWidth(), r.centerY() / getHeight());
    }

    private void snapAllButtons() {
        for (int i = 0; i < bounds.length; i++) {
            RectF r = bounds[i];
            moveEditedButton(i, r.centerX(), r.centerY());
        }
    }

    // ── Layout ──────────────────────────────────────────────────────────────────────────────────

    void cancelInput() {
        pointers.clear();
        held = 0;
        send();
        invalidate();
    }

    private void panel(int index, float x, float y, float width, float height) {
        bounds[index].set(x, y, x + width, y + height);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        cancelInput();
        arrangeButtons(w, h);
    }

    private void arrangeButtons(int w, int h) {
        if (w <= 0 || h <= 0) return;
        // Designed on 960x540; fits 16:9, 4:3 and wider screens.
        scale = Math.min(w / 960f, h / 540f);
        float s = scale;
        float edge = 18 * s;
        float bottom = h - edge;
        // Left: the stick as a cross, Z above it. Right: accelerate, brake, boost, R above.
        float cx = edge + 141 * s, cy = bottom - 141 * s, arrow = 90 * s;
        panel(LEFT, cx - 135 * s, cy - arrow / 2, arrow, arrow);
        panel(RIGHT, cx + 45 * s, cy - arrow / 2, arrow, arrow);
        panel(UP, cx - arrow / 2, cy - 135 * s, arrow, arrow);
        panel(DOWN, cx - arrow / 2, cy + 45 * s, arrow, arrow);
        panel(8, edge, bottom - 380 * s, 96 * s, 96 * s);
        panel(1, w - edge - 120 * s, bottom - 120 * s, 120 * s, 120 * s);
        panel(2, w - edge - 230 * s, bottom - 96 * s, 96 * s, 96 * s);
        panel(3, w - edge - 110 * s, bottom - 235 * s, 100 * s, 100 * s);
        panel(9, w - edge - 96 * s, bottom - 380 * s, 96 * s, 96 * s);
        panel(0, w / 2f - 32 * s, edge, 64 * s, 64 * s);
        panel(10, w - edge - 64 * s, edge, 64 * s, 64 * s);
        for (int i = 0; i < bounds.length; i++) {
            RectF r = bounds[i];
            float size = layout.getSize(i);
            float width = Math.min(w, r.width() * size);
            float height = Math.min(h, r.height() * size);
            panel(i, r.centerX() - width / 2, r.centerY() - height / 2, width, height);
            moveButton(i, r.centerX(), r.centerY());
            if (layout.hasPosition(i)) moveButton(i, layout.getX(i) * w, layout.getY(i) * h);
        }
        if (editing && snapToGrid) snapAllButtons();
    }

    private void moveButton(int index, float centerX, float centerY) {
        RectF r = bounds[index];
        float width = r.width(), height = r.height();
        float x = Math.max(0, Math.min(getWidth() - width, centerX - width / 2));
        float y = Math.max(0, Math.min(getHeight() - height, centerY - height / 2));
        panel(index, x, y, width, height);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (!shown()) return;
        if (editing) {
            canvas.drawColor(0xBB000000);
            if (snapToGrid) drawGrid(canvas);
        }
        for (int i = 0; i < bounds.length; i++) {
            boolean down = (held & (1 << i)) != 0 || (editing && selectedButton == i);
            artwork.draw(canvas, i, bounds[i], down, editing ? 255 : 170);
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
        for (int i = 0; i < bounds.length; i++) {
            if (bounds[i].contains(x, y)) return i;
        }
        return -1;
    }

    /** The held buttons as N64 buttons and stick, to the game (port/gdx_android_bridge.cpp). */
    private void send() {
        int buttons = 0, stickX = 0, stickY = 0;
        for (int i = 0; i < N64_BUTTONS.length; i++) {
            if ((held & (1 << i)) != 0) buttons |= N64_BUTTONS[i];
        }
        if ((held & (1 << LEFT)) != 0) stickX -= STICK;
        if ((held & (1 << RIGHT)) != 0) stickX += STICK;
        if ((held & (1 << UP)) != 0) stickY += STICK;
        if ((held & (1 << DOWN)) != 0) stickY -= STICK;
        try {
            GameBridge.nativeSetTouch(buttons, stickX, stickY);
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
                dragOffsetX = event.getX() - bounds[dragButton].centerX();
                dragOffsetY = event.getY() - bounds[dragButton].centerY();
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
        for (int i = 0; i < event.getPointerCount(); i++) {
            int id = event.getPointerId(i);
            if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) && i == index) {
                pointers.delete(id);
            } else {
                // Sliding between buttons changes the held control at once (e.g. left to right).
                pointers.put(id, hit(event.getX(i), event.getY(i)));
            }
        }
        held = 0;
        for (int i = 0; i < pointers.size(); i++) {
            int button = pointers.valueAt(i);
            if (button >= 0) held |= 1 << button;
        }
        send();
        invalidate();
        return true;
    }
}
