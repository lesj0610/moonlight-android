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
 *
 * Characters can instead type when the finger lifts: the key is shown above
 * the finger while it is down, and sliding to a neighbour before lifting types
 * that instead. Other keys always act as soon as they are touched.
 */
@SuppressLint("ViewConstructor")
class KeyboardView extends View {
    interface Listener {
        void onKeyDown(KeyboardLayout.Key key);
        void onKeyUp(KeyboardLayout.Key key);

        /**
         * Whether a touch near the edge {@code touched} shares with {@code neighbor}
         * goes to the neighbour, which what was typed so far makes likelier.
         */
        boolean preferNeighbor(KeyboardLayout.Key touched, KeyboardLayout.Key neighbor);

        /** Shows the key a finger will type on release, at {@code rect} in this view, or hides it for null. */
        void onPreview(KeyboardLayout.Key key, RectF rect);
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

    // Touches land below the key aimed at, the more so the lower the row (field
    // data from Android keyboards), so each row reads a touch this share higher.
    private static final float[] ROW_OFFSET = { 0.02f, 0.03f, 0.04f, 0.06f, 0.08f, 0.08f };

    // The share of a letter, at the edge it shares with another, where a touch may go
    // to that neighbour instead. The middle of every key is always that key.
    private static final float EDGE_SHARE = 0.25f;

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
    private final float hysteresis;

    private boolean commitOnRelease = true;
    // The character a finger is on but has not typed yet. A new touch types it first,
    // so there is never more than one.
    private int pendingPointer = -1;
    private KeyboardLayout.Key pendingKey;

    KeyboardView(Context context, Listener listener, Appearance appearance) {
        super(context);
        this.listener = listener;
        this.appearance = appearance;

        float density = context.getResources().getDisplayMetrics().density;
        gap = 2 * density;
        corner = 4 * density;
        hysteresis = 8 * density;

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

    /**
     * Characters type when the finger lifts rather than when it touches.
     */
    void setCommitOnRelease(boolean commitOnRelease) {
        this.commitOnRelease = commitOnRelease;
    }

    private KeyboardLayout.Key keyAt(float x, float y) {
        int rows = rects.length;
        if (getHeight() == 0) {
            return null;
        }
        float rowHeight = (float) getHeight() / rows;
        int aimed = Math.max(0, Math.min(rows - 1, (int) (y / rowHeight)));
        float corrected = y - rowHeight * ROW_OFFSET[Math.min(aimed, ROW_OFFSET.length - 1)];
        int row = Math.max(0, Math.min(rows - 1, (int) (corrected / rowHeight)));

        RectF[] rowRects = rects[row];
        for (int i = 0; i < rowRects.length; i++) {
            if (x < rowRects[i].right || i == rowRects.length - 1) {
                return withEdgeHint(row, i, x);
            }
        }
        return null;
    }

    private KeyboardLayout.Key withEdgeHint(int row, int i, float x) {
        KeyboardLayout.Key key = KeyboardLayout.ROWS[row][i];
        if (!key.letter) {
            return key;
        }
        RectF rect = rects[row][i];
        float into = (x - rect.left) / rect.width();
        int other = into < EDGE_SHARE ? i - 1 : into > 1 - EDGE_SHARE ? i + 1 : -1;
        if (other < 0 || other >= KeyboardLayout.ROWS[row].length) {
            return key;
        }
        KeyboardLayout.Key neighbor = KeyboardLayout.ROWS[row][other];
        return neighbor.letter && listener.preferNeighbor(key, neighbor) ? neighbor : key;
    }

    private RectF rectOf(KeyboardLayout.Key key) {
        for (int row = 0; row < rects.length; row++) {
            for (int i = 0; i < rects[row].length; i++) {
                if (KeyboardLayout.ROWS[row][i] == key) {
                    return rects[row][i];
                }
            }
        }
        return null;
    }

    private void commitPending() {
        if (pendingPointer < 0) {
            return;
        }
        KeyboardLayout.Key key = pendingKey;
        pendingPointer = -1;
        pendingKey = null;
        if (keysByPointer.indexOfValue(key) < 0) {
            pressed.remove(key);
        }
        listener.onPreview(null, null);
        listener.onKeyDown(key);
        listener.onKeyUp(key);
    }

    private void dropPending() {
        if (pendingPointer >= 0) {
            pendingPointer = -1;
            pendingKey = null;
            listener.onPreview(null, null);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int index = event.getActionIndex();
        int pointerId = event.getPointerId(index);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                // A character still under an earlier finger goes first, so keys type in the
                // order they were touched, and before this touch is read against it
                commitPending();
                KeyboardLayout.Key key = keyAt(event.getX(index), event.getY(index));
                if (key != null) {
                    pressed.add(key);
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    if (commitOnRelease && key.kind == KeyboardLayout.Kind.CHARACTER) {
                        pendingPointer = pointerId;
                        pendingKey = key;
                        listener.onPreview(key, rectOf(key));
                    }
                    else {
                        keysByPointer.put(pointerId, key);
                        listener.onKeyDown(key);
                    }
                }
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                int pointerIndex = pendingPointer < 0 ? -1 : event.findPointerIndex(pendingPointer);
                if (pointerIndex < 0) {
                    return true;
                }
                float x = event.getX(pointerIndex), y = event.getY(pointerIndex);
                // The finger has to be clear of its key before it changes keys, so it does not flicker along an edge
                RectF rect = rectOf(pendingKey);
                if (rect != null && x > rect.left - hysteresis && x < rect.right + hysteresis
                        && y > rect.top - hysteresis && y < rect.bottom + hysteresis) {
                    return true;
                }
                KeyboardLayout.Key key = keyAt(x, y);
                if (key != null && key != pendingKey && key.kind == KeyboardLayout.Kind.CHARACTER) {
                    pressed.remove(pendingKey);
                    pendingKey = key;
                    pressed.add(key);
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    listener.onPreview(key, rectOf(key));
                    invalidate();
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                if (pointerId == pendingPointer) {
                    commitPending();
                    invalidate();
                    return true;
                }
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
        // A character not typed yet stays untyped
        dropPending();
        while (keysByPointer.size() > 0) {
            KeyboardLayout.Key key = keysByPointer.valueAt(0);
            keysByPointer.removeAt(0);
            release(key);
        }
        pressed.clear();
        invalidate();
    }
}
