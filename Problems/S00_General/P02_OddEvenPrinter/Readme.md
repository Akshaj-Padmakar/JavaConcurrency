# S00 | P02 | OddEvenPrinter and Varients.

- Print the numbers from 1 to n. Now odd numbers should be printed by odd Thread and even by even Thread.
- [General] Print number from 1 to n, where number i should be printed by (i mod k)-th Thread.

## Solution.

### Method 1 :

- So generic solution which works everywhere is using a lock, and waiting on conditions in each runnable.
- Seems fine for odd-even case. But Gets a little messy for the K-varient.

### Method 2 :

- Other famous solution is using semaphores to print.
- Maintain k semaphores, where each runnable/Thread waits on it's semaphore and as soon as it acquires it's semaphore it prints and release the semaphore for the next Thread(mod k).
