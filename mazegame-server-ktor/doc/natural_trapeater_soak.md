# Natural auto-trapeater lifecycle observation

This opt-in test estimates how often normal auto-trapeater retirements leave a
retry job behind. It runs the production server with normal bait generation,
four test-only bait-seeking bots, three built-in `dummy` bots, and one TCP timing probe. The automatic
trapeater is spawned and despawned by the production handler. Its five-minute
minimum lifetime and random decisions use real wall-clock time.

This branch is based on the **pre-fix client**, so an abandoned retry remains
observable. It is separate from the small client fix PR. The runner does not
force a spawn, remove traps, accelerate cooldowns, or log out trapeaters itself.

## Run

Use Java 21. From the repository root:

```sh
./gradlew :mazegame-server-ktor:naturalTrapeaterSoak
```

The default duration is one hour. A startup smoke run checks wiring, but is too
short to say anything about despawn frequency:

```sh
./gradlew :mazegame-server-ktor:naturalTrapeaterSoak --args="--seconds=45 --sample-seconds=15"
```

Use `--args="--help"` for options. The default workload is seven moving bots
plus one probe, matching roughly eight active players. The test-only foragers
use the production A* pathfinder to seek visible non-trap baits; this lets traps
accumulate for the automatic trapeater. The probe turns in place. The game stays
at 150 ms. `--dummy-players=N` adjusts the mix without changing the total. The
Gradle task caps JVM heap at 512 MiB and reports two processors to the JVM;
`ActiveProcessorCount` does not physically cap CPU use.

Each run writes a timestamped directory under
`mazegame-server-ktor/build/reports/natural-trapeater/`:

- `events.csv`: observed spawn/despawn transitions, lifetime, trap counts at detection, and
  active child jobs 500 ms after the client disconnects.
- `snapshots.csv`: spawn/despawn totals, active retired jobs, trap counts, probe
  intervals, process CPU use, and heap use at regular intervals.
- `summary.txt`: observed counts and a 60-day extrapolation with a 95% Poisson
  sampling interval.
- `timingprobe-intervals.csv`: raw probe interval samples.
- `environment.txt`: runtime and workload settings.

The runner keeps only weak references to retired scopes, so it does not retain
clients that would otherwise be collectible. It cancels any surviving scopes
after measurement. Spawn/despawn transitions are sampled every 100 ms; trap
counts in `events.csv` are sampled at detection, not an atomic snapshot from
inside the server handler. A child-job count above zero after disconnect is
the practical leak signal; the earlier idle-only soak established that this
job is the retry loop.

The runner aborts if wall time and monotonic elapsed time differ by five seconds
or more. Sleep and large clock adjustments would make cooldowns and measured
elapsed time incomparable. Keep the host awake for the full run; on macOS, use
`caffeinate -i ./gradlew :mazegame-server-ktor:naturalTrapeaterSoak`.

## Interpreting a forecast

The extrapolation is `observed retirements with jobs / elapsed time × 60 days`.
The Poisson interval represents **counting uncertainty only**. It cannot
account for changes in player activity, bot strategies, map, bait settings,
server restarts, or time-of-day patterns. Fewer than ten natural despawns are
flagged as a weak sample. If none occur, the run cannot establish a useful
rate; extend the duration or repeat independent runs.

The bait-seeking foragers and dummy bot are proxies for the reported 7–8 bots.
Their bait collection and trap avoidance may differ from the actual server's
bots. This test measures normal production mechanics for this workload, not
the exact two-month history of another server.
