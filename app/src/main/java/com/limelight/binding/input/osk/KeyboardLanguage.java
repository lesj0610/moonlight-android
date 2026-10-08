package com.limelight.binding.input.osk;

import android.util.SparseArray;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A language the on-screen keyboard can show.
 *
 * The keyboard always sends the same keys, the ones a US layout has, and the
 * host's input method turns them into text. A language only changes what the
 * keys are labelled with, and what has to be sent to the host to switch its
 * input method over when the language is picked.
 *
 * To add a language, subclass this and add it to {@link #ALL}.
 */
public abstract class KeyboardLanguage {
    /**
     * Sends key presses to the host.
     */
    public interface KeyTapper {
        /**
         * Press and release a key on the host.
         *
         * @param keyCode Key code as the host takes it, with the 0x8000 bit.
         * @param flags Keyboard event flags.
         */
        void tap(short keyCode, byte flags);
    }

    /**
     * Name shown on the key that switches languages.
     */
    public abstract String getShortName();

    /**
     * Label for a key that types a character, or null to use the US label.
     *
     * @param vk Windows virtual key code of the key.
     * @param shifted Whether shift is in effect.
     */
    public String getLabel(int vk, boolean shifted) {
        return null;
    }

    /**
     * Switch the host's input method to this language.
     */
    public void enter(KeyTapper tapper) {
    }

    /**
     * Switch the host's input method back from this language.
     */
    public void leave(KeyTapper tapper) {
    }

    /**
     * The US layout, which is what the keys send.
     */
    public static final class English extends KeyboardLanguage {
        @Override
        public String getShortName() {
            return "EN";
        }
    }

    /**
     * Korean, with the Dubeolsik layout of the Windows Korean input method.
     *
     * The host switches between Korean and Latin input with the Hangul key,
     * so the host has to be using the Korean input method for this to type
     * Hangul.
     */
    public static final class Korean extends KeyboardLanguage {
        // VK_HANGUL, sent with the flag that tells the host it is the Hangul
        // key rather than VK_KANA, which has the same code
        private static final short HANGUL_KEY = (short) 0x8015;
        private static final byte HANGUL_FLAGS = 0x01 | 0x02;

        private static final SparseArray<String[]> JAMO = new SparseArray<>();
        static {
            String[][] keys = {
                    {"Q", "ㅂ", "ㅃ"}, {"W", "ㅈ", "ㅉ"}, {"E", "ㄷ", "ㄸ"}, {"R", "ㄱ", "ㄲ"}, {"T", "ㅅ", "ㅆ"},
                    {"Y", "ㅛ", "ㅛ"}, {"U", "ㅕ", "ㅕ"}, {"I", "ㅑ", "ㅑ"}, {"O", "ㅐ", "ㅒ"}, {"P", "ㅔ", "ㅖ"},
                    {"A", "ㅁ", "ㅁ"}, {"S", "ㄴ", "ㄴ"}, {"D", "ㅇ", "ㅇ"}, {"F", "ㄹ", "ㄹ"}, {"G", "ㅎ", "ㅎ"},
                    {"H", "ㅗ", "ㅗ"}, {"J", "ㅓ", "ㅓ"}, {"K", "ㅏ", "ㅏ"}, {"L", "ㅣ", "ㅣ"},
                    {"Z", "ㅋ", "ㅋ"}, {"X", "ㅌ", "ㅌ"}, {"C", "ㅊ", "ㅊ"}, {"V", "ㅍ", "ㅍ"}, {"B", "ㅠ", "ㅠ"},
                    {"N", "ㅜ", "ㅜ"}, {"M", "ㅡ", "ㅡ"},
            };
            for (String[] key : keys) {
                JAMO.put(key[0].charAt(0), new String[] { key[1], key[2] });
            }
        }

        @Override
        public String getShortName() {
            return "한";
        }

        @Override
        public String getLabel(int vk, boolean shifted) {
            String[] jamo = JAMO.get(vk);
            return jamo != null ? jamo[shifted ? 1 : 0] : null;
        }

        @Override
        public void enter(KeyTapper tapper) {
            tapper.tap(HANGUL_KEY, HANGUL_FLAGS);
        }

        @Override
        public void leave(KeyTapper tapper) {
            tapper.tap(HANGUL_KEY, HANGUL_FLAGS);
        }
    }

    /**
     * The languages the keyboard switches between, in order. The first is
     * where it starts, and should be the one the host starts in. When it is
     * not, holding the language key brings the labels in line.
     */
    public static final List<KeyboardLanguage> ALL = Collections.unmodifiableList(Arrays.asList(
            new English(),
            new Korean()
    ));
}
