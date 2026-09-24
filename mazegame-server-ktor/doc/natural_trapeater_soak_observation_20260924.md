# Natural trapeater soak observation, 2026-09-24

This observation used the pre-fix client on `test/natural-trapeater-lifecycle`.
The local production server ran at 150 ms with default bait generation, automatic
trapeater spawning, four test-only bots seeking visible non-trap baits, three
built-in `dummy` bots, and one TCP timing probe. Normal spawn/despawn rules and
cooldowns were left intact.

The valid continuous segment lasted **2,346 seconds (39.1 minutes)**, through
the sixth natural despawn. It recorded **6 despawns and 1 retired trapeater
with a persistent active client job**. The job was still active at subsequent
minute snapshots. The probe produced 15,642 move intervals, averaging 153.0 ms
with p95 159.0 ms. Seven of 39 minute snapshots showed a negative server
`visibleTrapCount`; that counter feeds the auto-despawn rule and warrants
separate investigation.

| Despawn at (s) | Bot ID | Lifetime (s) | Active jobs after disconnect | Visible-trap counter at event logging |
| ---: | ---: | ---: | ---: | ---: |
| 619.8 | 9 | 300.1 | 0 | 0 |
| 954.9 | 10 | 329.7 | 0 | 0 |
| 1343.6 | 11 | 308.6 | 0 | 0 |
| 1694.7 | 12 | 313.2 | 0 | 0 |
| 2011.5 | 13 | 300.5 | **1** | -1 |
| 2346.2 | 14 | 322.9 | 0 | 0 |

This run's trap counts were read when each event was written, roughly 500 ms
after despawn detection. The test now captures the counts at detection for
future runs; either reading is still a sampled value, not the exact value
inside the server's random despawn decision.

At the observed whole-window rate, 60 uninterrupted days would produce about
**13,300 retirements and 2,200 persistent jobs**. This is a preliminary
extrapolation, not a precise forecast: only one leak was observed. An exact
95% Poisson count interval for the leaked-job rate scales to roughly **56 to
12,300 jobs** over 60 days. The interval covers sampling uncertainty only;
workload changes, the initial five-minute spawn gate, the negative trap
counter, and a different server's CPU are not included. The startup gate makes
the whole-window point estimate somewhat conservative for continuous play.

The planned one-hour run was invalidated when the laptop entered **clamshell
sleep** immediately after this segment. `caffeinate -i` prevented idle sleep
but could not prevent closed-lid sleep; the test's wall/monotonic clock guard
then aborted the run instead of writing a misleading one-hour summary. All
six despawns above preceded sleep. The raw local output is in
`build/reports/natural-trapeater/20260924-070620-481/` (ignored by Git).

To reproduce on an always-on host with Java 21, use:

```sh
./gradlew :mazegame-server-ktor:naturalTrapeaterSoak
```

The default is one hour with the same bot mix. For a narrower two-month rate,
run several hours or longer on the actual server or a comparable always-on
host, and compare the bot strategies and bait settings to the real workload.
