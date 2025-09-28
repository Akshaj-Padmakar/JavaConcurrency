package BabonCrossingProblem;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class BabonCrossingProblem {
    private int CAPACITY = 5;
    private Random rnd = new Random();
    public enum DIR {
        NONE, LEFT, RIGHT;
    }
    private DIR currentDir = DIR.NONE;
    private DIR previousDir = DIR.NONE;
    private int leftWaiting = 0;
    private int rightWaiting = 0;
    private int onboard = 0;
    private Lock lock = new ReentrantLock();
    private Condition leftCondition = lock.newCondition();
    private Condition rightCondition = lock.newCondition();
    
    public class BabonRightRunnable implements Runnable {
        private int id;
        public BabonRightRunnable(int id){
            this.id = id;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(rnd.nextInt(200));
                rightEnter();
                Thread.sleep(rnd.nextInt(200));
                rightExit();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void rightEnter() throws InterruptedException {
            lock.lock();
            try {
                rightWaiting++;
                while(currentDir == DIR.LEFT || onboard == CAPACITY || (currentDir == DIR.NONE && leftWaiting > 0 && previousDir == DIR.RIGHT)){
                    // time to alternate
                    rightCondition.await();
                }
                rightWaiting--;
                onboard++;
                System.out.println("Right Babon-" + this.id + " has entered, onboard: " + onboard);
                currentDir = DIR.RIGHT;
            } finally {
                lock.unlock();
            }
        }

        private void rightExit() throws InterruptedException {
            lock.lock();
            try{
                onboard--;
                System.out.println("Right Babon-" + this.id + " has exited, onboard: " + onboard);
                if(onboard == 0){
                    currentDir = DIR.NONE;
                    previousDir = DIR.RIGHT;

                    if(leftWaiting > 0) {
                        leftCondition.signalAll();
                    } else if(rightWaiting > 0){
                        rightCondition.signalAll();
                    }
                } else {
                    if(rightWaiting > 0){
                        rightCondition.signalAll();
                    }
                }
            } finally {
                lock.unlock();
            }
        }
    }
    public class BabonLeftRunnable implements Runnable {
        private int id;
        public BabonLeftRunnable(int id) {
            this.id = id;
        }
        
        @Override
        public void run() {
            try {
                Thread.sleep(rnd.nextInt(200));
                leftEnter();
                Thread.sleep(rnd.nextInt(200));
                leftExit();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        public void leftEnter() throws InterruptedException {
            lock.lock();
            try {
                leftWaiting++;
                while(currentDir == DIR.RIGHT || onboard == CAPACITY || (currentDir == DIR.NONE && previousDir == DIR.LEFT && rightWaiting > 0)){
                    leftCondition.await();
                }
                leftWaiting--;
                onboard++;
                System.out.println("Left Babon-" + this.id + " has entered, onboard: " + onboard);
                currentDir = DIR.LEFT;
            } finally {
                lock.unlock();
            }
        }

        public void leftExit() throws InterruptedException {
            lock.lock();
            try {
                onboard--;
                System.out.println("Left Babon-" + this.id + " has exited, onboard: " + onboard);
                if(onboard == 0){
                    currentDir = DIR.NONE;
                    previousDir = DIR.LEFT;
                    if(rightWaiting > 0){
                        rightCondition.signalAll();
                    } else if(leftWaiting > 0){
                        leftCondition.signalAll();
                    }
                } else {
                    if(leftWaiting > 0){
                        leftCondition.signalAll();
                    }
                }
            } finally {
                lock.unlock();
            }
        }
    }
    public void solve(int babonLeft, int babonRight) throws InterruptedException {
        List<Thread> babonLeftThreads = new ArrayList<>(), babonRightThreads = new ArrayList<>();
        for(int i = 0; i < babonLeft; i++){
            babonLeftThreads.add(new Thread(new BabonLeftRunnable(i), "Babon-Left-Thread-" + i));
        }

        for(int i = 0; i < babonRight; i++){
            babonRightThreads.add(new Thread(new BabonRightRunnable(i), "Babon-Right-Thread-" + i));
        }

        for(Thread t : babonLeftThreads){
            t.start();
        }
        for(Thread t : babonRightThreads){
            t.start();
        }

        for(Thread t : babonLeftThreads){
            t.join();
        }
        for(Thread t : babonRightThreads){
            t.join();
        }
    }
    public static void main(String[] args) throws InterruptedException {
        int babonLeft = 10;
        int babonRight = 10;
        new BabonCrossingProblem().solve(babonLeft, babonRight);
    }
}
