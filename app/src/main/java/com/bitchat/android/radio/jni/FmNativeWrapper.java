package com.bitchat.android.radio.jni;

import android.util.Log;

/**
 * FmNativeWrapper
 *
 * Wraps the Qualcomm FM JNI interface (libqcomfmjni.so) from RevampedFMRadio.
 * Used only on Layer 0 (rooted / system-level devices where SELinux is relaxed).
 *
 * IMPORTANT — static initializer removed intentionally:
 * The original FmNative.java loads the library in a static initializer, which
 * fires at class-load time and throws UnsatisfiedLinkError (or its wrapper
 * ExceptionInInitializerError) before any try-catch can intercept it. We use
 * an explicit loadNative() method instead so FmHardwareController can attempt
 * the load in a controlled try-catch block.
 *
 * On Android API 24+ the linker namespace restriction blocks third-party apps
 * from loading vendor libraries — so this path succeeds only on rooted devices
 * or custom ROMs where the restriction is lifted. FmHardwareController treats
 * this as Layer 0 and falls through to RadioManager (Layer 1) on any failure.
 */
public class FmNativeWrapper {

    private static final String TAG = "FmNativeWrapper";
    private static final String LIBRARY_NAME = "qcomfmjni";

    private static boolean sLibraryLoaded = false;

    /**
     * Attempt to load libqcomfmjni.so.
     *
     * Must be called explicitly before any other method in this class.
     * Safe to call multiple times — subsequent calls are no-ops.
     *
     * @return true if the library was loaded (or already loaded), false otherwise.
     */
    public static boolean loadNative() {
        if (sLibraryLoaded) return true;

        try {
            System.loadLibrary(LIBRARY_NAME);
            sLibraryLoaded = true;
            Log.d(TAG, "libqcomfmjni loaded successfully");
            return true;
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "libqcomfmjni not available (expected on non-Qualcomm / non-rooted): " + e.getMessage());
        } catch (ExceptionInInitializerError e) {
            Log.w(TAG, "Static init failed while loading libqcomfmjni: " + e.getMessage());
        } catch (SecurityException e) {
            Log.w(TAG, "Security exception loading libqcomfmjni: " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "Unexpected error loading libqcomfmjni: " + e.getMessage());
        }
        return false;
    }

    /** @return true if native library is loaded and ready */
    public static boolean isAvailable() {
        return sLibraryLoaded;
    }

    // ── JNI native methods (Qualcomm FM HAL) ─────────────────────────────────
    // All methods guard against unloaded library — caller should check isAvailable() first.

    /** Open the FM device. @return file descriptor >= 0 on success, -1 on failure */
    public static native int openDev();

    /** Close the FM device. @return 0 on success */
    public static native int closeDev();

    /**
     * Power up the FM chip and tune to the initial frequency.
     * @param freq Frequency in MHz (e.g. 95.6f)
     * @return 0 on success
     */
    public static native int powerUp(float freq);

    /**
     * Power down the FM chip.
     * @param type 0 = normal, 1 = transmitter
     * @return 0 on success
     */
    public static native int powerDown(int type);

    /**
     * Tune to a specific frequency.
     * @param freq Frequency in MHz
     * @return 0 on success
     */
    public static native int tune(float freq);

    /**
     * Seek to next/previous station.
     * @param freq Current frequency in MHz
     * @param isUp true = seek up, false = seek down
     * @return found frequency in MHz, or -1 on failure
     */
    public static native float seek(float freq, boolean isUp);

    /**
     * Mute or unmute FM audio output.
     * @param mute true = mute
     * @return 0 on success
     */
    public static native boolean setMute(boolean mute);

    /**
     * Switch antenna type.
     * @param antennaType 0 = headset, 1 = short
     * @return 0 on success
     */
    public static native int switchAntenna(int antennaType);
}
