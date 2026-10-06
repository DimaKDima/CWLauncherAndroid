package net.kdt.pojavlaunch.utils;

import android.content.Context;

/** Native bridge used to hand the phone screen to the game Java machine. */
public final class JREUtils {
    private JREUtils() {}

    public static native void setupBridgeWindow(Object surface);

    public static native void releaseBridgeWindow();

    public static native void setLdLibraryPath(String path);

    public static native boolean dlopen(String path);

    public static native int chdir(String path);

    public static native void setupExitMethod(Context context);
}
