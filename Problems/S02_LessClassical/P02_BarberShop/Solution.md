# The Barbershop — Solution

> Book ref: *The Little Book of Semaphores* §5.2.
> Two implementations: **`BarberShopEz`** (shared conditions, no ordering guarantee, time-based stop)
> and **`BarberShop`** (a `Node` per customer, strict FIFO, join-based stop).

## 1. The idea

A bounded waiting room where the producer **never blocks** (full ⇒ leave), plus a **two-way
rendezvous** so the customer doesn't leave before its cut finishes and the barber doesn't seat the
next person before the chair is free.

## 2. State model

**`BarberShopEz`** — one flag per phase, shared conditions:

| Field | Meaning |
|---|---|
| `customerCnt` | everyone in the shop, including the one being cut. Balk at `nChairs + 1` |
| `barberChairEmpty` → `hairCutDone` → `customerLeft` | the three hand-off phases |
| `stop` | shutdown flag |

**`BarberShop`** (FIFO) — one `Node` per customer, carrying its own condition **and** its own phase
flags:

| Field | Meaning |
|---|---|
| `waitingList` | `Queue<Node>` — arrival order *is* service order, and the barber's sleep predicate |
| `Node.condition` | lets the barber wake **one specific** customer |
| `Node.barberReady` / `hairCutDoneFlag` / `customerLeftFlag` | the same three phases, per customer |
| `customerCnt` | everyone in the shop — used **only** for the balk test |

**The invariant that had to be learned the hard way:**

> `customerCnt` and `waitingList.size()` do **not** track the same thing. They disagree for the
> entire interval between the barber polling a customer and that customer leaving.

The barber must therefore sleep on `waitingList.isEmpty()`, never on `customerCnt == 0` — see §9.

## 3. Mechanism, and the traps

- **Three phases, and every flag must be reset.** `barberChairEmpty → hairCutDone → customerLeft`.
  A missed reset doesn't hang; it makes the next round *skip* a wait.
- **Both waits are load-bearing.** Customer waits for `hairCutDone`; barber waits for `customerLeft`.
  Drop the first and customers leave mid-haircut; drop the second and the barber seats someone into
  an occupied chair — and, here, dereferences a `null` poll.
- **Never test one variable and act on another.** The NPE in §9 is exactly that: guard on
  `customerCnt`, dereference `waitingList.poll()`.
- **Signals are a hint; state is the truth.** A customer can signal `barberSleepingCondition` before
  the barber reaches `await()`; nothing is lost because the queue is durable and re-tested in a
  `while`.
- **Shared conditions work only while you never need to wake a *specific* thread.** `Ez` qualifies —
  at most one customer is in the hand-off at a time. FIFO does not, which is the whole reason for
  `Node.condition`. Putting the condition *inside* the node (rather than a parallel `Map`) also makes
  it impossible to look up id `x` in one map and id `y` in another.
- **A setter that ignores its argument is a reset waiting to fail silently.** See §9.

## 4. What to ask the interviewer

1. "Full shop — does the customer block or leave? That's `put` vs `offer`, and it changes everything."
2. "FIFO service, or just no starvation? FIFO costs a condition per customer."
3. "Does the customer need to know its own haircut finished, or is fire-and-forget enough?"
4. "One barber or several? Hilzer's is the multi-barber generalisation."
5. "Is there a shutdown, and can it fire while customers are still queued?"

## 5. Answers to Problem.md §7

1. **They count different things.** `Ez.customerCnt` and `BarberShop.customerCnt` both count everyone
   in the shop (balk at `nChairs + 1`, capacity `N` waiting + 1 in the chair). But `BarberShop` also
   keeps `waitingList`, and the two diverge — §2.
2. **The count test and the increment**, in one critical section. Both implementations do this.
3. **Not lost.** The queue / counter is durable and re-tested in a `while`, so a barber arriving late
   at `await()` still sees the customer through the state.
4. **Customer waits for "cut done" so it doesn't walk out mid-haircut; barber waits for "customer
   left" so it doesn't seat the next into an occupied chair.** They're independent failures, which is
   why one flag can't cover both.
5. **Shared is enough only when you never need to target a thread.** FIFO must wake the head of the
   queue, so each customer needs its own condition.
6. **FIFO is guaranteed only in `BarberShop`**, by `waitingList.poll()` plus signalling that node's
   own condition. In `Ez` the order is whatever the non-fair lock produced — incidental.
