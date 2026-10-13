package com.winlator.xserver;

import android.util.Log;
import android.util.SparseArray;

import com.winlator.core.Callback;
import com.winlator.renderer.Texture;
import com.winlator.xserver.errors.BadAlloc;
import com.winlator.xserver.errors.BadValue;

public class DrawableManager extends XResourceManager implements XResourceManager.OnResourceLifecycleListener {
    private final XServer xServer;
    private final SparseArray<Drawable> drawables = new SparseArray<>();

    // DGPlayer: a drawable's pixels are allocated up front (width * height * 4), unlike a real X
    // server where a window has no storage of its own. 8192x8192 is far beyond any screen here.
    private static final long MAX_STORAGE_BYTES = 8192L * 8192L * 4L;

    /**
     * DGPlayer: X sizes are CARD16 (1..65535) but this server keeps them in shorts. Reading them
     * as signed turned 65535 into -1, and the negative ByteBuffer capacity aborted the whole app
     * (Empire Earth creates such a window at startup). Clamp to the short range instead.
     */
    public static short readDimension(int unsignedValue) throws BadValue {
        if (unsignedValue <= 0) throw new BadValue(unsignedValue);
        if (unsignedValue > Short.MAX_VALUE) Log.w("XServer", "clamping drawable dimension "+unsignedValue);
        return (short)Math.min(unsignedValue, Short.MAX_VALUE);
    }

    public static void checkStorageSize(short width, short height) throws BadAlloc {
        if ((long)width * height * 4L > MAX_STORAGE_BYTES) throw new BadAlloc();
    }

    public DrawableManager(XServer xServer) {
        this.xServer = xServer;
        xServer.pixmapManager.addOnResourceLifecycleListener(this);
    }

    public Drawable getDrawable(int id) {
        return drawables.get(id);
    }

    public Drawable createDrawable(int id, short width, short height, byte depth) {
        return createDrawable(id, width, height, xServer.pixmapManager.getVisualForDepth(depth));
    }

    public Drawable createDrawable(int id, short width, short height, Visual visual) {
        if (id == 0) return new Drawable(id, width, height, visual);
        if (drawables.indexOfKey(id) >= 0) return null;
        Drawable drawable = new Drawable(id, width, height, visual);
        drawables.put(id, drawable);
        return drawable;
    }

    public void removeDrawable(int id) {
        Drawable drawable = drawables.get(id);

        final Texture texture = drawable.getTexture();
        if (texture != null) {
            if (texture.getOwner() == drawable) texture.setOwner(null);
            xServer.getRenderer().xServerView.queueEvent(texture::destroy);
        }

        Callback<Drawable> onDestroyListener = drawable.getOnDestroyListener();
        if (onDestroyListener != null) onDestroyListener.call(drawable);

        drawable.setOnDrawListener(null);
        drawables.remove(id);
    }

    @Override
    public void onFreeResource(XResource resource) {
        if (resource instanceof Pixmap) removeDrawable(((Pixmap)resource).drawable.id);
    }

    public Visual getVisual() {
        return xServer.pixmapManager.visual;
    }
}
