package com.limelight.binding.input.osk;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;

/**
 * A full keyboard drawn over the stream, which folds away into a small button.
 *
 * The keyboard spans the width of the screen, and its height is set by
 * dragging the bar along its top. The button can be dragged anywhere. Both
 * are remembered.
 *
 * Shift, Ctrl, Alt and Win apply to the next key when tapped and stay on when
 * tapped twice, as on the Windows on-screen keyboard. Win tapped twice is
 * pressed on its own instead, which opens the Start menu. A modifier held
 * down with one finger applies to whatever the others type.
 */
public class OnScreenKeyboard implements KeyboardView.Listener, KeyboardView.Appearance {
    /**
     * Sends a key to the host.
     */
    public interface KeySender {
        /**
         * @param keyCode Key code as the host takes it, with the 0x8000 bit.
         * @param down Pressed or released.
         * @param modifiers Modifier flags in effect.
         * @param flags Keyboard event flags.
         */
        void sendKey(short keyCode, boolean down, byte modifiers, byte flags);
    }

    /**
     * Told how much of the bottom of the screen the keyboard covers.
     */
    public interface CoverListener {
        void onCoveredBottomChanged(int pixels);
    }

    private static final String PREFS = "OSK";
    private static final String PREF_HEIGHT = "height";
    private static final String PREF_BUTTON_X = "button_x";
    private static final String PREF_BUTTON_Y = "button_y";

    private static final float DEFAULT_HEIGHT = 0.4f;
    private static final float MIN_HEIGHT = 0.2f;
    private static final float MAX_HEIGHT = 0.75f;
    private static final int BUTTON_SIZE_DP = 52;
    private static final int HANDLE_HEIGHT_DP = 20;

    private static final int VK_CAPITAL = 0x14;

    // A 두벌식 letter typed in Korean is a consonant on the left of the keyboard and a vowel on the right
    private static final String VOWEL_KEYS = "YUIOPHJKLBNM";
    private static final int NO_JAMO = 0, CONSONANT = 1, VOWEL = 2;

    private enum Latch { OFF, ONCE, LOCKED }

    private static final byte[] MODIFIERS = {
            KeyboardLayout.SHIFT, KeyboardLayout.CTRL, KeyboardLayout.ALT, KeyboardLayout.META,
    };

    private final FrameLayout parent;
    private final KeySender sender;
    private final CoverListener coverListener;
    private final SharedPreferences prefs;
    private final float density;

    private final ImageView button;
    private final LinearLayout panel;
    private final KeyboardView keys;
    private final boolean hangulHint;
    private TextView preview;

    // Per modifier, in the order of MODIFIERS
    private final Latch[] latches = { Latch.OFF, Latch.OFF, Latch.OFF, Latch.OFF };
    private final int[] heldFingers = new int[MODIFIERS.length];
    private final boolean[] usedWhileHeld = new boolean[MODIFIERS.length];
    private final int[] lastVk = { 0xA0, 0xA2, 0xA4, 0x5B };
    private final int[] downOnHost = new int[MODIFIERS.length];

    private int languageIndex = 0;
    private boolean capsOn = false;
    private boolean expanded = false;
    private boolean hidden = false;
    private boolean releasing = false;

    // Holding the language key switches only the labels, for when they no
    // longer match the host (it was already in another language, say).
    private final Runnable languageHold = this::switchLabelsOnly;
    private boolean labelsSwitched = false;

    // What the Korean typed so far makes the next letter, for touches between a consonant and a vowel
    private int lastJamo = NO_JAMO;
    private int expectedJamo = NO_JAMO;
    private int lastLetterVk = 0;

