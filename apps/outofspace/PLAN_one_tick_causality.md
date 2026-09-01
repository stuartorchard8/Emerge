# One-tick causality

Status: **built and green** (2026-09-01). One real change — `Work.settled`, which stops a store
passing on what it has only just been handed. Everything else in the plan below turned out to be
either already true or a derivation that is deliberately exempt; both are now pinned by tests rather
than left as incidental. `OneTickCausalityTest` is the guard.

One rule, applied everywhere:

> Nothing in a tick reads *work*. Every pass reads the state emitted by last tick, and writes to
> work. A thing that happened this tick is not observable until next tick.

The signal network already works this way and is the template — `state.signals` is read, `nextSignals`
is written, and they swap at the end of the tick. The one-tick lag between a sensor firing and the
machine it drives noticing is not an artifact of the wire; it is this rule, showing through in the
one place it is currently enforced.

Expected consequences, all of them wanted:

- A storage that receives a packet holds it for a tick instead of emitting it from its output port
  in the same tick.
- A rail that receives a packet from a machine does not move it on in the same step.
- A bridge that takes a packet into its third slot keeps it there for a step.

## The machinery is already there

`Work(state)` deep-copies `deck`, `buffers`, `rail` and `tracks` on entry (`StuffLayer.copyOf`
copies every array), and `state` stays alive for the whole tick. **Both buffers already exist.** The
change is not an allocation or a new data structure — it is redirecting reads from `w.buffers` to
`state.buffers` at the points where a pass is *deciding* something, while writes go on landing in
`w`. That makes this far cheaper than it sounds, and it means the work can be done a layer at a time
with the two halves coexisting.

Sizing: ~22 buffer writes, ~25 deck writes, ~13 rail writes in a 3,753-line `OutofspaceSim.kt`.

## Already conformant

More than expected:

- **Signals** — the reference implementation, described above.
- **Staggered subsystems** — pump, heat, chemistry, pressure and fluid fire on *different ticks*
  (`PUMP_OFFSET`…`FLUID_OFFSET`), so they already communicate through state across a tick boundary.
  The cross-subsystem case is largely solved; what is left is *within* a pass.
- **`advanceSegments`** — hand-rolls this rule locally with `arrived: BooleanArray`, and its own doc
  already states the principle: "A packet moves one tile per advance, and that has to be true of the
  *packet* rather than of the walk." It even notes that such a tile "does not offer the new arrival
  to its own port until next pass".
- **Bridges** — `depositFromBridge` drains before `advanceBridges` shuffles the slots, so a packet
  moved into a slot is not deposited until the next step. The suspicion that the bridge is already
  fine looks correct; what is seen is likely the storage case below.
- **Heat** — `w.heatAdded[tile]` accumulates through the tick and is applied in a single pass at the
  end. This is the correct pattern for anything with multiple writers, and is already in place.

## Actual violations

1. **A storage emits what it just received.** `advanceRails`'s absorb callback writes into the
   buffer; `pushOut` runs immediately after, in the same block, and reads that buffer through
   `bufferFor`. This is the named case, confirmed.
2. **`structure` is re-derived mid-tick** — `if (w.solidityChanged) structure = StructureMap.derive(…)`.
   ⚠️ **Deliberate, and deferring it re-opens a bug already hit**: the comment says the fluid step
   would otherwise pour air back into the tile a new casing has just emptied. This one needs a
   decision, not a mechanical change.
3. **Deconstruction mutates the graph it is walking** — `scrapDeconstructing`/`scrapMachines` change
   `rails` mid-pass, and the flow graph is rebuilt in the same step when `railCount()` changed.
4. **Machine dispatch** — `w[tile] = …` writes machine state that later passes in the same tick read
   back through `deck[…]`.
5. **`leech`** reads the product buffer to decide whether to bite and writes the same buffer.

## The two real hazards

### Conservation is the sharp one

"Take from A, give to B" becomes: read A from state, write `A − x`, write `B + y`. If two passes both
draw on one source while reading last-tick state, **both see the full amount and both take it, and
mass is created**. `Mixture.take` being the only exact draw makes this precise rather than
approximate, and the ledger has four terms that will disagree loudly.

Every contended source needs a single owner per tick, or an explicit reservation. The mass-balance
harness is the safety net, and it is right if it disagrees.

### Accumulators must stay additive

Anything with multiple writers must accumulate into work, never last-writer-wins on a shared cell,
or deposits are silently dropped. Heat already does this; `extractedMass`, the ledgers and `motion`
are the same shape and need the same treatment.

## Throughput: latency, not rate

The concern is right, and the arithmetic is worse than it first looks because **`RAIL_PERIOD = 32`**.