7. **`waitOrBalk()` returns `false`** and the thread returns. Retry is a caller policy.
8. **`N = 0` is legal** — balk at `customerCnt == 1` admits exactly one customer (the one in the
   chair) and turns everyone else away. Verified: `(0,10)` → 1 seated, 9 balked.
9. **The customer is left parked on its `hairCutDoneFlag`.** Both barbers restore the interrupt flag
   and keep going, so nothing resets the in-flight customer. Real hazard; `Ez` carries a comment
   about it.
10. **Producer never blocks** (balking = `offer`), and **the hand-off is synchronous** — each caller
    waits for *its own* completion. The second forces per-customer state.
11. **`boolean customerArrives(int id)` / `void barberWorks()`**, threads supplied by the caller.

## 6. What the interviewer is checking

| Signal | What it proves |
|---|---|
| Balking framed as load shedding | You know why `offer` beats `put` at a saturated front door |
| Naming *both* halves of the rendezvous | You thought about the hand-off, not just the queue |
| Per-customer condition for FIFO | You know a shared condition can't target a thread |
| Sleeping on the queue, not a parallel counter | You noticed two variables can drift apart |
| Joining workers before signalling stop | You make shutdown structural, not timing-dependent |
| Asking about shutdown up front | You treat termination as a requirement |

## 7. What fails you

- Blocking the customer when the shop is full — that's a different problem.
- Reading the seat count and sitting down in two different critical sections.
- One shared "done" flag when you need to wake a specific customer.
- Implementing only the customer's wait, so the barber starts the next cut too early.
- Guarding on one variable and dereferencing another.
- A setter that ignores its parameter.
- `while (true)` + `join()` with no stop path.
- A shutdown that signals one condition when the class has `2 + N` of them.

## 8. Extensions

**"Hilzer's Barbershop — `K` barbers."** → A barber pool; chair-and-rendezvous state becomes
per-barber. *Trap:* capacity is now `N + K`, and each customer must be matched to a **specific**
barber, so per-customer conditions stop being optional.

**"Customers give up after waiting T."** → `await(timeout)` on their own condition, then leave.
*Trap:* they must remove themselves from `waitingList` **and** fix the count atomically, or the
barber polls a customer that has gone — the same null-poll as §9.

**"Barber batches `k` customers."** → Wait until `k` are seated or a timeout fires. *Trap:* waiters
now have different predicates, so `signal` becomes wrong.

**"Shut down while customers are still queued."** → Stop flag in **every** wait predicate, and
signal **every** condition — including each queued node's. *Trap:* this is precisely the bug in §9;
the join-based shutdown sidesteps it by making the situation impossible instead.

**"Turn it into a component."** → `customerArrives(id)` / `barberWorks()`. *Trap:* `customerArrives`
must be all-or-nothing on interrupt, or a half-seated customer corrupts the count.

## 9. Bug log

| Bug | Symptom | Lesson |
|---|---|---|
| `Ez`: `hairCutDone` set `true`, never reset | First customer sat 604 ms; **every later one went "occupied" → "done" in 0 ms** while the barber was still asleep in its 600 ms cut | A stale phase flag doesn't hang, it makes the next round **skip a wait**. Skipping is far harder to notice than blocking. `barberChairEmpty = false` was written *twice* in the same method while the flag that mattered was missed — three booleans want to be one enum |
| FIFO: `while (customer.getCustomerLeftFlag())` — **inverted** | Flag is `false` on entry, so the barber never waited for the chair to be vacated and immediately looped to the next customer | An inverted guard reads as plausible and fails as a *different* bug — here it surfaced as an NPE, not as a rendezvous fault |
| FIFO: barber slept on `customerCnt == 0` but dereferenced `waitingList.poll()` | **NPE killed the barber thread.** 5 cut, 10 balked, **0 customers ever completed** | `customerCnt` and `waitingList.size()` disagree for the whole interval between poll and departure. **Never guard on one variable and act on another** — sleep on the queue you're about to poll |
| FIFO: `setHairCutDoneFlag(boolean value) { this.flag = true; }` | No effect yet — only ever called with `true` | A setter that ignores its argument is a **reset that will silently fail**, which is exactly the bug that broke `Ez`. The sibling setters were correct, which is what made it invisible |
| FIFO: `doStop()` empty; `stop` also read outside the lock | `solve()` returned while the barber looped on | A stop flag read without the lock is a data race even once the method is filled in |
| `Ez` + early FIFO: time-based shutdown | `Ez` at `(50,60)`: **26 customers parked forever**, `solve()` returned anyway. FIFO with a 1 s timer: stranded customers in **8 of 8** runs; at `(50,60)`, **53 of 60** left at `waitOrBalk` | A timer makes correctness depend on `nChairs`, `nCustomers` and haircut duration. **Join the workers, then stop** — that turns "only the barber remains" from an assumption into an invariant |
| *(my review)* Called the `Ez` `waitOrBalk`→`getHairCut` lock gap a bug | Unreproduced: **0 double-occupancy over 12 runs × 400 customers** | The window is microseconds, once per 600 ms haircut. Keep *structurally impossible* and *improbable* as separate claims |
| *(my probe)* Reported stranded customers after the join-based fix | False alarm — the probe waited a fixed 1.6 s, but `solve()` now runs as long as the work takes; those threads were mid-haircut | **When the shutdown strategy changes from time-based to join-based, the test's timing assumption must change too.** Wait for `solve()` to *return*, don't sample at a fixed instant |

