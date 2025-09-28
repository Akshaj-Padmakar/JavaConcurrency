package FaneuilHallProblem;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class FaneuliHall {
    private final Random rnd = new Random();
    private Lock lock = new ReentrantLock();
    private Condition judgeConfirmCondition = lock.newCondition();
    private Condition immigrantsCertificate = lock.newCondition();
    private Condition immigrantsLeaveCondition = lock.newCondition();
    private boolean judgeInside = false;
    private int spectator = 0;
    private int immigrants = 0;
    private int immigrantCheckIn = 0;
    private boolean judgeConfirmed = false;

    public class SpectatorRunnable implements Runnable {
        private int id;
        private boolean canSpectate;
        public SpectatorRunnable(int id) {
            this.id = id;
            this.canSpectate = true;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(rnd.nextInt(500));
                enter();
                if(canSpectate){
                    spectate();
                    leave();
                }
            } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
            }
        }

        public void enter() {
            lock.lock();
            try {
                if(judgeInside) {
                    System.out.println("Judge is already inside the Court, Spectator-" + this.id + " cannot enter");
                    canSpectate = false;
                    return;
                }
                spectator++;
                System.out.println("Spectator-" + this.id + " has entered the court. spectatorCount = " + spectator);
            } finally {
                lock.unlock();
            }
        }

        public void spectate() {
            lock.lock();
            try {
                System.out.println("Spectator-" + this.id + " is spectating. spectatorCount = " + spectator);
            } finally {
                lock.unlock();
            }
        }

        public void leave() {
            lock.lock();
            try {
                System.out.println("Spectator-" + this.id + " is leaving. spectatorCount = " + spectator);
                spectator--;
            } finally {
                lock.unlock();
            }
        }

    }

    public class ImmigrantRunnable implements Runnable {
        private int id;
        private boolean canEnter;
        public ImmigrantRunnable(int id) {
            this.id = id;
            this.canEnter = true;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(rnd.nextInt(500));
                enter();
                if(canEnter){
                    checkIn();
                    sitDown();
                    swear();
                    getCertificate();
                    leave();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        public void enter() {
            lock.lock();
            try {   
                if(judgeInside) {
                    System.out.println("Judge is already inside the Court, Immigrant-" + this.id + " cannot enter");
                    canEnter = false;
                    return;
                }
                immigrants++;
                System.out.println("Immigrant-" + this.id + " has entered the court. immigrantCount = " + immigrants);
            } finally {
                lock.unlock();
            }
        }

        public void checkIn() {
            lock.lock();
            try {
                immigrantCheckIn++;
                System.out.println("Immigrant-" + this.id + " has checkIn the court. immigrantCount = " + immigrants + " immigrantCheckInCount = " + immigrantCheckIn);
                if(immigrantCheckIn == immigrants) {
                    judgeConfirmCondition.signal();
                }
            } finally {
                lock.unlock();
            }
        }

        public void sitDown() {
            System.out.println("Immigrant-" + this.id + " has sitDown. immigrantCount = " + immigrants + " immigrantCheckInCount = " + immigrantCheckIn);
        }

        public void swear() {
            System.out.println("Immigrant-" + this.id + " has swornIn. immigrantCount = " + immigrants + " immigrantCheckInCount = " + immigrantCheckIn);
        }

        public void getCertificate() throws InterruptedException {
            lock.lock();
            try {
                while(!judgeConfirmed) {
                    immigrantsCertificate.await();
                }
                System.out.println("Immigrant-" + this.id + " has got Certificate. immigrantCount = " + immigrants + " immigrantCheckInCount = " + immigrantCheckIn);
            } finally {
                lock.unlock();
            }
        }

        private void leave() throws InterruptedException {
            lock.lock();
            try {
                while(judgeInside) {
                    immigrantsLeaveCondition.await();
                }
                immigrants--;
                immigrantCheckIn--;
                System.out.println("Immigrant-" + this.id + " is leaving. immigrantCount = " + immigrants + " immigrantCheckInCount = " + immigrantCheckIn);
            } finally {
                lock.unlock();
            }
        }
    }

    public class JudgeRunnable implements Runnable {
        public JudgeRunnable() {
        }

        @Override
        public void run() {
            try {
                Thread.sleep(rnd.nextInt(500));
                enter();
                confirm();
                leave();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void enter() {
            lock.lock();
            try {
                judgeInside = true;
                System.out.println("Judge has entered !  immigrantCount = " + immigrants + " immigrantCheckInCount = " + immigrantCheckIn + " specatators = " + spectator);
            } finally {
                lock.unlock();
            }
        }

        private void confirm() throws InterruptedException {
            lock.lock();
            try {
                while(immigrantCheckIn != immigrants) {
                    judgeConfirmCondition.await();
                }
                judgeConfirmed = true;
                immigrantsCertificate.signalAll();
                System.out.println("Judge has confirmed !  immigrantCount = " + immigrants + " immigrantCheckInCount = " + immigrantCheckIn + " specatators = " + spectator);

            } finally {
                lock.unlock();
            }
        }

        private void leave() throws InterruptedException {
            lock.lock();
            try {
                Thread.sleep(50);
                System.out.println("Judge has left !  immigrantCount = " + immigrants + " immigrantCheckInCount = " + immigrantCheckIn + " specatators = " + spectator);
                judgeInside = false;
                immigrantsLeaveCondition.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }
    private void solve(int spectators, int immigrants) throws InterruptedException {
        List<Thread> spectatorThreads = new ArrayList<>();
        List<Thread> immigrantThreads = new ArrayList<>();
        Thread judgeThread = new Thread(new JudgeRunnable(), "Judge-Thread");
        
        for(int i = 0; i < spectators; i++) {
            spectatorThreads.add(new Thread(new SpectatorRunnable(i), "Spectator-Thread-" + i));
        }

        for(int i = 0; i < immigrants; i++) {
            immigrantThreads.add(new Thread(new ImmigrantRunnable(i), "Immigrant-Thread-" + i));
        }

        for(Thread t : spectatorThreads) {
            t.start();
        }
        for(Thread t : immigrantThreads) {
            t.start();
        }
        judgeThread.start();

        for(Thread t : spectatorThreads) {
            t.join();
        }
        for(Thread t : immigrantThreads) {
            t.join();
        }
    }
    public static void main(String[] args) throws InterruptedException {
        int spectators = 15;
        int immigrants = 10;

        new FaneuliHall().solve(spectators, immigrants);
    }
}
