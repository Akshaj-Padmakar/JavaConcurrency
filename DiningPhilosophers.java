import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

public class DiningPhilosophers {
    private int n = 5;
    private List<Semaphore> forkLock;
    private Semaphore dinner;
    
    public DiningPhilosophers(int n) {
        this.n = n;
        dinner = new Semaphore(n - 1);
        forkLock = new ArrayList<>();
        for(int i = 0; i < n; i++){
            forkLock.add(new Semaphore(1));
        }
    }

    // call the run() method of any runnable to execute its code
    public void wantsToEat(int philosopher,
                           Runnable pickLeftFork,
                           Runnable pickRightFork,
                           Runnable eat,
                           Runnable putLeftFork,
                           Runnable putRightFork) throws InterruptedException {
        int idL = philosopher;
        int idR = (philosopher + 1) % this.n;
        try {
            dinner.acquire();
            
            forkLock.get(idL).acquire();
            forkLock.get(idR).acquire();
            
            pickLeftFork.run();
            pickRightFork.run();

            eat.run();
        } finally{
            putLeftFork.run();
            putRightFork.run();

            forkLock.get(idL).release();
            forkLock.get(idR).release();
            
            dinner.release();
        }
    }
}