package com.limelight.binding.input.osk;

/**
 * The keys of the on-screen keyboard: a full keyboard like the Windows
 * on-screen keyboard, sixteen units wide.
 *
 * Every key sends the Windows virtual key code of the key in that place on a
 * US keyboard. What it types is up to the host's input method, which is what
 * makes one layout serve every language.
 */
final class KeyboardLayout {
    enum Kind {
        CHARACTER,      // Types a character; its label follows shift and the language
        FUNCTION,       // Any other key the host gets as it is
        MODIFIER,       // Shift, Ctrl, Alt, Win: applies to the next key, or stays on
        CAPS_LOCK,      // Toggled on the host, and changes the labels
        LANGUAGE,       // Switches the language, here and on the host
        HIDE,           // Folds the keyboard away into its button
    }

    enum Icon {
        NONE, UP, DOWN, LEFT, RIGHT, HIDE,
    }

    static final class Key {
        final Kind kind;
        final int vk;
        final float width;
        final String label;
        final String shiftedLabel;
        final boolean letter;
        final byte modifierFlag;
        final Icon icon;

        private Key(Kind kind, int vk, float width, String label, String shiftedLabel, boolean letter, byte modifierFlag, Icon icon) {
            this.kind = kind;
            this.vk = vk;
            this.width = width;
            this.label = label;
            this.shiftedLabel = shiftedLabel;
            this.letter = letter;
            this.modifierFlag = modifierFlag;
            this.icon = icon;
        }
    }

    static final int COLUMNS = 16;

    // Modifier flags of a keyboard packet
    static final byte SHIFT = 0x01, CTRL = 0x02, ALT = 0x04, META = 0x08;

    private static Key fn(String label, int vk, float width) {
        return new Key(Kind.FUNCTION, vk, width, label, null, false, (byte) 0, Icon.NONE);
    }

    private static Key fn(String label, int vk) {
        return fn(label, vk, 1);
    }

    private static Key icon(Icon icon, int vk) {
        return new Key(Kind.FUNCTION, vk, 1, "", null, false, (byte) 0, icon);
    }

    private static Key ch(String label, String shiftedLabel, int vk) {
        return ch(label, shiftedLabel, vk, 1);
    }

    private static Key ch(String label, String shiftedLabel, int vk, float width) {
        return new Key(Kind.CHARACTER, vk, width, label, shiftedLabel, false, (byte) 0, Icon.NONE);
    }

    private static Key letter(char letter) {
        return new Key(Kind.CHARACTER, letter, 1, String.valueOf(Character.toLowerCase(letter)), String.valueOf(letter), true, (byte) 0, Icon.NONE);
    }

    private static Key modifier(String label, int vk, float width, byte flag) {
        return new Key(Kind.MODIFIER, vk, width, label, null, false, flag, Icon.NONE);
    }

    static final Key[][] ROWS = {
            {
                    fn("Esc", 0x1B),
                    fn("F1", 0x70), fn("F2", 0x71), fn("F3", 0x72), fn("F4", 0x73),
                    fn("F5", 0x74), fn("F6", 0x75), fn("F7", 0x76), fn("F8", 0x77),
                    fn("F9", 0x78), fn("F10", 0x79), fn("F11", 0x7A), fn("F12", 0x7B),
                    fn("PrtSc", 0x2C), fn("Ins", 0x2D), fn("Del", 0x2E),
            },
            {
                    ch("`", "~", 0xC0),
                    ch("1", "!", 0x31), ch("2", "@", 0x32), ch("3", "#", 0x33), ch("4", "$", 0x34), ch("5", "%", 0x35),
                    ch("6", "^", 0x36), ch("7", "&", 0x37), ch("8", "*", 0x38), ch("9", "(", 0x39), ch("0", ")", 0x30),
                    ch("-", "_", 0xBD), ch("=", "+", 0xBB),
                    fn("Bksp", 0x08, 2),
                    fn("Home", 0x24),
            },
            {
                    fn("Tab", 0x09, 1.5f),
                    letter('Q'), letter('W'), letter('E'), letter('R'), letter('T'),
                    letter('Y'), letter('U'), letter('I'), letter('O'), letter('P'),
                    ch("[", "{", 0xDB), ch("]", "}", 0xDD), ch("\\", "|", 0xDC, 1.5f),
                    fn("End", 0x23),
            },
            {
                    new Key(Kind.CAPS_LOCK, 0x14, 1.75f, "Caps", null, false, (byte) 0, Icon.NONE),
                    letter('A'), letter('S'), letter('D'), letter('F'), letter('G'),
                    letter('H'), letter('J'), letter('K'), letter('L'),
                    ch(";", ":", 0xBA), ch("'", "\"", 0xDE),
                    fn("Enter", 0x0D, 2.25f),
                    fn("PgUp", 0x21),
            },
            {
                    modifier("Shift", 0xA0, 2.25f, SHIFT),
                    letter('Z'), letter('X'), letter('C'), letter('V'), letter('B'), letter('N'), letter('M'),
                    ch(",", "<", 0xBC), ch(".", ">", 0xBE), ch("/", "?", 0xBF),
                    modifier("Shift", 0xA1, 1.75f, SHIFT),
                    icon(Icon.UP, 0x26),
                    fn("PgDn", 0x22),
            },
            {
                    modifier("Ctrl", 0xA2, 1.25f, CTRL),
                    modifier("Win", 0x5B, 1.25f, META),
                    modifier("Alt", 0xA4, 1.25f, ALT),
                    new Key(Kind.LANGUAGE, 0, 1.5f, "", null, false, (byte) 0, Icon.NONE),
                    fn("", 0x20, 6.25f),
                    new Key(Kind.HIDE, 0, 1.5f, "", null, false, (byte) 0, Icon.HIDE),
                    icon(Icon.LEFT, 0x25), icon(Icon.DOWN, 0x28), icon(Icon.RIGHT, 0x27),
            },
    };

    private KeyboardLayout() {
    }
}
