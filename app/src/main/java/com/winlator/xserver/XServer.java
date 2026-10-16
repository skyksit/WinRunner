package com.winlator.xserver;

import com.winlator.XServerDisplayActivity;
import com.winlator.contentdialog.DebugDialog;
import com.winlator.core.CursorLocker;
import com.winlator.renderer.GLRenderer;
import com.winlator.winhandler.WinHandler;
import com.winlator.xserver.extensions.BigReqExtension;
import com.winlator.xserver.extensions.DRI3Extension;
import com.winlator.xserver.extensions.Extension;
import com.winlator.xserver.extensions.GLXExtension;
import com.winlator.xserver.extensions.GenericEventExtension;
import com.winlator.xserver.extensions.MITSHMExtension;
import com.winlator.xserver.extensions.PresentExtension;
import com.winlator.xserver.extensions.SyncExtension;
import com.winlator.xserver.extensions.XComposite;
import com.winlator.xserver.extensions.XInputExtension;

import java.nio.charset.Charset;
import java.util.EnumMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class XServer {
    public enum Lockable {WINDOW_MANAGER, PIXMAP_MANAGER, DRAWABLE_MANAGER, GRAPHIC_CONTEXT_MANAGER, INPUT_DEVICE, CURSOR_MANAGER, SHMSEGMENT_MANAGER}
    private static final boolean ENABLE_CURSOR_LOCKER = false;
    public static final short VERSION = 11;
    public static final String VENDOR_NAME = "Elbrus Technologies, LLC";
    public static final Charset LATIN1_CHARSET = Charset.forName("latin1");
    public final XServerDisplayActivity activity;
    private final Extension[] extensions;
    public final ScreenInfo screenInfo;
    public final PixmapManager pixmapManager;
    public final ResourceIDs resourceIDs = new ResourceIDs(128);
    public final GraphicsContextManager graphicsContextManager = new GraphicsContextManager();
    public final SelectionManager selectionManager;
    public final DrawableManager drawableManager;
    public final WindowManager windowManager;
    public final CursorManager cursorManager;
    public final Keyboard keyboard = Keyboard.createKeyboard(this);
    public final Pointer pointer = new Pointer(this);
    public final InputDeviceManager inputDeviceManager;
    public final GrabManager grabManager;
    public final CursorLocker cursorLocker;
    private SHMSegmentManager shmSegmentManager;
    private GLRenderer renderer;
    private WinHandler winHandler;
    private final EnumMap<Lockable, ReentrantLock> locks = new EnumMap<>(Lockable.class);
    private boolean relativeMouseMovement = false;
    private final InputQueue inputQueue = createInputQueue();

    public XServer(XServerDisplayActivity activity, ScreenInfo screenInfo) {
        this.activity = activity;
        this.screenInfo = screenInfo;
        cursorLocker = ENABLE_CURSOR_LOCKER ? new CursorLocker(this) : null;
        for (Lockable lockable : Lockable.values()) locks.put(lockable, new ReentrantLock());

        pixmapManager = new PixmapManager();
        drawableManager = new DrawableManager(this);
        cursorManager = new CursorManager(drawableManager);
        windowManager = new WindowManager(screenInfo, drawableManager);
        selectionManager = new SelectionManager(windowManager);
        inputDeviceManager = new InputDeviceManager(this);
        grabManager = new GrabManager(this);

        DesktopHelper.attachTo(this);
        extensions = setupExtensions();
    }

    public boolean isRelativeMouseMovement() {
        return relativeMouseMovement;
    }

    public void setRelativeMouseMovement(boolean relativeMouseMovement) {
        if (cursorLocker != null) cursorLocker.setEnabled(!relativeMouseMovement);
        this.relativeMouseMovement = relativeMouseMovement;
    }

    public GLRenderer getRenderer() {
        return renderer;
    }

    public void setRenderer(GLRenderer renderer) {
        this.renderer = renderer;
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public void setWinHandler(WinHandler winHandler) {
        this.winHandler = winHandler;
    }

    public SHMSegmentManager getSHMSegmentManager() {
        return shmSegmentManager;
    }

    public void setSHMSegmentManager(SHMSegmentManager shmSegmentManager) {
        this.shmSegmentManager = shmSegmentManager;
    }

    private class SingleXLock implements XLock {
        private final ReentrantLock lock;

        private SingleXLock(Lockable lockable) {
            this.lock = locks.get(lockable);
            lock.lock();
        }

        @Override
        public void close() {
            lock.unlock();
        }
    }

    private class MultiXLock implements XLock {
        private final Lockable[] lockables;

        private MultiXLock(Lockable[] lockables) {
            this.lockables = lockables;
            for (Lockable lockable : lockables) locks.get(lockable).lock();
        }

        @Override
        public void close() {
            for (int i = lockables.length - 1; i >= 0; i--) {
                locks.get(lockables[i]).unlock();
            }
        }
    }

    public XLock lock(Lockable lockable) {
        return new SingleXLock(lockable);
    }

    public XLock lock(Lockable... lockables) {
        return new MultiXLock(lockables);
    }

    public XLock lockAll() {
        return new MultiXLock(Lockable.values());
    }

    public Extension getExtensionByName(String name) {
        for (Extension extension : extensions) if (extension.getName().equals(name)) return extension;
        return null;
    }

    private InputQueue createInputQueue() {
        // One thread keeps the order; it ends when input is idle and comes back on the next event.
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "XInput");
            thread.setDaemon(true);
            return thread;
        });
        executor.allowCoreThreadTimeOut(true);
        return new InputQueue(executor, new InputQueue.Sink() {
            @Override
            public void moveTo(int x, int y) {
                pointerMove(x, y);
            }

            @Override
            public void moveBy(int dx, int dy) {
                pointerMoveDelta(dx, dy);
            }

            @Override
            public void button(Pointer.Button button, boolean pressed) {
                if (pressed) pointerButtonPress(button);
                else pointerButtonRelease(button);
            }

            @Override
            public void key(XKeycode xKeycode, int keysym, boolean pressed) {
                if (pressed) keyPress(xKeycode, keysym);
                else keyRelease(xKeycode);
            }
        });
    }

    // The inject* methods only queue the input and return; see InputQueue for why.

    public void injectPointerMove(int x, int y) {
        inputQueue.moveTo(x, y);
    }

    public void injectPointerMoveDelta(int dx, int dy) {
        inputQueue.moveBy(dx, dy);
    }

    public void injectPointerButtonPress(Pointer.Button buttonCode) {
        inputQueue.button(buttonCode, true);
    }

    public void injectPointerButtonRelease(Pointer.Button buttonCode) {
        inputQueue.button(buttonCode, false);
    }

    public void injectKeyPress(XKeycode xKeycode) {
        injectKeyPress(xKeycode, 0);
    }

    public void injectKeyPress(XKeycode xKeycode, int keysym) {
        inputQueue.key(xKeycode, keysym, true);
    }

    public void injectKeyRelease(XKeycode xKeycode) {
        inputQueue.key(xKeycode, 0, false);
    }

    /** The button's state once the input injected so far has run; see {@link InputQueue#isButtonPressed}. */
    public boolean isPointerButtonPressed(Pointer.Button button) {
        return inputQueue.isButtonPressed(button, pointer.isButtonPressed(button));
    }

    private void pointerMove(int x, int y) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setPosition(x, y);
        }
    }

    private void pointerMoveDelta(int dx, int dy) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setPosition(pointer.getX() + dx, pointer.getY() + dy);
            if (cursorLocker == null) pointer.clampPosition();

            XInputExtension xInputExtension = getExtension(XInputExtension.class);
            if (xInputExtension != null) xInputExtension.sendRawMotion(dx, dy);
        }
    }

    private void pointerButtonPress(Pointer.Button buttonCode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setButton(buttonCode, true);

            XInputExtension xInputExtension = getExtension(XInputExtension.class);
            if (xInputExtension != null) xInputExtension.sendRawButtonState(buttonCode.code(), true);
        }
    }

    private void pointerButtonRelease(Pointer.Button buttonCode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setButton(buttonCode, false);

            XInputExtension xInputExtension = getExtension(XInputExtension.class);
            if (xInputExtension != null) xInputExtension.sendRawButtonState(buttonCode.code(), false);
        }
    }

    private void keyPress(XKeycode xKeycode, int keysym) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            keyboard.setKeyPress(xKeycode.id, keysym);
        }
    }

    private void keyRelease(XKeycode xKeycode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            keyboard.setKeyRelease(xKeycode.id);
        }
    }

    private Extension[] setupExtensions() {
        byte opcode = Extension.START_MAJOR_OPCODE;
        Extension[] extensions = new Extension[]{
            new BigReqExtension(this, opcode--),
            new MITSHMExtension(this, opcode--),
            new DRI3Extension(this, opcode--),
            new PresentExtension(this, opcode--),
            new SyncExtension(this, opcode--),
            new XComposite(this, opcode--),
            new GLXExtension(this, opcode--),
            new GenericEventExtension(this, opcode--),
            new XInputExtension(this, opcode--)
        };

        short nextEventId = 64;
        short nextErrorId = 128;
        for (Extension extension : extensions) {
            byte eventCount = extension.getEventCount();
            byte errorCount = extension.getErrorCount();
            if (eventCount > 0) {
                extension.setFirstEventId((byte)nextEventId);
                nextEventId += eventCount;
            }
            if (errorCount > 0) {
                extension.setFirstErrorId((byte)nextErrorId);
                nextErrorId += errorCount;
            }
        }
        return extensions;
    }

    public <T extends Extension> T getExtension(byte opcode) {
        int index = Extension.START_MAJOR_OPCODE - opcode;
        return (T)extensions[index];
    }

    public <T extends Extension> T getExtension(Class<T> extensionClass) {
        for (Extension extension : extensions) {
            if (extension.getClass() == extensionClass) return (T)extension;
        }
        return null;
    }

    public void debugPrint(String line) {
        DebugDialog debugDialog = activity.getDebugDialog();
        if (debugDialog != null) debugDialog.call("xserver:"+line);
    }
}
