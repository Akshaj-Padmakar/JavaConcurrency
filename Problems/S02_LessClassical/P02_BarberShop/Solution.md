# Barbershop — Revision Sheet

> Book ref: _The Little Book of Semaphores_ §5.2. Two implementations here:
> `BarberShopEz.java` (simple, **unfair**) and `BarberShop.java` (**FIFO / fair**).

## One-line idea

One barber, one barber chair, `N` waiting chairs. Barber sleeps when empty; a customer wakes him,
gets cut, and leaves; if all `N` waiting chairs are full the customer **balks**. Two rendezvous have
to line up: **wake-the-barber** and the **haircut hand-off**.

## The 3-phase rendezvous (both versions)

```
1. sit:   customerInside++ ; if first, signal sleeping barber ; wait for "chair is yours"
2. cut:   barber grants chair -> customer takes it -> barber cuts (sleep OUTSIDE lock) -> "done"
3. leave: customer signals "I left" -> barber proceeds to next / sleeps
```

Handshake flags (Ez version): `barberChairEmpty` → `hairCutDone` → `currentCustomerLeft`. Every
`await` is `while`-guarded, so a signal that fires before its waiter is parked is harmless.

## ⭐ The headline lesson — CONSUME the "your turn" token atomically

A shared boolean like `barberChairEmpty` is a **one-shot token**. The customer that wins it **must
clear it in the same lock hold, before any `await`**, or a second customer (new arrival *or* spurious
wakeup) also sees `true` and walks into the chair → **two customers in one chair**.

```java
while (!barberChairEmpty) customerWaitingCondition.await();
barberChairEmpty = false;      // ✅ CONSUME immediately — before getHairCut()'s await releases the lock
getHairCut();
```

- Reset it **late** (at end of haircut) ⇒ the token stays `true` for the whole cut ⇒ steal / double
  occupancy. This was the original Ez bug.
- Because check + consume happen under one continuous lock hold (no `await` between), only one
  customer can consume it. That also guarantees only one waiter on `hairCutDone` at a time.

> Benign leftover: between the barber releasing the lock and the *signaled* customer re-acquiring it,
> a brand-new customer can grab the lock and consume the token first. Still exactly one in the chair —
> just **not FIFO**. Fine for the unfair version.

## FIFO version — how it avoids the token problem entirely

`BarberShop.java` gives each customer its **own** `Condition` and the barber `poll()`s a **specific**
customer from a queue, signalling *that customer's* condition. No shared token to steal, and order is
guaranteed.

**Key invariant (why `poll()` never returns null):** `curCustomerCnt` is incremented at seating and
decremented in `getHairCut` *after* the barber polls. Transiently it can exceed `waitingList.size()`.
But the barber **blocks in `cutHair` until the current customer fully leaves**, and the customer
decrements *before* signalling "left". So by the next `poll()`, the served customer has already
decremented ⇒ `curCustomerCnt == waitingList.size()` ⇒ non-null poll. The one-at-a-time serialization
is what makes it safe.

| Aspect        | Ez (`BarberShopEz`)                | FIFO (`BarberShop`)                     |
| ------------- | ---------------------------------- | --------------------------------------- |
| Ordering      | unfair (newcomer can jump)         | strict FIFO                             |
| Turn signal   | one shared `barberChairEmpty` flag | per-customer `Condition` + queue `poll` |
| `signal` risk | must consume token atomically      | targeted, no shared token               |
| Complexity    | low                                | higher (per-customer maps/conditions)   |

## Capacity

- Balk when full; check + `customerInside++` in **one** critical section, else two customers race the
  last seat.
- Capacity here = `N` waiting chairs **+ 1** barber chair (customer in the chair still counts in
  `customerInside` until it decrements). `>= N+1` reads safer than `== N+1`.

## Things to keep in mind

- **Cut hair OUTSIDE the lock** (`sleep` between `unlock`/`lock`) — never hold a lock across slow work.
- **`while`-guard every `await`** so lost/early signals don't hang anyone.
- **Consume shared "turn" tokens atomically** (the headline lesson).
- One barber + one chair ⇒ single waiter per handshake condition ⇒ `signal()` is enough; if you
  generalize (Hilzer's multiple barbers), shared conditions break — use per-customer conditions.

## Correctness status

- **FIFO version (`BarberShop.java`): no steady-state bug.** No double-occupancy, correct FIFO
  order, and `poll()` is provably non-null. Its synchronization logic is clean.
- **Ez version (`BarberShopEz.java`): correct once the token is consumed atomically** (the fix
  above). Before that, it had the chair-steal / double-occupancy bug.

## Out-of-scope items (NOT correctness bugs — same for every infinite version in this repo)

These are deliberate "infinite demo" scope decisions, not flaws in either barbershop's logic:

- **Termination:** `while(true)` barber ⇒ `solve()`/`join()` won't self-terminate. For a bounded/test
  run, add a `done` flag checked in `while (curCustomerCnt == 0 && !done)` and wake the barber once
  all `M` customers are served/balked.
- **Interrupt mid-handshake:** *if* a customer is interrupted while awaiting the haircut, it dies
  without completing the handshake ⇒ the barber parks forever. Only triggerable by an interrupt
  (nothing in the demo does this). Unwind the handshake in a `finally` if you want interruptibility
  ("half a rendezvous" hazard).
- 🟡 Nit: make `lock` / `Condition` fields `final`; in Ez, drop the redundant second
  `barberChairEmpty = false`.

## 30-second recall

> One barber, `N` waiting chairs, balk when full. 3-phase rendezvous: wake barber → grant+cut
> (outside lock) → leave. **Consume the shared "your turn" flag atomically before any `await`** or two
> customers steal one chair. FIFO version = per-customer condition + queue `poll` (no shared token;
> `poll` non-null because the barber serves one at a time). `while`-guard everything. Remaining gaps:
> termination + interrupt-safe unwinding.
