package RoomPartyProblem;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class RoomParty {
    private Random rnd = new Random();

    private final int PARTY_STUDENT_CNT = 5;
    private Lock lock = new ReentrantLock();
    private Condition allowDean = lock.newCondition();
    private Condition allowStudent = lock.newCondition();
    private Condition allowDeanExit = lock.newCondition();
    private int currentStudent = 0;
    private boolean deanInside = false;
    public class StudentRunnable implements Runnable {
        int id;
        public StudentRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(rnd.nextInt(1000));
                enter();
                Thread.sleep(rnd.nextInt(1000));
                exit();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        public void enter() {
            lock.lock();
            try {
                System.out.println("Student-" + this.id + " is trying to enter. currentStudent = " + currentStudent + " deanInside = " + deanInside);
                while(deanInside) {
                    allowStudent.await();
                }
                currentStudent++;
                System.out.println("Student-" + this.id + " has entered. currentStudent = " + currentStudent + " deanInside = " + deanInside);
                if(currentStudent > PARTY_STUDENT_CNT) {
                    allowDean.signal();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }

        public void exit() {
            lock.lock();
            try {
                currentStudent--;
                System.out.println("Student-" + this.id + " has exited. currentStudent = " + currentStudent + " deanInside = " + deanInside);
                if(currentStudent == 0) {
                    allowDeanExit.signal();
                }
            } finally {
                lock.unlock();
            }
        }
    }

    public class DeanRunnable implements Runnable {
        public DeanRunnable() {

        }

        @Override
        public void run() {
             try {
                Thread.sleep(rnd.nextInt(200));
                enter();
                Thread.sleep(rnd.nextInt(200));
                exit();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void enter() {
            lock.lock();
            try {
                System.out.println("Dean is trying to enter, currentStudent = " + currentStudent + " deanInside = " + deanInside);
                while(currentStudent > 0 && currentStudent <= PARTY_STUDENT_CNT) {
                    allowDean.await();
                }
                deanInside = true;
                System.out.println("Dean has entered, currentStudent = " + currentStudent + " deanInside = " + deanInside);
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }   

        private void exit() {
            lock.lock();
            try {
                System.out.println("Dean is trying to exit, currentStudent = " + currentStudent + " deanInside = " + deanInside);
                while(currentStudent > 0) {
                    allowDeanExit.await();
                }
                deanInside = false;
                System.out.println("Dean has exited, currentStudent = " + currentStudent + " deanInside = " + deanInside);
                allowStudent.signalAll();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }
    }
    private void solve(int student) throws InterruptedException {
        List<Thread> studentThreads = new ArrayList<>();
        for(int i = 0; i < student; i++) {
            studentThreads.add(new Thread(new StudentRunnable(i), "Student-Thread-" + i));
        }
        Thread deanThread = new Thread(new DeanRunnable(), "Dean-Thread");
        deanThread.start();
        for(Thread t : studentThreads) {
            t.start();
        }

        for(Thread t : studentThreads){
            t.join();
        }
        deanThread.join();
    }
    public static void main(String[] args) throws InterruptedException {
        int student = 20;
        
        new RoomParty().solve(student);
    }
}
