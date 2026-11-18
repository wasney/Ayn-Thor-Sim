package com.aynthor.simulator;

import android.view.Surface;

interface IVirtualDisplayService {
    // Shizuku UserService destroy transaction code (16777114 in AIDL -> 16777115)
    void destroy() = 16777114;

    int[] createDisplays(
        in Surface topSurface,
        int topW,
        int topH,
        int topDpi,
        in Surface bottomSurface,
        int bottomW,
        int bottomH,
        int bottomDpi
    );

    void swapSurfaces(in Surface topSurface, in Surface bottomSurface);

    void injectTouchEvent(
        int displayId,
        int action,
        long downTime,
        long eventTime,
        int pointerCount,
        in int[] pointerIds,
        in float[] xs,
        in float[] ys,
        in float[] pressures
    );

    void injectKeyEvent(
        int displayId,
        int action,
        int keyCode,
        long downTime,
        long eventTime,
        int repeatCount,
        int metaState,
        int source
    );

    void injectJoystickEvent(
        int displayId,
        long downTime,
        long eventTime,
        float axisX,
        float axisY,
        float axisZ,
        float axisRz,
        float axisLTrigger,
        float axisRTrigger,
        float axisHatX,
        float axisHatY
    );

    boolean launchAppOnDisplay(String packageName, String activityName, int displayId);

    void releaseDisplays();
}
