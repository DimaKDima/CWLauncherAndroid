package com.oracle.dalvik;

/** Starts the game Java machine inside this process so it can draw on the phone screen. */
public final class VMLauncher {
    private VMLauncher() {}

    public static native int launchJVM(String[] args);
}
