package com.limelight.binding.input.osk;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.HashSet;
import java.util.Set;

/**
 * Draws the on-screen keyboard and reports which keys the fingers press.
 *
 * It spans whatever width and height it is given. Every finger stays with the
 * key it first touched, so a modifier can be held with one finger while
 * another types.
 */
@SuppressLint("ViewConstructor")
class KeyboardView extends View {
    interface Listener {
        void onKeyDown(KeyboardLayout.Key key);
        void onKeyUp(KeyboardLayout.Key key);
    }

    interface Appearance {
        /** What a key says right now. */
        String getLabel(KeyboardLayout.Key key);

        /** 0 for a plain key, 1 for one set for the next key, 2 for one that stays on. */
        int getState(KeyboardLayout.Key key);
    }

    private static final int PANEL_COLOR = 0xD0101010;
    private static final int KEY_COLOR = 0xE0323232;
    private static final int KEY_PRESSED_COLOR = 0xE0686868;
    private static final int KEY_ONCE_COLOR = 0xE02F5F9F;
    private static final int KEY_LOCKED_COLOR = 0xE01E88E5;
    private static final int LABEL_COLOR = 0xFFF0F0F0;

    private final Listener listener;
    private final Appearance appearance;

    private final RectF[][] rects = new RectF[KeyboardLayout.ROWS.length][];
    private final SparseArray<KeyboardLayout.Key> keysByPointer = new SparseArray<>();
    private final Set<KeyboardLayout.Key> pressed = new HashSet<>();

    private final Paint keyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path iconPath = new Path();
    private final float gap;
    private final float corner;

    KeyboardView(Context context, Listener listener, Appearance appearance) {
        super(context);
        this.listener = listener;
        this.appearance = appearance;

        float density = context.getResources().getDisplayMetrics().density;
        gap = 2 * density;
        corner = 4 * density;

        labelPaint.setColor(LABEL_COLOR);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        for (int row = 0; row < rects.length; row++) {
            rects[row] = new RectF[KeyboardLayout.ROWS[row].length];
            for (int i = 0; i < rects[row].length; i++) {
                rects[row][i] = new RectF();
            }
        }
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        float unit = (float) width / KeyboardLayout.COLUMNS;
        float rowHeight = (float) height / KeyboardLayout.ROWS.length;

        for (int row = 0; row < KeyboardLayout.ROWS.length; row++) {
            float left = 0;
            for (int i = 0; i < KeyboardLayout.ROWS[row].length; i++) {
                float right = left + KeyboardLayout.ROWS[row][i].width * unit;
                rects[row][i].set(left, row * rowHeight, right, (row + 1) * rowHeight);
                left = right;
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(PANEL_COLOR);

        for (int row = 0; row < KeyboardLayout.ROWS.length; row++) {
            for (int i = 0; i < KeyboardLayout.ROWS[row].length; i++) {
                drawKey(canvas, KeyboardLayout.ROWS[row][i], rects[row][i]);
            }
        }
    }

    private void drawKey(Canvas canvas, KeyboardLayout.Key key, RectF rect) {
        float left = rect.left + gap, top = rect.top + gap, right = rect.right - gap, bottom = rect.bottom - gap;

        int state = appearance.getState(key);
        if (pressed.contains(key)) {
            keyPaint.setColor(KEY_PRESSED_COLOR);
        }
        else if (state == 2) {
            keyPaint.setColor(KEY_LOCKED_COLOR);
        }
        else if (state == 1) {
            keyPaint.setColor(KEY_ONCE_COLOR);
        }
        else {
            keyPaint.setColor(KEY_COLOR);
        }
        canvas.drawRoundRect(left, top, right, bottom, corner, corner, keyPaint);

        float centerX = (left + right) / 2, centerY = (top + bottom) / 2;
        float height = bottom - top, width = right - left;

        if (key.icon != KeyboardLayout.Icon.NONE) {
            drawIcon(canvas, key.icon, centerX, centerY, Math.min(width, height) * 0.22f);
            return;
        }

        String label = appearance.getLabel(key);
        if (label == null || label.isEmpty()) {
            return;
        }

        // As big as the key allows, and smaller where the label is long
        float textSize = height * 0.4f;
        labelPaint.setTextSize(textSize);
        float textWidth = labelPaint.measureText(label);
        if (textWidth > width * 0.85f) {
            labelPaint.setTextSize(textSize * width * 0.85f / textWidth);
        }
        canvas.drawText(label, centerX, centerY - (labelPaint.descent() + labelPaint.ascent()) / 2, labelPaint);
    }

    // Triangles drawn rather than typed, so they show whatever the fonts on the device
    private void drawIcon(Canvas canvas, KeyboardLayout.Icon icon, float x, float y, float size) {
        iconPath.reset();
        switch (icon) {
            case UP:
                iconPath.moveTo(x, y - size);
                iconPath.lineTo(x + size, y + size * 0.6f);
                iconPath.lineTo(x - size, y + size * 0.6f);
                break;
            case DOWN:
            case HIDE:
                iconPath.moveTo(x, y + size);
                iconPath.lineTo(x + size, y - size * 0.6f);
                iconPath.lineTo(x - size, y - size * 0.6f);
                break;
            case LEFT:
                iconPath.moveTo(x - size, y);
                iconPath.lineTo(x + size * 0.6f, y - size);
                iconPath.lineTo(x + size * 0.6f, y + size);
                break;
            case RIGHT:
                iconPath.moveTo(x + size, y);
                iconPath.lineTo(x - size * 0.6f, y - size);
                iconPath.lineTo(x - size * 0.6f, y + size);
                break;
            default:
                return;
        }
        iconPath.close();
        canvas.drawPath(iconPath, labelPaint);

        if (icon == KeyboardLayout.Icon.HIDE) {
            // A bar under the arrow, so it reads as putting the keyboard away
            canvas.drawRect(x - size, y + size * 1.3f, x + size, y + size * 1.6f, labelPaint);
        }
    }

    private KeyboardLayout.Key keyAt(float x, float y) {
        for (int row = 0; row < rects.length; row++) {
            for (int i = 0; i < rects[row].length; i++) {
                if (rects[row][i].contains(x, y)) {
                    return KeyboardLayout.ROWS[row][i];
                }
            }
        }
        return null;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int index = event.getActionIndex();
        int pointerId = event.getPointerId(index);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                KeyboardLayout.Key key = keyAt(event.getX(index), event.getY(index));
                if (key != null) {
                    keysByPointer.put(pointerId, key);
                    pressed.add(key);
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    listener.onKeyDown(key);
                    invalidate();
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                KeyboardLayout.Key key = keysByPointer.get(pointerId);
                if (key != null) {
                    keysByPointer.remove(pointerId);
                    release(key);
                }
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                return true;

            default:
                return true;
        }
    }

    private void release(KeyboardLayout.Key key) {
        // Another finger may still be on the same key
        if (keysByPointer.indexOfValue(key) < 0) {
            pressed.remove(key);
        }
        listener.onKeyUp(key);
        invalidate();
    }

    /**
     * Let go of every key a finger is on, as if the fingers had been lifted.
     */
    void releaseAll() {
        while (keysByPointer.size() > 0) {
            KeyboardLayout.Key key = keysByPointer.valueAt(0);
            keysByPointer.removeAt(0);
            release(key);
        }
        pressed.clear();
        invalidate();
    }
}