    @SuppressLint("ClickableViewAccessibility")
    public OnScreenKeyboard(Context context, FrameLayout parent, KeySender sender, CoverListener coverListener,
                            boolean commitOnRelease, boolean hangulHint) {
        this.parent = parent;
        this.sender = sender;
        this.coverListener = coverListener;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.density = context.getResources().getDisplayMetrics().density;
        // The hint moves keys a finger is on, so it needs the key shown before it types
        this.hangulHint = hangulHint && commitOnRelease;

        keys = new KeyboardView(context, this, this);
        keys.setCommitOnRelease(commitOnRelease);

        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClickable(true);
        panel.addView(new HandleView(context), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (HANDLE_HEIGHT_DP * density)));
        panel.addView(keys, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        panel.setVisibility(View.GONE);
        parent.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        button = new ImageView(context);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(0xB0202020);
        background.setStroke((int) density, 0x80FFFFFF);
        button.setBackground(background);
        button.setImageResource(R.drawable.ic_osk_keyboard);
        int padding = (int) (12 * density);
        button.setPadding(padding, padding, padding, padding);
        button.setContentDescription(context.getString(R.string.osk_button_description));
        button.setOnTouchListener(new ButtonDragger(context));
        int size = (int) (BUTTON_SIZE_DP * density);
        parent.addView(button, new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.START));

