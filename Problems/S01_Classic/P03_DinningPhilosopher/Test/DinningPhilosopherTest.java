package Problems.S01_Classic.P03_DinningPhilosopher.Test;

import Problems.S01_Classic.P03_DinningPhilosopher.DinningPhilosopher;

public class DinningPhilosopherTest {
    public static void main(String[] args) throws InterruptedException {
        int n = 10; // number of Philosophers.
        new DinningPhilosopher(n).solve();
    }
}
