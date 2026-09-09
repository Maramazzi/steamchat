package org.steamchat.voice;

public final class SteamWebRtcNative {
    private SteamWebRtcNative() {}

    /** Call after NativeLoader.initNativeLibs and before PeerConnectionFactory.initialize. */
    public static native void initialize();
}
