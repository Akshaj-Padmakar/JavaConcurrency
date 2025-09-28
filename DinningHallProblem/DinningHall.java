package DinningHallProblem;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class DinningHall {
    private Random rnd = new Random();
    private Lock lock = new ReentrantLock();
    private Condition aloneDinning = lock.newCondition();
    private Condition leaveCondition = lock.newCondition();
    private int inside = 0;
    private int waiting = 0;
    private boolean alone = true;
    private int wantingToLeave = 0;
    
    public class StudentRunnable implements Runnable {
        private int id;
        public StudentRunnable(int id){
            this.id = id;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(rnd.nextInt(500));
                dine();
                Thread.sleep(rnd.nextInt(1000));
                leave();
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void dine() throws InterruptedException {
            lock.lock();
            try {
                waiting++;
                System.out.println("Student-" + this.id + " is trying to enter, waiting = " + waiting + " inside = " + inside);
                if(waiting > 1) {
                    alone = false;
                }
                while(alone) {
                    aloneDinning.await();
                }
                alone = false;
                aloneDinning.signalAll();
                waiting--;
                inside++;
                System.out.println("Student-" + this.id + " has entered waiting = " + waiting + " inside = " + inside);
            } finally {
                lock.unlock();
            }
        }

        private void leave() throws InterruptedException {
            lock.lock();
            try {
                wantingToLeave++;
                System.out.println("Student-" + this.id + " wants to leave, wantingToLeave = " + wantingToLeave + " inside = " + inside);
                if(inside == 2) {
                    if(wantingToLeave <= 1) {
                        leaveCondition.await();
                    }

                    leaveCondition.signalAll();
                }
                wantingToLeave--;
                inside--;
                if(inside == 0){
                    alone = true;
                }
                System.out.println("Student-" + this.id + " has left, wantingToLeave = " + wantingToLeave + " inside = " + inside);
            } finally {
                lock.unlock();
            }
        }
    }
    public void solve(int students) throws InterruptedException {
        List<Thread> studentThreads = new ArrayList<>();
        for(int i = 0; i < students; i++){
            studentThreads.add(new Thread(new StudentRunnable(i), "Student-Thread-" + i));
        }
        for(Thread t : studentThreads) {
            t.start();
        }
        for(Thread t : studentThreads) {
            t.join();
        }
    }
    public static void main(String[] args) throws InterruptedException {
        int students = 2;
        new DinningHall().solve(students);
    }
}