package com.winlator.widget;

import android.content.Context;
import android.text.InputType;
import android.util.Log;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/**
 * The view the soft keyboard talks to.
 *
 * <p>Without one, a soft keyboard has no editor to commit text into and falls back to plain key
 * events. That works for Latin letters but not for anything the keyboard composes, so Hangul never
 * reached a game. This view takes the keyboard's text and hands it on in the two forms the guest
 * can use:
 * <ul>
 * <li>A character the virtual key map can type (Latin letters, digits, punctuation) becomes the same
 * key events the keyboard used to send, so games that read keys (hotkeys, DirectInput) see no
 * difference.</li>
 * <li>Anything else goes to {@link Callback#onUnicodeChar} as a code point; the X server sends it as
 * a Unicode keysym, which the container's patched winex11 commits as IME text (DGwine patch 0002).</li>
 * </ul>
 *
 * <p>Text being composed is not sent: a syllable is final only once the keyboard commits it, and a
 * game cannot take back characters it was already given. It is shown through
 * {@link Callback#onComposingText} instead.
 *
 * <p>This view holds focus for the whole session, not only while the soft keyboard is up. An IME
 * attaches to the focused editor, and a Bluetooth keyboard's Hangul composition is the IME's work
 * too: without the attachment its keys went straight to the game as Latin letters until the soft
 * keyboard had been opened once. Focus also decides who receives captured mouse events and
 * joystick motion, so both are passed on through the callback instead of letting the touchpad and
 * the controls overlay take focus back (which detached the IME again, and left the captured mouse
 * dead whenever the keyboard took focus).
 */
public class ImeInputView extends View {
    private static final String TAG = "ImeInputView";

    public interface Callback {
        /** A key event, to go the same way a hardware or keyboard-fallback key goes. */
        void onKeyEvent(KeyEvent event);

        /** A character the key map cannot type. */
        void onUnicodeChar(int codePoint);

        /** The text being composed, empty once it is committed or dropped. */
        void onComposingText(CharSequence text);

        /** A captured (relative) mouse event; the pointer owner handles it. */
        boolean onCapturedPointer(MotionEvent event);

        /** Joystick / generic motion that reached the focused view; the controls overlay handles it. */
        boolean onGenericMotion(MotionEvent event);
    }

    private final Callback callback;
    private final KeyCharacterMap keyCharacterMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
    private final StringBuilder composing = new StringBuilder();

    public ImeInputView(Context context, Callback callback) {
        super(context);
        this.callback = callback;
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        // A text field, so the keyboard offers its normal layouts (Korean included). Not a password
        // field: several keyboards drop to an ASCII-only layout for those.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        // Enter must reach the game as a key, not as an editor action the game cannot see.
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN |
                EditorInfo.IME_FLAG_NO_ENTER_ACTION | EditorInfo.IME_ACTION_NONE;
        outAttrs.initialSelStart = 0;
        outAttrs.initialSelEnd = 0;
        Log.d(TAG, "input connection created");
        return new Connection();
    }

    @Override
    public boolean onCapturedPointerEvent(MotionEvent event) {
        return callback.onCapturedPointer(event) || super.onCapturedPointerEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        return callback.onGenericMotion(event) || super.onGenericMotionEvent(event);
    }

    /** Drops any half-typed text, e.g. when the keyboard closes or the session pauses. */
    public void clearComposing() {
        if (composing.length() == 0) return;
        composing.setLength(0);
        callback.onComposingText("");
    }

    /** Debug builds: the same path a keyboard commit takes, so text can be typed over adb. */
    public void debugCommit(CharSequence text) {
        Log.d(TAG, "debug commit '" + text + "'");
        commit(text);
    }

    private void commit(CharSequence text) {
        if (text == null) return;
        for (int i = 0; i < text.length(); ) {
            int codePoint = Character.codePointAt(text, i);
            i += Character.charCount(codePoint);
            typeCodePoint(codePoint);
        }
    }

    private void typeCodePoint(int codePoint) {
        if (codePoint == '\n') {
            tapKey(KeyEvent.KEYCODE_ENTER);
            return;
        }

        KeyEvent[] events = codePoint <= 0xFFFF ? keyCharacterMap.getEvents(new char[]{(char)codePoint}) : null;
        if (events != null && events.length > 0) {
            for (KeyEvent event : events) callback.onKeyEvent(event);
        }
        else {
            Log.d(TAG, "unicode U+" + Integer.toHexString(codePoint));
            callback.onUnicodeChar(codePoint);
        }
    }

    private void tapKey(int keyCode) {
        callback.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        callback.onKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }

    private void setComposing(CharSequence text) {
        composing.setLength(0);
        if (text != null) composing.append(text);
        callback.onComposingText(composing);
    }

    private class Connection extends BaseInputConnection {
        Connection() {
            super(ImeInputView.this, false);
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            Log.d(TAG, "commitText '" + text + "'");
            setComposing("");
            commit(text);
            return true;
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            Log.d(TAG, "setComposingText '" + text + "'");
            setComposing(text);
            return true;
        }

        @Override
        public boolean finishComposingText() {
            if (composing.length() > 0) {
                String text = composing.toString();
                setComposing("");
                commit(text);
            }
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            // Nothing is mirrored here, so "before the cursor" can only mean the game's own text.
            for (int i = 0; i < beforeLength; i++) tapKey(KeyEvent.KEYCODE_DEL);
            return true;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            Log.d(TAG, "sendKeyEvent " + event);
            callback.onKeyEvent(event);
            return true;
        }

        @Override
        public boolean performEditorAction(int actionCode) {
            tapKey(KeyEvent.KEYCODE_ENTER);
            return true;
        }

        @Override
        public CharSequence getTextBeforeCursor(int length, int flags) {
            return "";
        }

        @Override
        public CharSequence getTextAfterCursor(int length, int flags) {
            return "";
        }
    }
}
