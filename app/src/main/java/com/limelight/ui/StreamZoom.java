package com.limelight.ui;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Pinch to zoom into the stream, and move it around with two fingers.
 *
 * The stream view is scaled from its top left corner and moved so it always
 * covers the area it was laid out in, so zooming never shows anything but the
 * stream. It never goes below its laid out size.
 *
 * Something covering the bottom of the stream, such as the on-screen keyboard,
 * lets it be moved up by as much as it covers, zoomed or not. It is never
 * moved up on its own: that is left to the user.
 *
 * A two finger gesture is told apart from a scroll by how it starts: fingers
 * that move apart or together zoom, and fingers that move side by side move
 * the stream when there is room to, or scroll as before when there is not.
 * Once a gesture zooms or moves the stream, nothing of it reaches the host.
 *
 * Touches arrive in the coordinates of the view behind the stream, so every
 * conversion to the stream's own coordinates has to go through this class.
 */
public class StreamZoom {
    public static final float MAX_SCALE = 4.0f;

    private enum State {
        IDLE,
        UNDECIDED,
        ZOOMING,
        PANNING,
        NOT_ZOOMING,
    }

    private final View view;
    private final boolean zoomEnabled;
    private final float touchSlop;
    private int coveredBottom;

    private State state = State.IDLE;
    private float startSpan;
    private float startFocusX, startFocusY;
    private float startScale;
    private float startOriginX, startOriginY;

    /**
     * @param view The stream view.
     * @param zoomEnabled Whether pinching zooms. Without it the stream can
     *                    still be moved up past something covering it.
     */
    public StreamZoom(View view, boolean zoomEnabled) {
        this.view = view;
        this.zoomEnabled = zoomEnabled;
        this.touchSlop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
    }

    /**
     * Say how much of the bottom of the screen is covered.
     *
     * The stream can then be moved up by as much of it as lies over the
     * stream. It stays where it is until the user moves it, unless less is
     * covered than before, when it comes back down as far as it has to.
     *
     * @param pixels Height covered, from the bottom of the stream's parent.
     */
    public void setCoveredBottom(int pixels) {
        coveredBottom = Math.max(0, pixels);
        apply(view.getScaleX(), view.getTranslationX(), view.getTranslationY());
    }

    public float getScale() {
        return view.getScaleX();
    }

    /**
     * Convert a horizontal position in the coordinates of the view behind the
     * stream to one in the stream view's own, unscaled, coordinates.
     */
    public float toStreamX(float x) {
        return (x - view.getX()) / view.getScaleX();
    }

    /**
     * Convert a vertical position in the coordinates of the view behind the
     * stream to one in the stream view's own, unscaled, coordinates.
     */
    public float toStreamY(float y) {
        return (y - view.getY()) / view.getScaleY();
    }

    /**
     * Go back to the stream's laid out size and place.
     */
    public void reset() {
        state = State.IDLE;
        apply(1.0f, 0, 0);
    }

    private boolean canPan() {
        return view.getScaleX() > 1.0f || coveredStream() > 0;
    }

    // How much of the stream's laid out area the covered part lies over
    private float coveredStream() {
        View parent = (View) view.getParent();
        if (parent == null || coveredBottom == 0) {
            return 0;
        }
        return Math.max(0, view.getBottom() - (parent.getHeight() - coveredBottom));
    }

    /**
     * Look at a finger touch on the view behind the stream.
     *
     * @return True if it belongs to a zoom and must not reach the host.
     */
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                state = State.IDLE;
                return false;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (state == State.ZOOMING || state == State.PANNING) {
                    return true;
                }
                if (event.getPointerCount() == 2 && state == State.IDLE) {
                    state = State.UNDECIDED;
                    startSpan = span(event);
                    startFocusX = focusX(event);
                    startFocusY = focusY(event);
                    startScale = view.getScaleX();
                    startOriginX = view.getTranslationX();
                    startOriginY = view.getTranslationY();
                }
                else {
                    // Three fingers bring up the keyboard
                    state = State.NOT_ZOOMING;
                }
                return false;

            case MotionEvent.ACTION_MOVE:
                if (state == State.UNDECIDED && event.getPointerCount() >= 2) {
                    float spanChange = Math.abs(span(event) - startSpan);
                    float focusMove = (float) Math.hypot(focusX(event) - startFocusX, focusY(event) - startFocusY);
                    if (zoomEnabled && spanChange > 2 * touchSlop && spanChange > focusMove) {
                        state = State.ZOOMING;
                    }
                    else if (focusMove > 2 * touchSlop) {
                        state = canPan() ? State.PANNING : State.NOT_ZOOMING;
                    }
                }
                if (state == State.ZOOMING || state == State.PANNING) {
                    if (event.getPointerCount() >= 2) {
                        zoomTo(event, state == State.ZOOMING);
                    }
                    return true;
                }
                return false;

            case MotionEvent.ACTION_POINTER_UP:
                if (state == State.UNDECIDED) {
                    // A two finger tap, which is a right click
                    state = State.IDLE;
                }
                return isZooming();

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean zooming = isZooming();
                state = State.IDLE;
                return zooming;

            default:
                return isZooming();
        }
    }

    /**
     * Whether the gesture under way zooms or moves the stream.
     */
    public boolean isZooming() {
        return state == State.ZOOMING || state == State.PANNING;
    }

    private void zoomTo(MotionEvent event, boolean zoom) {
        float scale = startScale;
        if (zoom) {
            scale = startScale * span(event) / Math.max(startSpan, 1);
            scale = Math.max(1.0f, Math.min(MAX_SCALE, scale));
        }

        // The point of the stream that was under the fingers stays under them
        float contentX = (startFocusX - view.getLeft() - startOriginX) / startScale;
        float contentY = (startFocusY - view.getTop() - startOriginY) / startScale;
        float originX = focusX(event) - view.getLeft() - contentX * scale;
        float originY = focusY(event) - view.getTop() - contentY * scale;

        apply(scale, originX, originY);
    }

    private void apply(float scale, float originX, float originY) {
        // Never smaller than laid out, and never leaving a gap at an edge,
        // except at the bottom by as much as is covered there
        float width = view.getWidth();
        float height = view.getHeight();
        originX = Math.max(width - width * scale, Math.min(0, originX));
        originY = Math.max(height - height * scale - coveredStream(), Math.min(0, originY));

        view.setPivotX(0);
        view.setPivotY(0);
        view.setScaleX(scale);
        view.setScaleY(scale);
        view.setTranslationX(originX);
        view.setTranslationY(originY);
    }

    private static float span(MotionEvent event) {
        return (float) Math.hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1));
    }

    private static float focusX(MotionEvent event) {
        return (event.getX(0) + event.getX(1)) / 2;
    }

    private static float focusY(MotionEvent event) {
        return (event.getY(0) + event.getY(1)) / 2;
    }
}
