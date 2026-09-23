# Natural auto-trapeater lifecycle observation

This opt-in test estimates how often normal auto-trapeater retirements leave a
retry job behind. It runs the production server with normal bait generation,
seven moving built-in `dummy` bots, and one TCP timing probe. The automatic
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

Use `--args="--help"` for options. The default workload is seven dummy bots
plus one probe, matching roughly eight active players. The probe turns in place;
the dummy bots move and collect baits. The game stays at 150 ms.

Each run writes a timestamped directory under
`mazegame-server-ktor/build/reports/natural-trapeater/`:

- `events.csv`: observed spawn/despawn transitions, lifetime, trap counts, and
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
counts in `events.csv` are values at detection, not an atomic snapshot from
inside the server handler. A child-job count above zero after disconnect is
the practical leak signal; the earlier idle-only soak established that this
job is the retry loop.

## Interpreting a forecast

The extrapolation is `observed retirements with jobs / elapsed time × 60 days`.
The Poisson interval represents **counting uncertainty only**. It cannot
account for changes in player activity, bot strategies, map, bait settings,
server restarts, or time-of-day patterns. Fewer than ten natural despawns are
flagged as a weak sample. If none occur, the run cannot establish a useful
rate; extend the duration or repeat independent runs.

The built-in dummy bots are a proxy for the reported 7–8 players. Their bait
collection rate may differ from the actual server's bots. This test measures
normal production mechanics for this workload, not the exact two-month
history of another server.