- Rails already move one tile per advance, so the **hop rate does not change at all**.
- What changes is each *handoff* at a boundary — machine→rail, rail→machine, bridge→rail. Each gains
  one full rail step, which is **32 ticks, not one tick**. A line with N boundary crossings gains
  32N ticks.
- ⚠️ That is **latency, not throughput**. The line is pipelined, so steady-state rate is unchanged;
  what grows is the time for the first gram to arrive. Worth keeping the two words apart when
  judging whether it feels wrong in play.

⛔ **The emit hazard is real and specific.** `pushOut` already runs only inside the rail gate, so
machines emit onto track once per 32 ticks *today* — "every tick" is not the current behaviour. The
danger is that the new rule pushes the port's read one tick behind the machine's production write,
so a machine emits every *other* rail step — 64 ticks, halving output. Avoiding it means the
production write and the port's emit read stay one tick apart, not two. Sequencing within the tick
still matters under the new rule; the rule says what may be *read*, not that ordering stops counting.

## Suggested order

Hard shape first — the case that decides whether the idea survives, not the easy plumbing.

1. **Buffers.** The storage case, and where conservation bites. If double-buffered buffers cannot be
   made to conserve, the whole idea is in question and every later step is wasted work.
2. **Rail layer.** The handoff boundaries. Measure the latency change against a known line before and
   after, so the 32-tick steps are a number rather than a feeling.
3. **Deck / machine state.** Mostly mechanical once 1 and 2 hold.
4. **Derived state.** Decide the `solidityChanged` question explicitly.

## What was built

1. **Buffers — the one substantive change.** `Work.before` holds the state the tick copied from and
   is never written; `Work.settled(store)` is `min(before, now)` — what a store held when the tick
   began *and* still holds. `pushOut` caps what it may hand over by it. Decisions read `before`; the
   arithmetic still runs against the live layer, which is what keeps mass exact.
2. **Rail boundaries — already conformant, now guarded.** `pushOut` runs after `advanceRails`, and
   `advanceSegments` keeps its own `arrived` flag, so neither machine→rail nor rail→rail could ever
   act twice in a step. Two tests pin it, because it holds today by the order of two calls.
3. **Machine state — no change needed, and this was checked rather than assumed.** The machines pass
   runs before the rails pass, so the rail block reads machine state from earlier in the tick only
   for `kind` and `wiring`, neither of which the machines pass alters. The auto-lock a warehouse
   applies on delivery cannot change a decision in its own step either: `acceptInto` refuses only on
   "full" and "not a solid", and the whitelist that a filter feeds is built before any delivery
   happens. ⚠️ `acceptInto` reading the *live* buffer to check "full" is correct and must stay —
   two deliveries into one tank in one step have to see each other, or they would both find it empty
   and overfill it. That is the accounting side of the rule, not a violation of it.
4. **Derived state — exempt, deliberately and narrowly.** See the note at the `solidityChanged`
   re-derive in `OutofspaceSim`. `structure` is recomputed from scratch each tick and holds nothing
   between ticks, so it is not state that was *emitted*. Making a finished casing wait a tick was
   considered and rejected: the passes that move air run earlier, so the wall would appear after air
   had been let back into its tile, and the building would then have to displace air that only
   arrived because the building was not yet there — the same problem with an extra tick of wrongness
   in front of it.

### The two the scope listed and this did not change

Both were reclassified on closer reading rather than quietly dropped.

- **Deconstruction rebuilding the flow graph mid-pass** (violation 3) is the same category as
  `structure`: the graph is a derivation, rebuilt from scratch when the tile set changes and holding
  nothing between ticks. Exempt for the reason recorded above, not overlooked. What matters for
  causality is that no *matter* moves twice, and `advanceSegments` guarantees that independently.
- **`leech` reading its own product buffer** (violation 5) is conformant in practice: the machines
  pass runs before the rails pass, so what it reads is what last tick's `pushOut` left. It reads a
  store that nothing else has written this tick.

## What did not change

Throughput. The `min(before, now)` shape means a store hands on what it was *already* holding, so a
tank with a queue still feeds the run every step — a step of latency, not a halved rate. This is
pinned separately, because the obvious wrong implementation (a tile that sits out a step after any
delivery) passes the headline test and fails this one.

Machine emission is likewise unaffected in practice: production lands in a store during the machines
pass, which runs every tick, while `pushOut` fires every `RAIL_PERIOD`. Only material produced on a
rail tick itself waits for the following step.

## Open questions

- **`pushOut` runs only on rail ticks**, so machines put material on track once per 32 ticks. Left
  as it was — it predates this work and is a separate dial.

- **Other derivations** — `occupancy`, `apertures`, the flow graph — are exempt by the same
  reasoning as `structure`, but only `structure` re-derives mid-tick today, so only it needed saying.
