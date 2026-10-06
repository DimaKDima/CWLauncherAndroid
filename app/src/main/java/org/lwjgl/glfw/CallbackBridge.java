package org.lwjgl.glfw;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

/**
 * Touch and keys from the phone screen. The game Java machine reads these through the renderer.
 */
public final class CallbackBridge {
    public static final int CLIPBOARD_COPY = 2000;
    public static final int CLIPBOARD_PASTE = 2001;

    private static volatile Context app;
    private static volatile boolean grabbing;

    private CallbackBridge() {}

    public static void setContext(Context context) {
        app = context.getApplicationContext();
    }

    public static void prepare() {
        // Class init loads the renderer once, before the game machine starts.
    }

    public static boolean isGrabbing() {
        return grabbing;
    }

    public static void sendCursorPos(float x, float y) {
        nativeSendCursorPos(x, y);
    }

    public static void sendMouseButton(int button, int action, int mods) {
        nativeSendMouseButton(button, action, mods);
    }

    public static void sendKey(int key, int scancode, int action, int mods) {
        nativeSendKey(key, scancode, action, mods);
    }

    public static void tapKey(int key) {
        nativeSendKey(key, 0, 1, 0);
        nativeSendKey(key, 0, 0, 0);
    }

    public static void sendChar(char value) {
        nativeSendCharMods(value, 0);
        nativeSendChar(value);
    }

    public static void sendScroll(double x, double y) {
        nativeSendScroll(x, y);
    }

    public static void sendScreenSize(int width, int height) {
        nativeSendScreenSize(width, height);
    }

    @SuppressWarnings("unused")
    public static String accessAndroidClipboard(int type, String copy) {
        Context context = app;
        if (context == null) return "";
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return "";
        if (type == CLIPBOARD_COPY) {
            clipboard.setPrimaryClip(ClipData.newPlainText("CWLauncher", copy == null ? "" : copy));
            return null;
        }
        if (type == CLIPBOARD_PASTE) {
            if (!clipboard.hasPrimaryClip() || clipboard.getPrimaryClip() == null) return "";
            CharSequence text = clipboard.getPrimaryClip().getItemAt(0).getText();
            return text == null ? "" : text.toString();
        }
        return null;
    }

    @SuppressWarnings("unused")
    public static void onGrabStateChanged(boolean value) {
        grabbing = value;
    }

    @SuppressWarnings("unused")
    public static void onDirectInputEnable() {
    }

    @SuppressWarnings("unused")
    public static float getAndroidDPI() {
        Context context = app;
        if (context == null) return 1f;
        return context.getResources().getDisplayMetrics().density;
    }

    @SuppressWarnings("unused")
    public static boolean notifyLauncher(int type, int[] action) {
        return false;
    }

    public static native boolean nativeSendChar(char codepoint);

    public static native boolean nativeSendCharMods(char codepoint, int mods);

    public static native void nativeSendKey(int key, int scancode, int action, int mods);

    public static native void nativeSendCursorPos(float x, float y);

    public static native void nativeSendMouseButton(int button, int action, int mods);

    public static native void nativeSendScroll(double xoffset, double yoffset);

    public static native void nativeSendScreenSize(int width, int height);

    public static native void nativeSetUseInputStackQueue(boolean useInputStackQueue);

    static {
        System.loadLibrary("pojavexec");
    }
}
