package com.winlator.xserver;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.concurrent.Executor;

/**
 * Hands Android input (keys, mouse, touch, on-screen controls) to the X server off the UI thread, in order.
 *
 * <p>Injecting takes the X server locks and writes events to the clients' sockets, which blocks
 * while a client is not reading (a game busy handling a click). Done on the UI thread, that froze
 * the app until Android killed it for not answering input. A captured mouse also reports far more
 * often than touch (it is not batched per frame), so consecutive moves still waiting here are
 * merged into one: a game that falls behind gets one move, not a backlog. Buttons and keys are
 * never merged and moves are not merged across them, so a click lands where it was made.
 */
public class InputQueue {
    public interface Sink {
        void moveTo(int x, int y);
        void moveBy(int dx, int dy);
        void button(Pointer.Button button, boolean pressed);
        void key(XKeycode xKeycode, int keysym, boolean pressed);
    }

    private static final byte MOVE_TO = 0, MOVE_BY = 1, BUTTON = 2, KEY = 3;

    private static final class Op {
        final byte type;
        int x, y;
        final Pointer.Button button;
        final XKeycode xKeycode;
        final boolean pressed;

        Op(byte type, int x, int y, Pointer.Button button, XKeycode xKeycode, boolean pressed) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.button = button;
            this.xKeycode = xKeycode;
            this.pressed = pressed;
        }

        boolean isMove() {
            return type == MOVE_TO || type == MOVE_BY;
        }
    }

    private final Executor executor;
    private final Sink sink;
    private final ArrayDeque<Op> ops = new ArrayDeque<>();
    /** The last button op of each button that has not run yet; see {@link #isButtonPressed}. */
    private final EnumMap<Pointer.Button, Op> pendingButtons = new EnumMap<>(Pointer.Button.class);
    private boolean draining;

    public InputQueue(Executor executor, Sink sink) {
        this.executor = executor;
        this.sink = sink;
    }

    public void moveTo(int x, int y) {
        synchronized (ops) {
            // An absolute position makes any move still waiting before it pointless.
            while (!ops.isEmpty() && ops.peekLast().isMove()) ops.pollLast();
            ops.add(new Op(MOVE_TO, x, y, null, null, false));
        }
        schedule();
    }

    public void moveBy(int dx, int dy) {
        if (dx == 0 && dy == 0) return;
        synchronized (ops) {
            Op last = ops.peekLast();
            if (last != null && last.type == MOVE_BY) {
                last.x += dx;
                last.y += dy;
                return;
            }
            ops.add(new Op(MOVE_BY, dx, dy, null, null, false));
        }
        schedule();
    }

    public void button(Pointer.Button button, boolean pressed) {
        synchronized (ops) {
            Op op = new Op(BUTTON, 0, 0, button, null, pressed);
            ops.add(op);
            pendingButtons.put(button, op);
        }
        schedule();
    }

    public void key(XKeycode xKeycode, int keysym, boolean pressed) {
        synchronized (ops) {
            ops.add(new Op(KEY, keysym, 0, null, xKeycode, pressed));
        }
        schedule();
    }

    /**
     * Whether the button will be down once the waiting input has run: its last queued press or
     * release, or {@code current} (the pointer's state) when none is waiting. Callers that press
     * only an up button and release only a down one must ask this, not the pointer - a quick tap
     * would otherwise see its own press still queued and never release.
     */
    public boolean isButtonPressed(Pointer.Button button, boolean current) {
        synchronized (ops) {
            Op op = pendingButtons.get(button);
            return op != null ? op.pressed : current;
        }
    }

    private void schedule() {
        synchronized (ops) {
            if (draining || ops.isEmpty()) return;
            draining = true;
        }
        executor.execute(this::drain);
    }

    private void drain() {
        while (true) {
            Op op;
            synchronized (ops) {
                op = ops.poll();
                if (op == null) {
                    draining = false;
                    return;
                }
            }
            switch (op.type) {
                case MOVE_TO:
                    sink.moveTo(op.x, op.y);
                    break;
                case MOVE_BY:
                    sink.moveBy(op.x, op.y);
                    break;
                case BUTTON:
                    sink.button(op.button, op.pressed);
                    // Cleared only after it ran, so the pointer already shows the state.
                    synchronized (ops) {
                        if (pendingButtons.get(op.button) == op) pendingButtons.remove(op.button);
                    }
                    break;
                case KEY:
                    sink.key(op.xKeycode, op.x, op.pressed);
                    break;
            }
        }
    }
}
