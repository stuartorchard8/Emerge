# Working in this repo

Rules of engagement for coding agents. `README.md` has the repo tour — this file is about conduct.
Read it before your first edit and follow it over your own defaults.

## The gate

```
./gradlew test                      # everything
./gradlew :apps:outofspace:core:jvmTest   # one module, much faster — use this while iterating
```

A change is not done until the gate is green. Run it yourself; do not report success you have not
observed. Test times land in `.build/<module>/test-results/**/TEST-*.xml`.

## The rule that matters most: never move a test's expectation

If a test asserts `12` and you observe `17`, you have found **information**, not an obstacle.

- **Do not** edit the expected value.
- **Do not** rewrite the assertion into something weaker that passes — replacing a specific
  expectation with a search for "whatever happens to be there" destroys the test while leaving it
  green. This is the single most damaging thing an agent does here.
- **Do not** add a mechanism whose purpose is to make the failure go away.
- **Do** stop and report: what you expected, what you observed, and your best account of why.

A fix that makes the suite pass is not a diagnosis that is right. A moved number gets a question.

## Scope

Build what was asked, and nothing else. If the task cannot be completed without some capability
that was not requested, that is a finding to report — not a licence to add it. Finish the parts you
can, and say plainly which parts you did not do and why.

If you find a second problem while working on the first, note it and keep going. Do not chase it.

## Specs are contracts, not suggestions

Plan documents in this repo (`docs/*.md`, `apps/*/PLAN_*.md`) argue for their constraints at length,
and the argument is the point. When a plan says "not X" and gives three paragraphs of reasons, "not
X" is a requirement. Before you finish, re-read the section covering what you built and check each
constraint it states against what you actually wrote.

Where a plan and a test disagree, stop and ask. Do not pick one.

## Conservation and ledgers

The sims here maintain exact identities — mass, energy, momentum, air, rock. Several are named
`*Balance` on the state. They are load-bearing: they hold at zero, on every tick, exactly.

- Never relax, skip, or widen a balance assertion to get a build green.
- If your change makes a balance break, the change is wrong until proven otherwise.
- Anything discarded, vented or destroyed must be **booked** to the term that accounts for it.
- Watch for values that are constructor defaults — they silently recompute on a `data class` `copy()`
  and will rebase a baseline without any error. Pass them through explicitly.

## Git

- Commit directly to `main`. No feature branches.
- One focused commit per change, with a message that says what changed and why.
- **Never `git stash`.** It has destroyed long-lived stashes here. To split a working tree, copy
  files to a scratch directory instead.

## Tests

- No test may take more than 5 seconds. If yours does, reformulate it.
- A quantity only ever exercised at one value has not been tested.
- Prefer an assertion that pins the real intent over one that merely passes.

## Kotlin Multiplatform

Code in `commonMain`/`commonTest` compiles for JS as well as JVM. JVM-only APIs (`Map.merge`,
`computeIfAbsent`, `String.format`) compile on JVM and **fail on JS**. If common code fails to
resolve in one place but not another, compile a non-JVM target before blaming the toolchain.

## When you are stuck

Report. A clear account of where you got to, what you tried, and what you observed is a good
outcome. Silently working around the blockage is not.
