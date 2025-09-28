package CigaretteSmokersProblem;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Semaphore;

public class CigaretteSmokersProblem {
    private final int ING_CNT = 3;
    private final int TOBACCO = 0;
    private final int PAPER = 1;
    private final int MATCHES = 2;

    private final List<Semaphore> ingredientSem = new ArrayList<>();
    private final List<Semaphore> smokerSem = new ArrayList<>();

    private final Random rnd = new Random();

    // agent waits/releases to coordinate rounds
    private final Semaphore agentSem = new Semaphore(1);

    // running flag for graceful shutdown
    private volatile boolean running = true;

    public CigaretteSmokersProblem(){
        for(int i = 0; i < ING_CNT; i++){
            smokerSem.add(new Semaphore(0));
            ingredientSem.add(new Semaphore(0));
        }
    }

    private class Agent implements Runnable {
        private final int iterations;
        public Agent(int iterations){
            this.iterations = iterations;
        }

        @Override
        public void run() {
            for(int i = 0; i < this.iterations; i++){
                System.out.println("This is round " + (i + 1));
                try {
                    agentSem.acquire();

                    int pick = rnd.nextInt(3); // 0..2
                    int a = -1, b = -1;

                    switch(pick) {
                        case 0:
                            System.out.println("Agent has supplied PAPER and MATCHES");
                            a = PAPER; b = MATCHES;
                            break;
                        case 1:
                            System.out.println("Agent has supplied TOBACCO and MATCHES");
                            a = TOBACCO; b = MATCHES;
                            break;
                        case 2:
                            System.out.println("Agent has supplied PAPER and TOBACCO");
                            a = PAPER; b = TOBACCO;
                            break;
                    }

                    // place the two ingredients
                    ingredientSem.get(a).release();
                    ingredientSem.get(b).release();

                    // signal exactly the smoker who has the missing ingredient
                    int missing = (TOBACCO + PAPER + MATCHES) - (a + b);
                    smokerSem.get(missing).release();

                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                System.out.println("Agent has supplied the ingredients on the table");
            }

            if (this.iterations > 0) {
                try {
                    agentSem.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            // shutdown: ensure workers can exit
            running = false;
            System.out.println("[Agent] finished producing. Shutting down...");

            // unblock any potentially waiting smokers and ingredient acquires
            for (int j = 0; j < ING_CNT; j++) {
                smokerSem.get(j).release();
                ingredientSem.get(j).release();
            }
        }
    }

    private class Smoker implements Runnable {
        private final int id;
        public Smoker(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            try{
                while (running) {
                    smokerSem.get(id).acquire();
                    if (!running) break;

                    // acquire the two ingredients placed by the agent
                    for(int i = 0; i < ING_CNT; i++){
                        if(i == this.id) continue;
                        ingredientSem.get(i).acquire();
                    }

                    System.out.println("made the cigarette and started smoking, smoker: " + this.id);

                    // simulate smoking
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

                    // notify agent that this round is done
                    agentSem.release();
                }
            } catch(InterruptedException e){
                Thread.currentThread().interrupt();
            }
            // exiting
        }
    }

    private void startDemo(int iterations) throws InterruptedException{
        Runnable agentRunnable = new Agent(iterations);
        Runnable tobaccoRunnable = new Smoker(TOBACCO);
        Runnable paperRunnable = new Smoker(PAPER);
        Runnable matchesRunnable = new Smoker(MATCHES);

        Thread agentThread = new Thread(agentRunnable, "Agent-Thread");
        Thread tobaccoThread = new Thread(tobaccoRunnable, "Smoker-Tobacco");
        Thread paperThread = new Thread(paperRunnable, "Smoker-Paper");
        Thread matchesThread = new Thread(matchesRunnable, "Smoker-Matches");

        tobaccoThread.start();
        paperThread.start();
        matchesThread.start();

        agentThread.start();
        agentThread.join();

        // ensure workers stop and join
        tobaccoThread.join();
        paperThread.join();
        matchesThread.join();
    }

    public static void main(String[] args) throws InterruptedException{
        CigaretteSmokersProblem problem = new CigaretteSmokersProblem();
        problem.startDemo(5);
    }
}
