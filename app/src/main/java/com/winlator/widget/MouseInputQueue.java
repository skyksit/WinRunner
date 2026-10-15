package com.winlator.widget;

import com.winlator.xserver.Pointer;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;

/**
 * Hands external mouse input to the X server off the UI thread, in order.
 *
 * <p>Injecting takes the X server locks and writes events to the clients' sockets, which blocks
 * while a client is not reading (a game busy handling a click). Done on the UI thread, that froze
 * the app until Android killed it for not answering input. A captured mouse also reports far more
 * often than touch (it is not batched per frame), so consecutive moves still waiting here are
 * merged into one: a game that falls behind gets one move, not a backlog.
 */
public class MouseInputQueue {
    public interface Sink {
        void moveTo(int x, int y);
        void moveBy(int dx, int dy);
        void button(Pointer.Button button, boolean pressed);
    }

    private static final byte MOVE_TO = 0, MOVE_BY = 1, BUTTON = 2;

    private static final class Op {
        final byte type;
        int x, y;
        final Pointer.Button button;
        final boolean pressed;

        Op(byte type, int x, int y, Pointer.Button button, boolean pressed) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.button = button;
            this.pressed = pressed;
        }
    }

    private final Executor executor;
    private final Sink sink;
    private final ArrayDeque<Op> ops = new ArrayDeque<>();
    private boolean draining;

    public MouseInputQueue(Executor executor, Sink sink) {
        this.executor = executor;
        this.sink = sink;
    }

    public void moveTo(int x, int y) {
        synchronized (ops) {
            // An absolute position makes any move still waiting before it pointless.
            while (!ops.isEmpty() && ops.peekLast().type != BUTTON) ops.pollLast();
            ops.add(new Op(MOVE_TO, x, y, null, false));
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
            ops.add(new Op(MOVE_BY, dx, dy, null, false));
        }
        schedule();
    }

    public void button(Pointer.Button button, boolean pressed) {
        synchronized (ops) {
            ops.add(new Op(BUTTON, 0, 0, button, pressed));
        }
        schedule();
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
                    break;
            }
        }
    }
}
