package BabonCrossingProblem;
import java.util.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class BabonCrossingFIFO {
private final int CAPACITY;
    private final ReentrantLock lock = new ReentrantLock();

    public enum DIR { NONE, LEFT, RIGHT }

    // queue of waiters in arrival order
    private final Deque<WaitNode> queue = new ArrayDeque<>();

    private DIR currentDir = DIR.NONE;
    private int onboard = 0;

    public BabonCrossingFIFO(int capacity) {
        this.CAPACITY = capacity;
    }

    private static class WaitNode {
        final DIR dir;
        final Condition cond;
        boolean cancelled = false; // if the waiter was interrupted/cancelled
        WaitNode(DIR dir, ReentrantLock lock) {
            this.dir = dir;
            this.cond = lock.newCondition();
        }
    }

    /**
     * Called by a baboon that wants to start crossing in direction 'dir'.
     * This method blocks until it is that thread's turn and it is safe to step on the rope.
     */
    public void enter(DIR dir) throws InterruptedException {
        lock.lock();
        WaitNode node = new WaitNode(dir, lock);
        queue.addLast(node);
        try {
            while (true) {
                // If node was cancelled while waiting (interrupted), remove and throw
                if (node.cancelled) throw new InterruptedException();

                // Only the head of the queue may attempt to board
                WaitNode head = queue.peekFirst();
                if (head != node) {
                    // wait for our turn
                    try { node.cond.await(); } catch (InterruptedException ex) {
                        // remove node from queue and propagate interrupt
                        boolean removed = queue.remove(node);
                        node.cancelled = true;
                        // if we removed the head, we should wake the new head
                        if (removed && !queue.isEmpty()) queue.peekFirst().cond.signal();
                        throw ex;
                    }
                    continue;
                }

                // head == node. Check whether boarding it is allowed:
                boolean directionOk = (currentDir == DIR.NONE) || (currentDir == node.dir);
                boolean capacityOk = (onboard < CAPACITY);

                if (directionOk && capacityOk) {
                    // we may board now: remove ourselves from queue and increment onboard
                    queue.removeFirst();
                    onboard++;
                    if (currentDir == DIR.NONE) currentDir = node.dir;
                    // After boarding, wake next head if it can also board (same dir and capacity)
                    if (!queue.isEmpty()) {
                        WaitNode next = queue.peekFirst();
                        if (next.dir == currentDir && onboard < CAPACITY) {
                            next.cond.signal();
                        }
                    }
                    return; // allowed to proceed onto rope
                } else {
                    // cannot board right now; wait until condition changes
                    try { node.cond.await(); } catch (InterruptedException ex) {
                        boolean removed = queue.remove(node);
                        node.cancelled = true;
                        if (removed && !queue.isEmpty()) queue.peekFirst().cond.signal();
                        throw ex;
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Called by a baboon when it finishes crossing.
     */
    public void exit() {
        lock.lock();
        try {
            onboard--;
            if (onboard < 0) onboard = 0; // defensive
            if (onboard == 0) {
                // rope empty now: clear direction and wake the FIFO head (if any)
                currentDir = DIR.NONE;
                if (!queue.isEmpty()) {
                    queue.peekFirst().cond.signal();
                }
            } else {
                // still some on rope: next in FIFO may board only if same direction and capacity allows
                if (!queue.isEmpty()) {
                    WaitNode next = queue.peekFirst();
                    if (next.dir == currentDir && onboard < CAPACITY) {
                        next.cond.signal();
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    // ---------- demo / test ----------
    public static class Baboon implements Runnable {
        private final int id;
        private final DIR dir;
        private final BabonCrossingFIFO rope;
        private final Random rnd = new Random();

        public Baboon(int id, DIR dir, BabonCrossingFIFO rope) {
            this.id = id;
            this.dir = dir;
            this.rope = rope;
        }

        @Override
        public void run() {
            try {
                // random arrival jitter
                Thread.sleep(rnd.nextInt(100));
                System.out.printf("%s wants to cross%n", describe());
                rope.enter(dir);
                System.out.printf("%s ON ROPE (onboard=%d dir=%s)%n", describe(), snapshotOnboard(rope), rope.currentDir);
                // simulate crossing time
                Thread.sleep(100 + rnd.nextInt(200));
                rope.exit();
                System.out.printf("%s FINISHED (onboard=%d)%n", describe(), snapshotOnboard(rope));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.printf("%s INTERRUPTED%n", describe());
            }
        }

        private String describe() { return String.format("[%s-%d]", dir, id); }

        // reflection helper to safely read onboard for logging (not part of core logic)
        private int snapshotOnboard(BabonCrossingFIFO r) {
            r.lock.lock();
            try { return r.onboard; }
            finally { r.lock.unlock(); }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        int left = 10, right = 10;
        int capacity = 5;
        BabonCrossingFIFO rope = new BabonCrossingFIFO(capacity);

        List<Thread> threads = new ArrayList<>();
        // create an interleaved sequence to stress FIFO ordering
        for (int i = 0; i < Math.max(left, right); i++) {
            if (i < left) threads.add(new Thread(new Baboon(i, DIR.LEFT, rope)));
            if (i < right) threads.add(new Thread(new Baboon(i, DIR.RIGHT, rope)));
        }
        // shuffle arrival order if you want random arrivals, or leave as interleaved for structured test
        // Collections.shuffle(threads);

        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        System.out.println("All baboons finished.");
    }
}
