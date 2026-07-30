# Cyto agent scripts

Scripted playthroughs for the agent harness. Run one with:

```
./gradlew :apps:cyto:desktop:cytoAgent --args="apps/cyto/agent-scripts/<name>.txt /tmp/cyto-agent"
```

`expect` lines make these **tests**, not recordings: a failed expectation prints `EXPECT FAIL` and the run
exits non-zero. A script that only *narrates* stops being checked the moment nobody reads its output.

| script | what it covers |
|---|---|
| `campaign-genesis-divide-conversion.txt` | `ch00-genesis` → `ch01-divide` end to end through the UI, taking the fuel pair that **conflicts** with the growth chemical → branches to `ch02-conversion` |
| `campaign-genesis-divide-photosynthesis.txt` | the same run, taking a **non-conflicting** pair → branches to `ch02-photosynthesis` |
| `campaign-eager-player.txt` | a player who selects the cell and opens its chemistry **before** the beats that ask for it — the state-gated goals must arrive already satisfied |
| `rehomed-insert.txt` | a group insert (`+ ADD HOLD TOGETHER`) rebinding onto the player's own chemistry |
| `campaign-spotlight.txt` | Genesis's spotlit beats — the coach's box + connector, and that every label it points at is still tappable, and that the ring hands off to Next once the task is done |
| `spotlight-labels.txt` | The gene-card words the coach rings that no walkthrough taps — BREAK, Import, SEVERING |
| `matter-layers.txt` | the LAYERS sheet's per-species matter layers — the rows are there, named, and change what the ground draws |

The two differ only in the reaction chosen at the last step, which is the whole point: the branch is decided
by that choice and nothing else.

## Writing one

Every player action goes through the real UI (`tap-ui`, `tap`, `dragcell`) rather than a back door, so these
break when the campaign's copy, gates or gene-card labels change — which is what makes them useful.

Two things that will bite:

- **`@n`, not `#n`, to pick the n-th match.** `#` starts a comment, so `tap-ui USE LIGHT #2` silently becomes
  `tap-ui USE LIGHT` and edits the *first* gene while reporting success. Every gene shows its own `ALWAYS`
  and `USE LIGHT`, so indices matter.
- **A pick sheet is modal.** While one is open, `tap-ui` can only reach the sheet — as a player can only
  click the sheet. Close it (`tap-ui close`) before touching the card again.
