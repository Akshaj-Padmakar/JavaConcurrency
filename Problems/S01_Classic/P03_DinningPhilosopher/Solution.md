# Dining Philosophers — Revision Sheet

> Book ref: _The Little Book of Semaphores_ §4.4. This repo uses the **waiter (footman)** solution.

## One-line idea

Deadlock happens only if all `n` philosophers hold one fork and wait for the other. **Never let more
than `n-1` sit down at once** → by pigeonhole, someone always gets both forks → no deadlock.

## Core template (this repo)

```java
Semaphore[] fork = new Semaphore[n];        // each = 1
Semaphore seats  = new Semaphore(n - 1);    // the "waiter": at most n-1 diners

// philosopher i: left = fork[i], right = fork[(i+1) % n]
seats.acquire();                 // ask the waiter for a seat
fork[left].acquire();
fork[right].acquire();
   ... eat ...
fork[left].release();
fork[right].release();
seats.release();                 // give the seat back
```

## Why it works (say this out loud)

- Deadlock needs **circular wait**: all `n` holding one fork. Capping at `n-1` diners makes that
  impossible — at least one fork pair is always fully free for someone.
- Breaks **hold-and-wait** at the table-entry level (Coffman condition #2).

## The 4 classic solutions (compare in interview)

| Solution                    | Breaks        | Risk / note                                               |
| --------------------------- | ------------- | --------------------------------------------------------- |
| **Waiter (n-1)**            | hold-and-wait | simple, this repo; deadlock-free                          |
| **Resource ordering**       | circular-wait | one philosopher goes right-then-left                      |
| **State-based (Tanenbaum)** | circular-wait | eat only if neither neighbor eats; can be starvation-free |
| **tryLock + backoff**       | hold-and-wait | may **livelock** (add randomized backoff)                 |

Resource-ordering one-liner: if `left < right` take left first, else right first — a single
asymmetric philosopher is enough to break the cycle.

## Things to keep in mind

- **Acquire order matters** without the waiter: uniform left-then-right ⇒ deadlock. The `Semaphore(n-1)`
  is what makes the uniform order safe here.
- **Always release forks + seat in `finally`** — an exception mid-eat must not leak a fork/seat
  (this repo tracks `leftAcquired/rightAcquired/criticalAcquired` and releases in `finally`). ✅
- **Deadlock ≠ starvation ≠ livelock.** Waiter solution is deadlock-free; strict starvation-freedom
  needs FIFO-fair semaphores or the state-based solution.
- **Concurrency:** non-adjacent philosophers eat in parallel; the waiter only blocks the `n`-th one.
- Prefer **fair** semaphores (`new Semaphore(k, true)`) if you want bounded waiting.

## Gotchas

- `Semaphore(n-1)` with `n == 1` → permits `0` → the lone philosopher can never sit (edge case;
  validate `n >= 2`).
- Releasing a fork you don't hold corrupts the permit count — release exactly what you acquired.

## Complexity

- Per meal: 3 semaphore acquires / releases → **O(1)**. Up to `n-1` philosophers eat concurrently.

## 30-second recall

> `n` forks, need both to eat. Uniform left-then-right ⇒ circular-wait deadlock. Fix = **waiter**:
> `Semaphore(n-1)` caps diners so one always gets both forks. Alternatives: resource ordering
> (one goes right-first), state-based, tryLock+backoff (livelock risk). Release forks + seat in `finally`.
