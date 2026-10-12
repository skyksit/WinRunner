package com.winlator.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MagnifierMathTest {
    @Test
    public void toContentIsIdentityWithoutZoom() {
        assertEquals(320, MagnifierMath.toContent(320, 0, 1.0f));
    }

    @Test
    public void toContentInvertsTheRendererTransform() {
        // 800x600 at 2x centred on (400, 300): pan = 400*2 - 400 = 400, so screen 400 shows X 400
        // and the right edge (800) shows X 600.
        assertEquals(400, MagnifierMath.toContent(400, 400, 2.0f));
        assertEquals(600, MagnifierMath.toContent(800, 400, 2.0f));
        assertEquals(200, MagnifierMath.toContent(0, 400, 2.0f));
    }

    @Test
    public void toContentRoundTripsTheRendererTransform() {
        float zoom = 1.75f, pan = 123.5f;
        for (int x = 0; x < 800; x += 37) {
            float screen = x * zoom - pan;
            assertEquals(x, MagnifierMath.toContent(screen, pan, zoom));
        }
    }

    @Test
    public void pinchScalesByDistanceRatio() {
        assertEquals(2.0f, MagnifierMath.pinchZoom(1.0f, 100, 200), 1e-6);
        assertEquals(1.5f, MagnifierMath.pinchZoom(3.0f, 200, 100), 1e-6);
    }

    @Test
    public void pinchIsClamped() {
        assertEquals(MagnifierMath.MAX_ZOOM, MagnifierMath.pinchZoom(2.0f, 100, 1000), 1e-6);
        assertEquals(MagnifierMath.MIN_ZOOM, MagnifierMath.pinchZoom(1.5f, 100, 10), 1e-6);
        assertEquals(1.5f, MagnifierMath.pinchZoom(1.5f, 0, 100), 1e-6);
    }

    @Test
    public void smallChangesAreJitter() {
        assertFalse(MagnifierMath.pinchEngaged(100, 102));
        assertFalse(MagnifierMath.pinchEngaged(0, 100));
        assertTrue(MagnifierMath.pinchEngaged(100, 104));
        assertTrue(MagnifierMath.pinchEngaged(100, 96));
    }
}
