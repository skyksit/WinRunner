package com.winlator.xserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class InputQueueTest {
    /** Runs posted work only when asked, so a test can queue input while the "thread" is busy. */
    private static class ManualExecutor implements java.util.concurrent.Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runAll() {
            while (!tasks.isEmpty()) tasks.poll().run();
        }
    }

    private static class RecordingSink implements InputQueue.Sink {
        final List<String> calls = new ArrayList<>();

        @Override
        public void moveTo(int x, int y) {
            calls.add("to " + x + "," + y);
        }

        @Override
        public void moveBy(int dx, int dy) {
            calls.add("by " + dx + "," + dy);
        }

        @Override
        public void button(Pointer.Button button, boolean pressed) {
            calls.add((pressed ? "press " : "release ") + button);
        }

        @Override
        public void key(XKeycode xKeycode, int keysym, boolean pressed) {
            calls.add((pressed ? "keydown " : "keyup ") + xKeycode + (keysym != 0 ? " " + keysym : ""));
        }
    }

    private final ManualExecutor executor = new ManualExecutor();
    private final RecordingSink sink = new RecordingSink();
    private final InputQueue queue = new InputQueue(executor, sink);

    @Test
    public void consecutiveRelativeMovesAreMerged() {
        queue.moveBy(3, 1);
        queue.moveBy(2, -4);
        queue.moveBy(-1, 0);
        executor.runAll();
        assertEquals(Arrays.asList("by 4,-3"), sink.calls);
    }

    @Test
    public void movesAreNotMergedAcrossAButton() {
        queue.moveBy(1, 1);
        queue.button(Pointer.Button.BUTTON_LEFT, true);
        queue.moveBy(2, 2);
        queue.moveBy(3, 3);
        queue.button(Pointer.Button.BUTTON_LEFT, false);
        executor.runAll();
        assertEquals(Arrays.asList("by 1,1", "press BUTTON_LEFT", "by 5,5", "release BUTTON_LEFT"), sink.calls);
    }

    @Test
    public void absoluteMoveReplacesWaitingMoves() {
        queue.moveTo(10, 10);
        queue.moveBy(5, 5);
        queue.moveTo(20, 30);
        executor.runAll();
        assertEquals(Arrays.asList("to 20,30"), sink.calls);
    }

    @Test
    public void absoluteMoveKeepsAWaitingClickInPlace() {
        queue.moveTo(10, 10);
        queue.button(Pointer.Button.BUTTON_LEFT, true);
        queue.moveTo(20, 20);
        executor.runAll();
        assertEquals(Arrays.asList("to 10,10", "press BUTTON_LEFT", "to 20,20"), sink.calls);
    }

    @Test
    public void onlyOneDrainIsPostedWhileBusy() {
        queue.moveBy(1, 0);
        queue.button(Pointer.Button.BUTTON_RIGHT, true);
        queue.button(Pointer.Button.BUTTON_RIGHT, false);
        assertEquals(1, executor.tasks.size());
        executor.runAll();

        // Idle again: the next event posts a new drain.
        queue.moveBy(0, 1);
        assertEquals(1, executor.tasks.size());
        executor.runAll();
        assertEquals(Arrays.asList("by 1,0", "press BUTTON_RIGHT", "release BUTTON_RIGHT", "by 0,1"), sink.calls);
    }

    @Test
    public void keysKeepTheirOrderWithMoves() {
        queue.moveBy(1, 0);
        queue.key(XKeycode.KEY_SHIFT_L, 0, true);
        queue.key(XKeycode.KEY_A, 65, true);
        queue.moveBy(2, 0);
        queue.moveTo(5, 5);
        queue.key(XKeycode.KEY_A, 0, false);
        executor.runAll();
        assertEquals(Arrays.asList("by 1,0", "keydown KEY_SHIFT_L", "keydown KEY_A 65", "to 5,5", "keyup KEY_A"), sink.calls);
    }

    @Test
    public void buttonStateFollowsWaitingInput() {
        assertFalse(queue.isButtonPressed(Pointer.Button.BUTTON_LEFT, false));
        queue.button(Pointer.Button.BUTTON_LEFT, true);
        // Queued, not yet run: a tap must see its own press, or it would never release.
        assertTrue(queue.isButtonPressed(Pointer.Button.BUTTON_LEFT, false));
        queue.button(Pointer.Button.BUTTON_LEFT, false);
        assertFalse(queue.isButtonPressed(Pointer.Button.BUTTON_LEFT, true));
        assertFalse(queue.isButtonPressed(Pointer.Button.BUTTON_RIGHT, false));
        executor.runAll();
        // Nothing waiting: the pointer's own state answers.
        assertTrue(queue.isButtonPressed(Pointer.Button.BUTTON_LEFT, true));
    }

    @Test
    public void zeroMoveIsDropped() {
        queue.moveBy(0, 0);
        assertEquals(0, executor.tasks.size());
    }
}