## 10. Known limitations — deliberate trades

- **`Ez` still uses the time-based stop** (`sleep(15_000)` then `doStop()`). Correct for `(5,15)`,
  broken for `(50,60)`. The FIFO version's join-based shutdown is the better pattern.
- **`solve()` does not `join()` the barber** — it calls `doStop()` and returns. The barber does exit
  (0 leftovers across 7 configurations), but returning means "asked to stop", not "has stopped".
- **`logExit()` is declared and never called** — the log shows seatings and balks but no completions,
  so a customer hung mid-haircut looks identical to one that finished.
- **`Ez` has no service ordering** — starvation possible in principle.
- **Interrupt policy is inconsistent** between the two files, and neither exits the loop on interrupt.
- Fixed / random sleep durations, no injectable clock.

## 11. Verified

**`BarberShop` (FIFO), after all fixes** — waiting for `solve()` to return rather than sampling at a
fixed time:

| Config | solve() | seated | balked | total | rendezvous violations | leftover threads |
|---|---|---|---|---|---|---|
| (3,15) ×4 | returned, 0.8–1.9 s | 5–6 | 9–10 | **15** | 0 | 0 |
| (1,20) | returned, 0.9 s | 3 | 17 | **20** | 0 | 0 |
| (50,60) | returned, 13.1 s | 52 | 8 | **60** | 0 | 0 |
| (0,10) | returned, 0.6 s | 1 | 9 | **10** | 0 | 0 |

Every customer accounted for, strict start/seat pairing (the barber never begins a cut before the
previous customer left), no uncaught exceptions, no leaked threads. Earlier: strict FIFO — seat order
and serve order identical across 8 customers.

**`BarberShopEz`, after the `hairCutDone` reset:** full ~605 ms per customer (was 0 ms after the
first) · 6 served / 9 balked of 15 · 0 double-occupancy over 12 runs × 400 customers · shutdown clean
at `(5,15)`.

**Not covered:** interrupting the barber mid-haircut (§5.9 is reasoned, not measured) · starvation
bounds for `Ez` · `Ez`'s time-based stop is only verified where customers finish before the timer —
`(50,60)` is the counter-example · nothing pins the §2 invariant, so re-introducing a `customerCnt`
based sleep predicate would break it silently again.

## 12. 30-second recall

> Bounded waiting room + **balking** (producer never blocks — `offer`, not `put`) + a **two-way
> rendezvous**. Both waits matter: customer waits for *cut done* so it doesn't leave mid-haircut,
> barber waits for *customer left* so it doesn't seat the next into a full chair. **Reset every phase
> flag** — a stale one doesn't hang, it skips a wait (0 ms haircuts). **Never guard on one variable
> and dereference another**: `customerCnt` and `waitingList` disagree between poll and departure, and
> sleeping on the wrong one NPEs the barber. **Shared conditions only work while you never need to
> wake a *specific* thread** — FIFO needs a condition per customer, best held *inside* the node.
> Wake-up race is a non-issue: state is durable and re-tested in a `while` — *signals are a hint,
> state is the truth*. **Shutdown: join the workers, then stop.** A timer makes correctness depend on
> workload; joining makes "only the barber is left" an invariant. Otherwise the stop flag must be in
> every wait predicate and every condition must be signalled.