        // The parent has no size until it is laid out, and a new one on rotation
        parent.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                v.post(this::layOut);
            }
        });
        parent.post(this::layOut);
    }

    /**
     * Hide everything, as in picture-in-picture.
     */
    public void hide() {
        hidden = true;
        releaseAll();
        button.setVisibility(View.GONE);
        panel.setVisibility(View.GONE);
        coverListener.onCoveredBottomChanged(0);
    }

    /**
     * Show whatever was showing before hide().
     */
    public void show() {
        hidden = false;
        button.setVisibility(expanded ? View.GONE : View.VISIBLE);
        panel.setVisibility(expanded ? View.VISIBLE : View.GONE);
        coverListener.onCoveredBottomChanged(expanded ? panelHeight() : 0);
    }

    /**
     * Release every key and modifier still pressed on the host.
     */
    public void releaseAll() {
        // Lifting every finger at once is not the user tapping anything
        releasing = true;
        keys.releaseAll();
        releasing = false;
        forgetJamo();
        for (int i = 0; i < MODIFIERS.length; i++) {
            latches[i] = Latch.OFF;
            heldFingers[i] = 0;
            releaseModifier(i);
        }
        keys.invalidate();
    }

    private void expand() {
        expanded = true;
        show();
    }

    private void collapse() {
        releaseAll();
        expanded = false;
        show();
    }

    private void layOut() {
        if (parent.getWidth() == 0 || parent.getHeight() == 0) {
            return;
        }

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) panel.getLayoutParams();
        params.height = panelHeight();
        panel.setLayoutParams(params);

        // Remembered as a share of the screen, so it lands in the same place after rotating
        int size = button.getLayoutParams().width;
        float x = prefs.getFloat(PREF_BUTTON_X, 1.0f);
        float y = prefs.getFloat(PREF_BUTTON_Y, 0.5f);
        placeButton(x * (parent.getWidth() - size), y * (parent.getHeight() - size));

        if (expanded && !hidden) {
            coverListener.onCoveredBottomChanged(panelHeight());
        }
    }

    private int panelHeight() {
        float share = prefs.getFloat(PREF_HEIGHT, DEFAULT_HEIGHT);
        share = Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, share));
        return (int) (share * parent.getHeight());
    }

    private void placeButton(float x, float y) {
        int size = button.getLayoutParams().width;
        button.setX(Math.max(0, Math.min(parent.getWidth() - size, x)));
        button.setY(Math.max(0, Math.min(parent.getHeight() - size, y)));
    }

    private int modifierIndex(byte flag) {
        for (int i = 0; i < MODIFIERS.length; i++) {
            if (MODIFIERS[i] == flag) {
                return i;
            }
        }
        return -1;
    }

    private boolean isActive(int index) {
        return latches[index] != Latch.OFF || heldFingers[index] > 0;
    }

    private byte activeModifiers() {
        byte flags = 0;
        for (int i = 0; i < MODIFIERS.length; i++) {
            if (isActive(i)) {
                flags |= MODIFIERS[i];
            }
        }
        return flags;
    }

    private static short hostCode(int vk) {
        return (short) (0x8000 | vk);
    }

    private void releaseModifier(int index) {
        if (downOnHost[index] != 0) {
            int vk = downOnHost[index];
            downOnHost[index] = 0;
            sender.sendKey(hostCode(vk), false, activeModifiers(), (byte) 0);
        }
    }

    private void tap(int vk, byte modifiers) {
        sender.sendKey(hostCode(vk), true, modifiers, (byte) 0);
        sender.sendKey(hostCode(vk), false, modifiers, (byte) 0);
    }

    @Override
    public void onKeyDown(KeyboardLayout.Key key) {
        switch (key.kind) {
            case MODIFIER: {
                int index = modifierIndex(key.modifierFlag);
                heldFingers[index]++;
                usedWhileHeld[index] = false;
                lastVk[index] = key.vk;
                break;
            }

            case CHARACTER:
            case FUNCTION: {
                // The host gets each modifier in effect as a key of its own first
                byte modifiers = activeModifiers();
                for (int i = 0; i < MODIFIERS.length; i++) {
                    if (isActive(i)) {
                        if (downOnHost[i] == 0) {
                            downOnHost[i] = lastVk[i];
                            sender.sendKey(hostCode(lastVk[i]), true, modifiers, (byte) 0);
                        }
                        if (heldFingers[i] > 0) {
                            usedWhileHeld[i] = true;
                        }
                    }
                }
                sender.sendKey(hostCode(key.vk), true, modifiers, (byte) 0);
                if (key.kind == KeyboardLayout.Kind.CHARACTER) {
                    trackJamo(key);
                }
                else {
                    forgetJamo();
                }
                break;
            }

            case CAPS_LOCK:
                forgetJamo();
                capsOn = !capsOn;
                sender.sendKey(hostCode(VK_CAPITAL), true, activeModifiers(), (byte) 0);
                break;

            case LANGUAGE:
                labelsSwitched = false;
                keys.removeCallbacks(languageHold);
                keys.postDelayed(languageHold, ViewConfiguration.getLongPressTimeout());
                break;

            default:
                break;
        }
    }

    private void switchLabelsOnly() {
        labelsSwitched = true;
        forgetJamo();
        languageIndex = (languageIndex + 1) % KeyboardLanguage.ALL.size();
        keys.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        keys.invalidate();
    }

    @Override
    public void onKeyUp(KeyboardLayout.Key key) {
        switch (key.kind) {
            case MODIFIER: {
                int index = modifierIndex(key.modifierFlag);
                heldFingers[index] = Math.max(0, heldFingers[index] - 1);
                if (heldFingers[index] > 0) {
                    break;
                }

                if (!usedWhileHeld[index] && !releasing) {
                    // A tap: on for the next key, then on for good, then off.
                    // Win tapped twice is pressed on its own instead.
                    switch (latches[index]) {
                        case OFF:
                            latches[index] = Latch.ONCE;
                            break;
                        case ONCE:
                            if (MODIFIERS[index] == KeyboardLayout.META) {
                                latches[index] = Latch.OFF;
                                if (downOnHost[index] == 0) {
                                    tap(lastVk[index], activeModifiers());
                                }
                            }
                            else {
                                latches[index] = Latch.LOCKED;
                            }
                            break;
                        case LOCKED:
                            latches[index] = Latch.OFF;
                            break;
                    }
                }
                if (!isActive(index)) {
                    releaseModifier(index);
                }
                break;
            }

            case CHARACTER:
            case FUNCTION: {
                sender.sendKey(hostCode(key.vk), false, activeModifiers(), (byte) 0);

                // Modifiers set for one key are done with now
                for (int i = 0; i < MODIFIERS.length; i++) {
                    if (latches[i] == Latch.ONCE) {
                        latches[i] = Latch.OFF;
                    }
                }
                for (int i = 0; i < MODIFIERS.length; i++) {
                    if (!isActive(i)) {
                        releaseModifier(i);
                    }
                }
                break;
            }

            case CAPS_LOCK:
                sender.sendKey(hostCode(VK_CAPITAL), false, activeModifiers(), (byte) 0);
                break;

            case LANGUAGE: {
                keys.removeCallbacks(languageHold);
                if (releasing || labelsSwitched) {
                    break;
                }
                KeyboardLanguage.KeyTapper tapper = (keyCode, flags) -> {
                    sender.sendKey(keyCode, true, (byte) 0, flags);
                    sender.sendKey(keyCode, false, (byte) 0, flags);
                };
                forgetJamo();
                KeyboardLanguage.ALL.get(languageIndex).leave(tapper);
                languageIndex = (languageIndex + 1) % KeyboardLanguage.ALL.size();
                KeyboardLanguage.ALL.get(languageIndex).enter(tapper);
                break;
            }

            case HIDE:
                if (!releasing) {
                    collapse();
                }
                return;
        }
        keys.invalidate();
    }

    private boolean korean() {
        return KeyboardLanguage.ALL.get(languageIndex) instanceof KeyboardLanguage.Korean;
    }

    private boolean beyondShift() {
        return isActive(modifierIndex(KeyboardLayout.CTRL)) || isActive(modifierIndex(KeyboardLayout.ALT))
                || isActive(modifierIndex(KeyboardLayout.META));
    }

    private static boolean isVowelKey(int vk) {
        return VOWEL_KEYS.indexOf((char) vk) >= 0;
    }

    private void trackJamo(KeyboardLayout.Key key) {
        if (!key.letter || beyondShift() || !korean()) {
            forgetJamo();
            return;
        }
        int jamo = isVowelKey(key.vk) ? VOWEL : CONSONANT;
        // ㅛ, ㅗ and ㅠ never follow a vowel, a vowel follows a consonant that opens a
        // syllable, and after a final consonant either may come
        expectedJamo = jamo == VOWEL ? CONSONANT : lastJamo == VOWEL ? NO_JAMO : VOWEL;
        lastJamo = jamo;
        lastLetterVk = key.vk;
    }

    private void forgetJamo() {
        lastJamo = NO_JAMO;
        expectedJamo = NO_JAMO;
        lastLetterVk = 0;
    }

    @Override
    public boolean preferNeighbor(KeyboardLayout.Key touched, KeyboardLayout.Key neighbor) {
        // A key typed again, as in ㅠㅠ or ㅎㅎ, is meant
        if (!hangulHint || expectedJamo == NO_JAMO || touched.vk == lastLetterVk || beyondShift() || !korean()) {
            return false;
        }
        boolean neighborVowel = isVowelKey(neighbor.vk);
        return neighborVowel != isVowelKey(touched.vk) && (neighborVowel ? VOWEL : CONSONANT) == expectedJamo;
    }

    @Override
    public void onPreview(KeyboardLayout.Key key, RectF rect) {
        if (key == null || rect == null) {
            if (preview != null) {
                preview.setVisibility(View.GONE);
            }
            return;
        }
        if (preview == null) {
            preview = new TextView(parent.getContext());
            preview.setGravity(Gravity.CENTER);
            preview.setTextColor(0xFFFFFFFF);
            GradientDrawable background = new GradientDrawable();
            background.setColor(0xF0505050);
            background.setCornerRadius(6 * density);
            background.setStroke((int) density, 0x80FFFFFF);
            preview.setBackground(background);
            parent.addView(preview, new FrameLayout.LayoutParams(0, 0, Gravity.TOP | Gravity.START));
        }

        // Above the key, where the finger does not cover it
        int width = (int) Math.max(rect.width() * 1.5f, 40 * density);
        int height = (int) Math.max(rect.height() * 1.4f, 48 * density);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) preview.getLayoutParams();
        if (params.width != width || params.height != height) {
            params.width = width;
            params.height = height;
            preview.setLayoutParams(params);
        }
        int[] at = new int[2];
        int[] origin = new int[2];
        keys.getLocationInWindow(at);
        parent.getLocationInWindow(origin);
        float x = at[0] - origin[0] + rect.centerX() - width / 2f;
        float y = at[1] - origin[1] + rect.top - height - 4 * density;
        preview.setX(Math.max(0, Math.min(x, parent.getWidth() - width)));
        preview.setY(Math.max(0, y));
        preview.setTextSize(TypedValue.COMPLEX_UNIT_PX, height * 0.45f);
        preview.setText(getLabel(key));
        preview.setVisibility(View.VISIBLE);
    }

    @Override
    public String getLabel(KeyboardLayout.Key key) {
        KeyboardLanguage language = KeyboardLanguage.ALL.get(languageIndex);
        switch (key.kind) {
            case CHARACTER: {
                boolean shifted = isActive(modifierIndex(KeyboardLayout.SHIFT));
                String label = language.getLabel(key.vk, shifted);
                if (label != null) {
                    return label;
                }
                if (key.letter) {
                    shifted ^= capsOn;
                }
                return shifted ? key.shiftedLabel : key.label;
            }

            case LANGUAGE:
                return language.getShortName();

            default:
                return key.label;
        }
    }

    @Override
    public int getState(KeyboardLayout.Key key) {
        switch (key.kind) {
            case MODIFIER: {
                Latch latch = latches[modifierIndex(key.modifierFlag)];
                return latch == Latch.LOCKED ? 2 : latch == Latch.ONCE ? 1 : 0;
            }
            case CAPS_LOCK:
                return capsOn ? 2 : 0;
            default:
                return 0;
        }
    }

    /**
     * The bar along the top of the keyboard, dragged to change its height.
     */
    @SuppressLint("ViewConstructor")
    private class HandleView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float startRawY;
        private int startHeight;

        HandleView(Context context) {
            super(context);
            paint.setColor(0xC0FFFFFF);
            setBackgroundColor(0xD0101010);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float width = 40 * density, height = 4 * density;
            float x = getWidth() / 2f, y = getHeight() / 2f;
            canvas.drawRoundRect(x - width / 2, y - height / 2, x + width / 2, y + height / 2, height, height, paint);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startRawY = event.getRawY();
                    startHeight = panel.getHeight();
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    int height = (int) (startHeight + startRawY - event.getRawY());
                    height = Math.max((int) (MIN_HEIGHT * parent.getHeight()), Math.min((int) (MAX_HEIGHT * parent.getHeight()), height));
                    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) panel.getLayoutParams();
                    params.height = height;
                    panel.setLayoutParams(params);
                    coverListener.onCoveredBottomChanged(height);
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    prefs.edit().putFloat(PREF_HEIGHT, (float) panel.getLayoutParams().height / parent.getHeight()).apply();
                    return true;

                default:
                    return true;
            }
        }
    }

    /**
     * Drags the button around, and opens the keyboard when it is tapped instead.
     */
    private class ButtonDragger implements View.OnTouchListener {
        private final float touchSlop;
        private float startRawX, startRawY, startX, startY;
        private boolean dragging;

        ButtonDragger(Context context) {
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startRawX = event.getRawX();
                    startRawY = event.getRawY();
                    startX = view.getX();
                    startY = view.getY();
                    dragging = false;
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - startRawX, dy = event.getRawY() - startRawY;
                    if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                        dragging = true;
                    }
                    if (dragging) {
                        placeButton(startX + dx, startY + dy);
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        int size = view.getWidth();
                        prefs.edit()
                                .putFloat(PREF_BUTTON_X, view.getX() / Math.max(1, parent.getWidth() - size))
                                .putFloat(PREF_BUTTON_Y, view.getY() / Math.max(1, parent.getHeight() - size))
                                .apply();
                    }
                    else {
                        expand();
                    }
                    return true;

                default:
                    return true;
            }
        }
    }
}
