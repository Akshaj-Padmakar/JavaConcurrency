# Dining Philosophers — Solution

> Book ref: *The Little Book of Semaphores* §4.4. This implementation: **waiter/footman** — admission
> capped at `n-1`, uniform left-then-right acquisition, each philosopher eats once.

## 1. The idea

A cycle in the wait-for graph needs **all `n`** philosophers holding one fork. Admit at most `n-1`,
and the cycle can never close.

## 2. State model

| Field | Meaning |
|---|---|
| `forkSemaphores[i]` | binary semaphore, 1 permit — fork `i` between philosopher `i` and `i+1` |
| `criticalSemaphore` | `n-1` permits — admission control ("the waiter") |
| `leftAcquired` / `rightAcquired` / `criticalAcquired` | per-thread flags so the `finally` releases exactly what it took |

Philosopher `i` uses `fork[i]` (left) and `fork[(i+1) % n]` (right).

```java
seats.acquire();                    // ask the waiter
fork[left].acquire();
fork[right].acquire();
    ... eat ...
fork[right].release();
fork[left].release();
seats.release();                    // forks BEFORE the seat
```

## 3. Mechanism, and the traps

- **The cap is what makes uniform left-then-right safe.** Without it, that order is the textbook
  deadlock. The order isn't the fix; the admission is.
- **`n-1` is the *maximum* safe cap, not a magic number.** Any smaller value is also correct and
  strictly less concurrent.
- **A counting semaphore has no owner.** `release()` doesn't check you ever acquired — it just
  increments. A mismatched release silently *creates a second copy of the resource*; it doesn't
  throw. This is the whole content of bug 1 below, and it's the strongest argument for
  `ReentrantLock` (whose `unlock()` throws `IllegalMonitorStateException` from a non-holder) whenever
  a resource has exactly one owner.
- **Release forks before the seat.** Releasing admission first lets a new philosopher in while you
  still hold forks. Not a correctness bug — they just block — but the happy path and the `finally`
  should agree.
- **Every acquired resource needs a flag, and every flag must release the thing it names.** Three
  flags, three releases, and the failure path is the one nobody runs.

## 4. What to ask the interviewer

1. "Do the philosophers loop forever, or eat once? That changes whether starvation is even observable."
2. "Do you want deadlock-freedom, or genuine starvation-freedom? They're different requirements."
3. "May I use `java.util.concurrent.Semaphore`, or should I build it from `wait`/`notify`?"
4. "Should this be a simulation that runs and finishes, or a component with `acquire(i)`/`release(i)`
   that other code drives?"
5. "How many philosophers — is `n = 1` or `n = 2` in scope?"

## 5. Answers to Problem.md §7

1. **Mutual exclusion, hold-and-wait, no-preemption, circular wait.** This breaks **circular wait**:
   in a ring, a cycle must involve all `n` philosophers, and with ≤ `n-1` holding anything the cycle
   can't close. (Some texts frame the seat as breaking hold-and-wait — say which you mean and why.)
   The alternative is resource ordering, which needs a global order over resources; admission control
   doesn't, so it generalises to resources with no natural numbering.
2. **Deadlock means no future interleaving makes progress** — the wait-for graph has a cycle and
   every edge is permanent, because nobody in the cycle releases before acquiring. The test: take a
   thread dump twice. Slow ⇒ the state differs and something is `RUNNABLE`. Deadlocked ⇒ identical
   dumps, everything parked, and the held/wanted sets form a cycle.
3. A cycle in the ring requires **all `n`** to hold exactly one fork and want their neighbour's. Cap
   at `n-1` and at least one philosopher holds nothing, so at least one edge is missing and no cycle
   exists. That's the argument — not "someone can always get both", which is the consequence.
4. **`n-2` works too**, and every value down to 1. All are deadlock-free; all are strictly less
   concurrent. `n-1` is the largest safe cap, so it's the right one.
