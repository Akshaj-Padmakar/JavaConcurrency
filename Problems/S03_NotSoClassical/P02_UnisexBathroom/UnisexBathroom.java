package Problems.S03_NotSoClassical.P02_UnisexBathroom;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/*
So many solution exist, some improving fairness some improving throughput,
S1 :
    * Allow the current gender 
    --------------------------
        * Starvation but good througput..

    * FIFO fainess
    ----------------
        * Which ever thread comes first, serve that....
        * Absolute Fairness guaranteed.
        * Throughput is bad !
    
    * Turnstile
    -------------
        * Same as FIFO almost, turnstile look once acquired by different gender, the current gender cannot access the bathroom...
        * Fairness but throughput is too bad.
    
    * Batching
    -----------
        * Certain number of men are allowed to enter(Batch size), once no men waiting or batch size completed switch to other gender...
        *  Fair + High througput...
    
    * Phasing (Similar to batching)
    ------------------------------
        * Same as batching with time constraint --> do one process for 100 ms or batch size...
        *  Fair + High througput...
*/

public class UnisexBathroom {
    private final int N; // number of men
    private final int M; // Number of women

    public UnisexBathroom(int N, int M) {
        this.N = N;
        this.M = M;
    }

    private Lock lock = new ReentrantLock();
    private Random rnd = new Random();

    public enum TYPE {
        NONE, MEN, WOMEN;
    }

    private TYPE currentGender = TYPE.NONE;
    private int currentGenderCnt = 0;
    private int insideCnt = 0;

    private final int BATCH_SIZE = 5;
    private final int MAX_CAPACITY = 3;

    private Queue<Node> menWaitingList = new LinkedList<>();
    private Queue<Node> womenWaitingList = new LinkedList<>();

    private class Node {
        private final TYPE type;
        private final int id;
        private Condition condition;

        public Node(int id, TYPE type) {
            this.id = id;
            this.type = type;
            this.condition = lock.newCondition();
        }

        public TYPE getType() {
            return type;
        }

        public int getId() {
            return this.id;
        }

        public Condition getCondition() {
            return this.condition;
        }
    }

    private abstract class GenderRunnable implements Runnable {
        private final Node node;

        public GenderRunnable(int id, TYPE type) {
            this.node = new Node(id, type);
            if (type != TYPE.MEN && type != TYPE.WOMEN) {
                throw new IllegalArgumentException("Only Men and Women gender can be set.");
            }
        }

        private int peeingTime() {
            return 300 + rnd.nextInt(100); // [300, 400)
        }

        public void run() {
            enter();

            startPeeing();

            exit();
        }

        private void enter() {
            lock.lock();
            Queue<Node> genderQueue = getGenderQueue();
            genderQueue.add(this.node);
            try {
                while (!allow()) {
                    this.node.getCondition().await();
                }
                enterInside();
                genderQueue.poll();

                logEntry();
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            } finally {
                lock.unlock();
            }

        }

        private void startPeeing() {
            logPeeing();
            try {
                Thread.sleep(this.peeingTime());
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
        }

        private void exit() {
            lock.lock();
            logExit();
            insideCnt--;
            try {
                if (currentGenderCnt == BATCH_SIZE) {
                    if (insideCnt == 0) {
                        if (getOppositeGenderQueue().size() > 0) {
                            signalOtherGenderToEnter();
                        } else {
                            currentGenderCnt = 0;
                            List<Node> signalCurrentGender = peekFirstK(getGenderQueue(), MAX_CAPACITY);
                            currentGender = this.node.getType();

                            for (Node currentGenderNode : signalCurrentGender) {
                                currentGenderNode.getCondition().signal();
                            }
                        }
                    } else { // Wait for last currentGender to exit.
                    }
                } else {
                    if (getGenderQueue().size() > 0) { // Signal more currentGender.
                        Node currentGenderNode = getGenderQueue().peek();
                        currentGenderNode.getCondition().signal();
                    } else {
                        // No currentGender waitng.
                        if (insideCnt == 0) {
                            signalOtherGenderToEnter();
                        }
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        private Queue<Node> getGenderQueue() {
            return this.node.getType() == TYPE.MEN ? menWaitingList : womenWaitingList;
        }

        private Queue<Node> getOppositeGenderQueue() {
            return this.node.getType() == TYPE.MEN ? womenWaitingList : menWaitingList;
        }

        private boolean allow() {
            if (currentGender == TYPE.NONE) {
                return true;
            } else if (this.node.getType() == currentGender && currentGenderCnt < BATCH_SIZE
                    && insideCnt < MAX_CAPACITY) {
                return true;
            }
            return false;
        }

        private void enterInside() {
            currentGender = this.node.getType();
            currentGenderCnt++;
            insideCnt++;
        }

        private void signalOtherGenderToEnter() {
            resetBathroom();
            List<Node> signalOppositeGender = peekFirstK(getOppositeGenderQueue(), MAX_CAPACITY);

            for (Node oppositeNode : signalOppositeGender) {
                oppositeNode.getCondition().signal();
            }
        }

        private void resetBathroom() {
            currentGender = this.node.getType() == TYPE.MEN ? TYPE.WOMEN : TYPE.MEN;
            currentGenderCnt = 0;
        }

        private void logEntry() {
            System.out.println(this.node.getType().toString() + "-" + this.node.getId() + " has entered the bathroom.");

        }

        private void logPeeing() {
            System.out.println(this.node.getType().toString() + "-" + this.node.getId() + " is peeeingg.......");

        }

        private void logExit() {
            System.out.println(
                    this.node.getType().toString() + "-" + this.node.getId() + " is done, leaving the bathroom.");

        }

    }

    private class MenRunnable extends GenderRunnable {
        public MenRunnable(int id) {
            super(id, TYPE.MEN);
        }

        @Override
        public void run() {
            super.run();
        }
    }

    private class WomenRunnable extends GenderRunnable {
        public WomenRunnable(int id) {
            super(id, TYPE.WOMEN);
        }

        @Override
        public void run() {
            super.run();
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
        List<Thread> menThread = new ArrayList<>();
        List<Thread> womenThread = new ArrayList<>();

        for (int i = 0; i < this.N; i++) {
            menThread.add(new Thread(new MenRunnable(i), "Men-Thread-" + i));
        }

        for (int i = 0; i < this.M; i++) {
            womenThread.add(new Thread(new WomenRunnable(i), "Women-Thread-" + i));
        }

        for (Thread t : menThread) {
            t.start();
        }

        for (Thread t : womenThread) {
            t.start();
        }

        for (Thread t : menThread) {
            t.join();
        }

        for (Thread t : womenThread) {
            t.join();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        int N = 22;
        int M = 17;
        new UnisexBathroom(N, M).solve();
    }

}
