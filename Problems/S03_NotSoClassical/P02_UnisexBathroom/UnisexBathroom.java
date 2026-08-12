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
        * Starvation but good throughput..

    * FIFO fairness
    ----------------
        * Whichever thread comes first, serve that....
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
    private final int menCount;
    private final int womenCount;

    private final Lock lock;
    private final Random rnd;
    private TYPE currentGender;
    private int currentGenderCnt;
    private int insideCnt;
    private final Queue<Node> menWaitingList;
    private final Queue<Node> womenWaitingList;

    private final int MAX_CAPACITY = 5;
    private final int BATCH_SIZE = 10;


    public UnisexBathroom(int menCount, int womenCount) {
        this.menCount = menCount;
        this.womenCount = womenCount;

        this.lock = new ReentrantLock();
        this.rnd = new Random();
        this.currentGender = TYPE.NONE;
        this.currentGenderCnt = 0;
        this.insideCnt = 0;
        this.menWaitingList = new LinkedList<>();
        this.womenWaitingList = new LinkedList<>();
    }

    private enum TYPE {
        NONE, MAN, WOMAN;
    }

    private static final class Node {
        private final int id;
        private final TYPE type;
        private final Condition condition;

        public Node(int id, TYPE type, Condition condition) {
            this.id = id;
            this.type = type;
            this.condition = condition;
        }

        private int getId() {
            return this.id;
        }

        private TYPE getType() {
            return this.type;
        }

        private Condition getCondition() {
            return this.condition;
        }
    }

    private class GenderRunnable implements Runnable {
        private final Node node;

        public GenderRunnable(int id, TYPE type) {
            if (type == TYPE.NONE) {
                throw new IllegalArgumentException("Gender Type cannot be none !");
            }
            this.node = new Node(id, type, lock.newCondition());
        }

        @Override
        public void run() {
            try {
                wander();

                enter();

                peeing();

                exit();
            } catch (InterruptedException ex) {
                System.out.println("This thread crashedddd !!! threadName = " + Thread.currentThread().getName());
                Thread.currentThread().interrupt();
            }
        }

        private void wander() throws InterruptedException {
            Thread.sleep(rnd.nextInt(300));
        }

        private void enter() throws InterruptedException {
            lock.lock();
            try {
                Queue<Node> waitingList = getCurrentGenderQueue();
                waitingList.add(this.node);
                while (!allow() || waitingList.peek() != this.node) {
                    this.node.getCondition().await();
                }
                enterInside();
                waitingList.poll();
                logEntry();
            } finally {
                lock.unlock();
            }
        }

        private void peeing() throws InterruptedException {
            Thread.sleep(peeingTime());
        }

        private void exit() {
            lock.lock();
            logExit();
            try {
                insideCnt--;
                if (currentGenderCnt == BATCH_SIZE) {
                    if (getOppositeGenderQueue().isEmpty()) {
                        signalCurrentGenderToEnter();
                    } else {
                        signalOtherGenderToEnter();
                    }
                } else {
                    if (getCurrentGenderQueue().isEmpty()) {
                        if (insideCnt == 0) {
                            signalOtherGenderToEnter();
                        }
                    } else {
                        getCurrentGenderQueue().peek().getCondition().signal();
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        private boolean allow() {
            return (currentGender == TYPE.NONE) ||
                    (currentGender == this.node.getType() && currentGenderCnt < BATCH_SIZE && insideCnt < MAX_CAPACITY);
        }

        private void enterInside() {
            currentGenderCnt++;
            insideCnt++;
            currentGender = this.node.getType();
        }

        private void signalCurrentGenderToEnter() {
            resetBathroom();
            List<Node> currentGenderList = peekFirstK(getCurrentGenderQueue(), MAX_CAPACITY);
            for (Node node : currentGenderList) {
                node.getCondition().signal();
            }
        }

        private void signalOtherGenderToEnter() {
            resetBathroom();
            List<Node> otherGenderList = peekFirstK(getOppositeGenderQueue(), MAX_CAPACITY);
            for (Node node : otherGenderList) {
                node.getCondition().signal();
            }
        }

        private void resetBathroom() {
            currentGender = TYPE.NONE;
            currentGenderCnt = 0;
        }

        private Queue<Node> getCurrentGenderQueue() {
            return this.node.getType() == TYPE.MAN ? menWaitingList : womenWaitingList;
        }

        private Queue<Node> getOppositeGenderQueue() {
            return this.node.getType() == TYPE.MAN ? womenWaitingList : menWaitingList;
        }

        private int peeingTime() {
            return 200 + rnd.nextInt(200);
        }

        private void logEntry() {
            System.out.println(this.node.getType().toString() + "-" + this.node.getId() + " has entered the bathroom.");
        }

        private void logExit() {
            System.out.println(
                    this.node.getType().toString() + "-" + this.node.getId() + " is done, leaving the bathroom.");

        }
    }

    private List<Node> peekFirstK(Queue<Node> queue, int k) {
        List<Node> res = new ArrayList<>();
        int cnt = 0;
        for (Node node : queue) {
            if (cnt >= k) {
                break;
            }
            res.add(node);
            cnt++;
        }
        return res;
    }

    private class ManRunnable extends GenderRunnable {
        public ManRunnable(int id) {
            super(id, TYPE.MAN);
        }
    }

    private class WomanRunnable extends GenderRunnable {
        public WomanRunnable(int id) {
            super(id, TYPE.WOMAN);
        }
    }

    public void solve() throws InterruptedException {
        List<Thread> menThreads = new ArrayList<>();
        List<Thread> womenThreads = new ArrayList<>();

        for (int i = 0; i < menCount; i++) {
            menThreads.add(new Thread(new ManRunnable(i), "Man-Thread-" + i));
        }

        for (int i = 0; i < womenCount; i++) {
            womenThreads.add(new Thread(new WomanRunnable(i), "Woman-Thread" + i));
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

    public static void main(String[] args) throws InterruptedException {
        new UnisexBathroom(10, 10).solve();
    }
}