5. **Resource ordering breaks circular wait directly** — a cycle would need a strictly increasing
   sequence of fork indices that wraps around, which a total order forbids. Concurrency is at least
   as good: there's no admission cap at all, so it's the better answer when the resources have a
   natural order.
6. **Livelock.** Everyone takes left, fails on right, releases, retries in lockstep. It's harder to
   detect than deadlock precisely because it *looks healthy* — threads are `RUNNABLE`, CPU is busy,
   the thread dump changes every sample. Only throughput reveals it. Fix: randomised backoff.
7. **Deadlock-freedom only.** The semaphores are non-fair, so nothing bounds waiting. The requirement
   asked for no starvation, which is strictly stronger — so strictly speaking this doesn't meet it.
   It's masked because each philosopher eats exactly once.
8. **`n = 1`:** `left = fork[0]`, `right = fork[(0+1) % 1] = fork[0]` — the same fork, so the
   philosopher deadlocks against itself; and `Semaphore(n-1)` = `Semaphore(0)` admits nobody, so it
   hangs before even getting there. **`n = 2`:** correct, but admission of 1 serialises everything —
   zero concurrency.
9. **In a `finally`, and each flag must release the resource it actually names.** The admission
   permit is held too, so it needs the same treatment. Order: forks first, then the seat.
10. **`acquire(int i)` blocks until philosopher `i` holds both forks; `release(int i)` returns them.**
    No threads inside the class, no sleeping, the caller drives. That's what a system coding round
    wants — *a thing with methods*, not a simulation with actors.

## 6. What the interviewer is checking

| Signal | What it proves |
|---|---|
| Naming the Coffman condition you broke | You know prevention is a choice, not a trick |
| Deriving the cap rather than reciting `n-1` | You can reason about the wait-for graph |
| Distinguishing deadlock / livelock / starvation | The three failure modes need different fixes |
| Releasing in `finally`, including admission | You think about the failure path |
| "Non-adjacent philosophers must eat in parallel" | You know a serialised solution is a wrong answer |
| Offering `acquire(i)`/`release(i)` | You build components, not simulations |

## 7. What fails you

- Uniform left-then-right with no mitigation.
- One global lock around eating — correct, deadlock-free, and wrong.
- Claiming "no deadlock" when asked for "no starvation".
- Releasing outside a `finally`, or releasing a resource you didn't take.
- Calling `tryLock` + retry deadlock-free without mentioning livelock.
- Not checking `n = 1`.
- Presenting a `main()` that prints a trace, when the ask was an API.

## 8. Extensions

**"Make it starvation-free."** → Fair semaphores, or the state-based (Tanenbaum) solution: a
philosopher eats only when neither neighbour eats, with a per-philosopher condition.
*Trap:* fairness on the seat isn't enough — the forks are contended too, so an unfair fork semaphore
reintroduces starvation.

**"No admission control allowed."** → Resource ordering: always take the lower-numbered fork first;
one asymmetric philosopher suffices. *Trap:* needs a global order over the resources, which real
systems often lack (two arbitrary account objects have no natural rank — you end up ordering by
identity hash, and then you must handle collisions).

**"Turn it into a component."** → `acquire(i)` / `release(i)`, state in the object, threads supplied
by the caller. *Trap:* `acquire` must be all-or-nothing — a caller interrupted midway must not leave
one fork taken.

**"Detect deadlock instead of preventing it."** → Build the wait-for graph, run cycle detection, abort
a victim. *Trap:* aborting needs rollback, which the philosophers don't have — this is a database
answer, not a lock answer.

**"Hand-roll the semaphore."** → `wait`/`notify` around a permit counter. *Trap:* `notifyAll`, not
`notify` — and `release()` must signal before returning.

**"Philosophers loop forever."** → Wrap in `while (true)` with a think phase. *Trap:* this is what
turns "no starvation" from trivially true into something you must actually design for.

## 9. Bug log

