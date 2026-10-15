package com.winlator.widget;

import static org.junit.Assert.assertEquals;

import com.winlator.xserver.Pointer;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MouseInputQueueTest {
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

    private static class RecordingSink implements MouseInputQueue.Sink {
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
    }

    private final ManualExecutor executor = new ManualExecutor();
    private final RecordingSink sink = new RecordingSink();
    private final MouseInputQueue queue = new MouseInputQueue(executor, sink);

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
    public void zeroMoveIsDropped() {
        queue.moveBy(0, 0);
        assertEquals(0, executor.tasks.size());
    }
}
