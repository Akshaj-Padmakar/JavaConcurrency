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
    private final int M; // number of women

    private Lock lock = new ReentrantLock();

    private final Random rnd = new Random();

    private final Queue<Node> menWaitingList = new LinkedList<>();
    private final Queue<Node> womenWaitingList = new LinkedList<>();

    private TYPE currentGender = TYPE.NONE;
    private final int BATCH_SIZE = 5;
    private final int MAX_CAPACITY = 3;
    private int currentGenderCnt = 0;
    private int insideCnt = 0;

    public UnisexBathroom(int N, int M) {
        this.N = N;
        this.M = M;
    }

    enum TYPE {
        MEN, WOMEN, NONE;
    }

    private class Node {
        private final TYPE type;
        private final int id;
        private final Condition condition;

        private Node(TYPE type, int id) {
            this.type = type;
            this.id = id;
            this.condition = lock.newCondition();
        }

        public TYPE getType() {
            return this.type;
        }

        public int getId() {
            return this.id;
        }

        public Condition getCondition() {
            return this.condition;
        }
    }

    private class GenderRunnable implements Runnable {
        private final Node node;

        public GenderRunnable(int id, TYPE type) {
            this.node = new Node(type, id);
            if (type != TYPE.MEN && type != TYPE.WOMEN) {
                throw new IllegalArgumentException("Only Men and Women gender can be set.");
            }

        }

        private int peeingTime() {
            return 300 + rnd.nextInt(100);
        }

        @Override
        public void run() {
            enter();

            startPeeing();

            exit();
        }

        private void enter() {
            lock.lock();
            Queue<Node> waitingList = getGenderQueue();
            waitingList.add(this.node);
            try {
                while (!allow()) {
                    this.node.getCondition().await();
                }
                enterInside();
                waitingList.remove(this.node);

                logEntry();
            } catch (InterruptedException ex) {
                ex.printStackTrace();
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }

        private void startPeeing() {
            try {
                Thread.sleep(peeingTime());
            } catch (InterruptedException ex) {
                ex.printStackTrace();
                Thread.currentThread().interrupt();
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
                            signalCurrentGenderToEnter();
                        }
                    } else {
                        // wait for the last currentGender Human to exit.
                    }
                } else {
                    if (getGenderQueue().size() > 0) {
                        Node currentGenderNode = getGenderQueue().peek();
                        currentGenderNode.getCondition().signal();
                    } else {
                        // No current gender waiting
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

        private void signalCurrentGenderToEnter() {
            resetBathroom();
            List<Node> signalSameGender = peekFirstK(getGenderQueue(), MAX_CAPACITY);
            for (Node node : signalSameGender) {
                node.getCondition().signal();
            }
        }

        private void resetBathroom() {
            currentGender = TYPE.NONE;
            currentGenderCnt = 0;
        }

        private List<Node> peekFirstK(Queue<Node> queue, int k) {
            List<Node> ret = new ArrayList<>();
            int cnt = 0;
            for (Node node : queue) {
                if (cnt >= k) {
                    break;
                }
                cnt++;
                ret.add(node);
            }
            return ret;
        }

        private void logEntry() {
            System.out.println(this.node.getType().toString() + "-" + this.node.getId() + " has entered the bathroom.");
        }

        private void logExit() {
            System.out.println(
                    this.node.getType().toString() + "-" + this.node.getId() + " is done, leaving the bathroom.");

        }

    }

    private class MenRunnable extends GenderRunnable {
        private MenRunnable(int id) {
            super(id, TYPE.MEN);
        }
    }

    private class WomenRunnable extends GenderRunnable {
        private WomenRunnable(int id) {
            super(id, TYPE.WOMEN);
        }
    }

    public void solve() throws InterruptedException {
        List<Thread> menThreads = new ArrayList<>();
        List<Thread> womenThreads = new ArrayList<>();

        for (int i = 0; i < this.N; i++) {
            menThreads.add(new Thread(new MenRunnable(i), "Men-Thread-" + i));
        }

        for (int i = 0; i < this.M; i++) {
            womenThreads.add(new Thread(new WomenRunnable(i), "Women-Thread-" + i));
        }

        for (Thread t : menThreads) {
            t.start();
        }
        for (Thread t : womenThreads) {
            t.start();
        }

        for (Thread t : menThreads) {
            t.join();
        }
        for (Thread t : womenThreads) {
            t.join();
        }
    }

    public static void main(String args[]) throws InterruptedException {
        new UnisexBathroom(22, 17).solve();
    }

}