| Bug | Symptom | Lesson |
|---|---|---|
| `if (rightAcquired) getLeftForkSemaphore().release()` — released the **left** fork | Interrupt a philosopher mid-eat: `fork[2] = 2 permits` (two philosophers could hold the same fork — **mutual exclusion gone**) and `fork[3] = 0` (leaked forever). Deterministic, 3/3 | **A counting semaphore has no owner.** A mismatched `release()` inflates the count instead of throwing. `ReentrantLock` would have thrown `IllegalMonitorStateException` at the first misuse |
| `n = 1` hangs | `Semaphore(0)` admits nobody, and `left == right == fork[0]` anyway | Compute the index expressions at the boundary before assuming they differ |
| *(previous Solution.md)* asserted "releases in `finally`" with a ✅ | Both bugs were live while the sheet said the code was correct | A checkmark earned by *reading* the code is worth nothing. The `finally` existed; it released the wrong thing |
| *(previous Solution.md)* listed `n == 1` under Gotchas, unfixed | Documented, never acted on — exactly the P02 pattern | A recorded gotcha you don't convert into a guard is a note, not a fix |
| *(my probe)* First attempt interrupted at a fixed 120ms | Caught the philosopher **blocked acquiring** the right fork, not eating, so `rightAcquired` was false and the bug stayed invisible — only 2 `[CRASHED]` instead of 3 | When a bug lives on a narrow path, the probe must **prove it reached that path**. Discriminating `TIMED_WAITING` (in `eat()`) from `WAITING` (parked on a semaphore) is what made it deterministic |

## 10. Known limitations — deliberate trades

- **Uses `java.util.concurrent.Semaphore`** — violates the stated constraint. `RUBRIK_PREP.md`'s audit
  names this file for the `ConcurrentStructures/CustomSemaphore` swap; it's the one problem where the
  hand-rolled semaphore is the point.
- **Each philosopher eats once.** No loop, so starvation is unobservable and the non-fair semaphores
  never bite.
- **No bounded waiting** — non-fair semaphores give deadlock-freedom, not fairness.
- **The `finally` releases the seat before the forks**, opposite to the happy path.
- **It's a simulation, not a component.** `solve()` owns the threads; the round wants `acquire(i)` /
  `release(i)`.

## 11. Verified

Both bugs reproduced with a probe *before* the fix and re-run clean after.

**Covered:** interrupting a philosopher mid-`eat()` (both forks held) leaves every fork at exactly 1
permit and admission at `n-1`, 3/3 · `n = 0` and `n = 1` rejected at construction · `n = 2`, `n = 3`
complete · **60 randomised trials**, `n ∈ [2,7]`, 1–3 philosophers interrupted at random moments,
with a watchdog sampling permits *during* the run — **0 accounting violations, 0 hangs**.

**Not covered:** starvation bounds (each philosopher eats once, so there's nothing to starve) ·
fairness ordering · concurrency is never asserted — nothing checks that two non-adjacent philosophers
actually eat simultaneously, so a fully serialised implementation would pass everything above.

## 12. 30-second recall

> `n` forks, need **both** to eat. Uniform left-then-right ⇒ every philosopher holds one and wants
> their neighbour's ⇒ **circular wait**. Fix here: **admit only `n-1`** — a cycle in a ring needs all
> `n`, so it can't close. `n-1` is the *largest* safe cap; smaller works and is less concurrent.
> Alternatives: **resource ordering** (lowest-numbered fork first; needs a global order) ·
> **state-based** (eat only if neither neighbour eats; can be starvation-free) · **tryLock+backoff**
> (⇒ **livelock**, which looks healthy in a thread dump — everything `RUNNABLE`). Deadlock-free ≠
> starvation-free; non-fair semaphores give only the former. Release **forks before the seat**, all
> in a `finally`, each flag releasing the resource it names. **A semaphore has no owner — a
> mismatched `release()` duplicates the resource instead of throwing**; a lock would have thrown.
> `n = 1`: `left == right`, and `Semaphore(0)` admits nobody. Round wants `acquire(i)`/`release(i)`,
> not a `main()` that prints a trace.
