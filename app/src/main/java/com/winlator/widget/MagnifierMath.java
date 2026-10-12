package com.winlator.widget;

/**
 * Pure maths behind the magnifier gestures, kept free of Android types so it can be unit tested.
 *
 * <p>GLRenderer draws X-screen content at {@code screen = X * zoom - pan}, with the pan following
 * the X cursor. Touch input arrives in screen space and has to be turned back into X coordinates.
 */
public final class MagnifierMath {
    public static final float MIN_ZOOM = 1.0f;
    public static final float MAX_ZOOM = 3.0f;
    /** Relative change in finger distance a pinch must reach before it starts zooming. */
    public static final float PINCH_DEAD_ZONE = 0.03f;

    private MagnifierMath() {}

    /** The X coordinate drawn at {@code screen} by a frame drawn with {@code zoom} and {@code pan}. */
    public static int toContent(float screen, float pan, float zoom) {
        if (zoom <= 0) return Math.round(screen);
        return Math.round((screen + pan) / zoom);
    }

    /**
     * The zoom for a pinch that started at {@code startZoom} with fingers {@code startDistance}
     * apart and is now {@code distance} apart. Scaling by the distance ratio, not by per-event
     * deltas, keeps a repeated MOVE (InputControlsView forwards one per pointer) harmless.
     */
    public static float pinchZoom(float startZoom, float startDistance, float distance) {
        if (startDistance <= 0) return clamp(startZoom);
        return clamp(startZoom * distance / startDistance);
    }

    /** Whether the fingers moved apart or together enough to read as a pinch rather than jitter. */
    public static boolean pinchEngaged(float startDistance, float distance) {
        if (startDistance <= 0) return false;
        return Math.abs(distance / startDistance - 1.0f) >= PINCH_DEAD_ZONE;
    }

    public static float clamp(float zoom) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
    }
}
