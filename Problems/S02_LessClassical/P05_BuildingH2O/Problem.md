# Building H₂O

> Book ref: _The Little Book of Semaphores_ §5.6.

## Problem

There are two kinds of threads: **hydrogen** and **oxygen**. To form a water molecule they must
group into **2 hydrogen + 1 oxygen**. Threads arrive in any order and any number.

- A thread that arrives must **wait** until it can be part of a complete molecule.
- When **2 H and 1 O** are available, they **bond** (each calls its `bond()` action) and are released
  together; only then may the next molecule start forming.
- No thread may proceed as part of an incomplete molecule (no lone O, no pair of H without an O, etc.).

### API (typical)

```java
class BuildingH2O {
    void hydrogen();  // wait until you can bond as one of the 2 H in a molecule, then bond()
    void oxygen();    // wait until you can bond as the 1 O in a molecule, then bond()
}
```

### Requirements

- **Exact ratio 2:1** — every completed group is exactly 2 H and 1 O.
- **Barrier semantics** — all 3 threads of a molecule must reach the bonding point before *any* of
  them leaves (they bond as a set).
- **No mixing across molecules** — a 3rd H must not bond with a molecule that already has its 2 H;
  it waits for the next one.
- **No busy-waiting.**

## The trap

It's a **barrier of size 3 with a composition constraint (2 H + 1 O)**. Two hazards:

1. **Over-admitting hydrogens.** If you only count "3 threads present," you can bond 3 H with no O.
   You must gate H and O by *type*: at most 2 H and exactly 1 O per group.
2. **Barrier reuse / leakage.** After a molecule bonds, the counters/permits must reset so the
   *next* trio forms cleanly — a straggler from molecule N must not leak into molecule N+1.

## Points to Ponder

- **Which primitive enforces the 2:1 count?** A common approach: a semaphore that admits **2**
  hydrogens and **1** oxygen per round, plus a **barrier** so all three bond together. (This repo
  starts with `hSemaphore = new Semaphore(2)`, `oSemaphore = new Semaphore(0)`.)
- **Who triggers the bond?** Usually the **oxygen** (the scarce, single member) acts as the
  coordinator: it waits for 2 H to be ready, releases/allows them, and everyone bonds.
- **The 3-way barrier.** How do you make all 3 wait for each other and leave together? (A
  `CyclicBarrier(3)`, or a counter + condition, or paired semaphores.) What resets it for reuse?
- **Reset without leaks.** Exactly where do you reset the H-count / re-arm the barrier so a 3rd, 4th
  H can't sneak into a finished molecule? (The "last one out re-arms" pattern.)
- **`Semaphore(2)` alone is not enough.** It caps concurrent H at 2 but doesn't guarantee those 2 H
  bond with the *same* O, nor that they all leave together. What extra synchronization is needed?
- **Deadlock check.** Can you wedge with, say, 2 H waiting and no O (or 1 O waiting and 1 H)? The
  design must simply *wait* for the missing type, never deadlock the ones already present.
- **Fairness / starvation.** With a flood of H and few O, do hydrogens starve? Is that acceptable
  (bounded by O arrivals)?
- **Generalization.** This is the **assemble-a-fixed-composition group** pattern (a typed barrier).
  How would it change for, say, 3 A + 2 B? Relation to Santa's "group of N" and River Crossing's
  "boat of 4"?
- **Termination.** How do leftover H or O threads (that can never complete a molecule) shut down
  cleanly?
