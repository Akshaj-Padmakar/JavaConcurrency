package Problems.S03_NotSoClassical.P03_BaboonCrossing;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class BaboonCrossing {
    private final int N; // Left crossing babon count
    private final int M; // Right crossing babon count

    public BaboonCrossing(int N, int M) {
        this.N = N;
        this.M = M;
    }

    public enum DIR {
        NONE, LEFT, RIGHT;
    }

    private Lock lock = new ReentrantLock();

    private class Node {
        private final int id;
        private final DIR dir;
        private final Condition condition;

        public Node(int id, DIR dir) {
            this.id = id;
            this.dir = dir;
            this.condition = lock.newCondition();
        }

        public Condition getCondition() {
            return condition;
        }

        public DIR getDir() {
            return dir;
        }
    }

    private int currentBabonCnt = 0;
    private int onRopeBabonCnt = 0;
    private DIR currentDir = DIR.NONE;

    private final int MAX_CAPACITY = 5;
    private final int BATCH_SIZE = 10;

    private Queue<Node> leftBabonQueue = new LinkedList<>();
    private Queue<Node> rightBabonQueue = new LinkedList<>();

    private Random rnd = new Random();

    public abstract class Babon implements Runnable {
        private Node node;

        public Babon(int id, DIR dir) {
            if (dir != DIR.LEFT && dir != DIR.RIGHT) {
                throw new IllegalStateException("Dir is invalid.");
            }
            this.node = new Node(id, dir);
        }

        private int glidingTime() {
            return 300 + rnd.nextInt(100);
        }

        public void run() {
            enterRope();

            glideOnRope();

            exitRope();
        }

        private void enterRope() {
            lock.lock();
            Queue<Node> directionQueue = getDirectionQueue();
            directionQueue.add(this.node);
            try {
                while (!allow()) {
                    this.node.getCondition().await();
                }
                directionQueue.poll();
                currentDir = this.node.getDir();
                currentBabonCnt++;
                onRopeBabonCnt++;

                logRopeEntry();
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            } finally {
                lock.unlock();
            }
        }

        private void glideOnRope() {
            logGlidOnRope();
            try {
                Thread.sleep(glidingTime());
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
        }

        private void exitRope() {
            lock.lock();
            onRopeBabonCnt--;
            logRopeExit();
            try {
                if (currentBabonCnt == BATCH_SIZE) {
                    if (onRopeBabonCnt == 0) { // We switch to the other direction
                        if (getOtherDirectionQueue().size() > 0) {
                            signalOtherDirectionBabon();
                        } else {
                            currentBabonCnt = 0;
                            currentDir = this.node.getDir();

                            List<Node> signalThisDirectionBabon = peekFirstK(getDirectionQueue(), MAX_CAPACITY);
                            for (Node thisDirNode : signalThisDirectionBabon) {
                                thisDirNode.getCondition().signal();
                            }
                        }
                    } else { // wait for the last babon to cross for switching...
                    }
                } else {
                    if (getDirectionQueue().size() == 0) {
                        if (onRopeBabonCnt == 0) {
                            signalOtherDirectionBabon();
                        }
                    } else {

                        Node nxtNode = getDirectionQueue().peek();

                        nxtNode.getCondition().signal();
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        private Queue<Node> getDirectionQueue() {
            return this.node.getDir() == DIR.LEFT ? leftBabonQueue : rightBabonQueue;
        }

        private Queue<Node> getOtherDirectionQueue() {
            return this.node.getDir() == DIR.LEFT ? rightBabonQueue : leftBabonQueue;
        }

        private DIR getOtherDirection() {
            return this.node.getDir() == DIR.LEFT ? DIR.RIGHT : DIR.LEFT;
        }

        private boolean allow() {
            if (currentDir == DIR.NONE || (currentDir == this.node.getDir() && onRopeBabonCnt < MAX_CAPACITY
                    && currentBabonCnt < BATCH_SIZE)) {
                return true;
            }
            return false;
        }

        private void signalOtherDirectionBabon() {
            currentDir = getOtherDirection();
            currentBabonCnt = 0;
            List<Node> signalOtherDirectionBabon = peekFirstK(getOtherDirectionQueue(), MAX_CAPACITY);
            for (Node otherDirNode : signalOtherDirectionBabon) {
                otherDirNode.getCondition().signal();
            }
        }

        private void logRopeEntry() {
            System.out.println("Babaon with direction = " + this.node.getDir().toString() + " and id = " + this.node.id
                    + " has entered on the rope");
        }

        private void logGlidOnRope() {
            System.out.println("Babaon with direction = " + this.node.getDir().toString() + " and id = " + this.node.id
                    + " is now gliding on the rope........");
        }

        private void logRopeExit() {
            System.out.println("Babaon with direction = " + this.node.getDir().toString() + " and id = " + this.node.id
                    + " is leaving the rope.");
        }
    }

    private class RightDirBabonRunnable extends Babon {
        public RightDirBabonRunnable(int id) {
            super(id, DIR.RIGHT);
        }
    }

    private class LeftDirBabonRunnable extends Babon {
        public LeftDirBabonRunnable(int id) {
            super(id, DIR.LEFT);
        }

    }

    // helper: peek first K entries from queue without removing
    private List<Node> peekFirstK(Queue<Node> queue, int k) {
        List<Node> ret = new ArrayList<>();
        Iterator<Node> it = queue.iterator();
        int i = 0;
        while (i < k && it.hasNext()) {
            ret.add(it.next());
            i++;
        }
        return ret;
    }

    public void solve() throws InterruptedException {
        List<Thread> rightDirBabon = new ArrayList<>();
        List<Thread> leftDirBabon = new ArrayList<>();

        for (int i = 0; i < this.N; i++) {
            rightDirBabon.add(new Thread(new RightDirBabonRunnable(i), "Right-Dir-Babon-Thread-" + i));
        }

        for (int i = 0; i < this.M; i++) {
            leftDirBabon.add(new Thread(new LeftDirBabonRunnable(i), "Left-Dir-Babob-Thread-" + i));
        }

        for (Thread t : rightDirBabon) {
            t.start();
        }

        for (Thread t : leftDirBabon) {
            t.start();
        }

        for (Thread t : rightDirBabon) {
            t.join();
        }

        for (Thread t : leftDirBabon) {
            t.join();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        int N = 21;
        int M = 17;
        new BaboonCrossing(N, M).solve();
    }

}
