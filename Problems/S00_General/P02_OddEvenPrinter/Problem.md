# Odd-Even Printer

Implement ordered printing using multiple threads.

The problem has multiple parts.

## Part 1: Odd-Even Printer

Given an integer `n`, print numbers from `0` to `n` using two threads:

- The even thread prints numbers where `value % 2 == 0`.
- The odd thread prints numbers where `value % 2 == 1`.
- The output must be in increasing order.

Example for `n = 5`:

```text
0 1 2 3 4 5
```

Expected API:

```java
new OddEvenPrinter(n).solve();
```

Think about:

- Which thread should run first.
- How a thread waits when it is not its turn.
- How to wake the correct next thread.
- How both threads exit cleanly after `n` is printed.
- Why the waiting condition should be checked in a `while` loop.

## Part 2: Odd-Even Printer Using Semaphores

Solve the same odd-even printing problem using semaphores instead of explicit locks and conditions.

Expected API:

```java
new OddEvenPrinterSemaphore(n).solve();
```

Think about:

- Initial semaphore permits.
- Which semaphore each thread waits on.
- Which semaphore each thread releases after printing.
- How to release the other thread during shutdown so it does not block forever.

## Part 3: K-Thread Printer

Generalize the problem to `k` threads.

Given `n` and `k`, print numbers from `0` to `n` such that number `value` is printed by thread:

```text
value % k
```

Expected API:

```java
new KThreadPrinter(n, k).solve();
```

Example for `n = 8`, `k = 3`:

```text
0 -> Thread 0
1 -> Thread 1
2 -> Thread 2
3 -> Thread 0
4 -> Thread 1
5 -> Thread 2
6 -> Thread 0
7 -> Thread 1
8 -> Thread 2
```

Think about:

- Maintaining one condition per thread.
- Waiting until `current % k == threadId`.
- Signaling the next thread after each print.
- Handling termination so all waiting threads can exit.

## Requirements

- Print values in strictly increasing order from `0` to `n`.
- Never print a number more than once.
- Never skip a number.
- Avoid busy waiting.
- Use `join()` so `solve()` returns only after all printer threads finish.
- Handle thread interruption cleanly.
