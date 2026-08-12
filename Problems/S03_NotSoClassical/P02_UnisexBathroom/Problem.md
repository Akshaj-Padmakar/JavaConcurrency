# The Unisex Bathroom

> Book ref: *The Little Book of Semaphores* §6.2.

> One bathroom, shared by men and women. Any number of the **same** gender may be inside together;
> a man and a woman may **never** be inside at the same time. And nobody waits forever — if women
> keep arriving and leaving in a steady stream while a man waits, he must eventually get in. Write
> the synchronisation, and tell me the worst-case wait.

## What you're building

**Group mutual exclusion with bounded waiting** — a shared resource that operates in one of several
mutually-exclusive *modes*, where everyone in the current mode can use it concurrently, and switching
modes is expensive enough that you want to batch.

That's an archive tier that is either reading or writing and pays a rewind to switch. A
log-structured store alternating between ingest and compaction. An accelerator running one kernel
type at a time. In all of them the same tension applies, and it is the entire problem:

> **Throughput wants long batches. Fairness wants short ones.**

Note this is *not* readers–writers. There, one role shares and the other is exclusive — asymmetric.
Here **both** modes share internally and exclude each other. Neither side is the "reader", so
"prefer writers" has no meaning; you need a rule that forces a handover regardless of which side is
currently in.

## Worked example

Suppose you cap occupancy at 3 and force a turnover check after 5 admissions.

| # | Event | Inside | Mode | Admitted this batch | Outcome |
|---|---|---|---|---|---|
| 1 | M1 arrives | 1 | MEN | 1 | room was idle — men take it |
| 2 | M2 arrives | 2 | MEN | 2 | same gender shares |
| 3 | M3 arrives | 3 | MEN | 3 | at the occupancy cap |
| 4 | M4 arrives | 3 | MEN | 3 | **waits** — room is full |
| 5 | W1 arrives | 3 | MEN | 3 | **waits** — wrong mode |
| 6 | M1 leaves | 2 → 3 | MEN | 4 | M4 admitted; still men's batch |
| 7 | M5 arrives, batch hits 5 | 3 | MEN | 5 | no further men admitted, even with room |
| 8 | last man leaves | 0 | — | — | **handover** — W1 enters, batch resets |

Two independent knobs appear here: how many may be **inside at once**, and how many may be
**admitted before a handover is forced**.

## The failures you're designing against

**1. Starvation with no turnover rule.** "Let anyone of the current gender in" satisfies mutual
exclusion perfectly and starves the other side forever:

| # | Inside | M1 |
|---|---|---|
| 1 | W1 | arrives, waits |
| 2 | W1, W2 | still waiting |
| 3 | W2, W3 (W1 left) | occupancy never reached 0 |
| … | never empty | **never enters** |

No individual woman does anything wrong, and no illegal state is ever reached — the man simply never
sees a gap.

**2. Checking "is anyone waiting?" only as the last occupant leaves.** This looks like a fix and
isn't. A woman who arrives *after* that check but *before* the mode actually flips can be admitted
under the old mode and restart the streak. The check and the handover have to be one indivisible
decision.

## The API

```java
public class UnisexBathroom {
    public UnisexBathroom(int men, int women);
    public void solve() throws InterruptedException;
}
```

```java
new UnisexBathroom(10, 10).solve();   // runs the simulation to completion
```

That's the whole public surface — the queues, the mode, and the batch bookkeeping are internal. The
caller picks how many of each and presses start.

## Constraints

- **No `java.util.concurrent` collections or `Semaphore`.** `ReentrantLock` + `Condition` is fine;
  be ready to drop to `synchronized` / `wait` / `notifyAll`.
- Plain `java.util` for the waiting queues — the container isn't the subject.
- **No busy-waiting**, and no `Thread.sleep` standing in for synchronisation. Sleeping to simulate
  time in the bathroom is fine.

## Requirements

- **Never** a man and a woman inside simultaneously.
- Any number of the same gender may be inside together (up to a capacity cap, if you impose one).
- **Bounded waiting:** a waiting thread of the other gender must be admitted within a bounded number
  of admissions — not merely "eventually, if the stream happens to stop".
- A mode switch may only happen when the room is **completely empty**.
- The room must not get stuck believing a gender still owns it once both queues have drained.
- Waiting threads consume no CPU and respond to interruption.
- Clean shutdown: nobody left blocked.

## Edge cases

- Zero men, or zero women.
- Exactly one person in total.
- Everybody arriving at the same instant.
- A capacity cap of 1 — does it degenerate correctly?
- A batch limit of 1 — strict alternation; does it still make progress?
- The batch limit is reached but the other queue is **empty**.
- A thread interrupted while queued.
- The last person leaves at the same moment a new arrival of the same gender turns up.

## Questions to answer before you code

1. Mutual exclusion is the easy half — write the state that gives it. Now look at that state: what in
   it *guarantees* a handover ever happens under an infinite stream of one gender?
2. You need a condition that becomes true even if the current gender never stops arriving. Name three
   candidates and say which you'd pick and why.
3. Why is "check whether the other gender is waiting, at the moment the last occupant leaves" not
   sufficient on its own? Write the interleaving that defeats it.
4. Occupancy cap and batch limit are different knobs. Are they independent? What breaks if you drop
   the occupancy cap entirely — and does the classic book version even have one?
5. When exactly is it safe to flip the mode? Which counter must be zero, and what goes wrong if you
   test the other one?
6. The batch limit is reached but nobody of the other gender is waiting. What should happen? Trace
   your reset logic — can the room end up permanently "owned" by a gender with no one in it?
7. How many threads should a single handover wake — one, or a whole batch's worth? What does
   `signalAll` cost you here, and what does it buy?
8. If several threads are signalled at once, do they leave the waiting queue in the order they were
   signalled? Could a later arrival's lock acquisition let it remove the wrong entry from the front?
9. Batching bounds cross-gender waiting. Does it also guarantee FIFO *within* one gender's queue?
10. State the worst-case wait for an arriving man, in terms of your two knobs. If you can't express
    it, you haven't got bounded waiting — you've got "probably fine".
11. `solve()` owns the threads. What would the component form be — the methods a caller's own threads
    would call — and which shape is a system coding round asking for?

## Jargon

| The plain phrasing | The term to use out loud |
|---|---|
| "one gender at a time, but many of them" | group mutual exclusion; mode-exclusive access |
| "which gender owns the room right now" | current mode / phase |
| "don't let one side hog it forever" | bounded waiting; starvation freedom |
| "let a few more of the same side in" | batching |
| "the rule that forces a handover" | turnover point |
| "a gate that stops new arrivals of the current side" | turnstile |
| "how many can be inside at once" | occupancy cap |
| "wake exactly this one thread" | targeted signal; per-thread condition |
| "wake everyone and let them sort it out" | broadcast — and the thundering herd it causes |
| "served in arrival order" | FIFO fairness |
| "the switch costs something, so batch it" | amortising the mode-switch cost |
