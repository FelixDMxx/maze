# Trapeater retirement soak test

This opt-in experiment measures how disconnected trapeater retry loops affect real server turn timing. It runs the production server and clients over TCP, accelerates bot retirements, then cancels abandoned work without restarting the server to measure recovery.

## Run

Use Java 21, as required by the project. From the repository root:

```sh
./gradlew :mazegame-server-ktor:trapeaterSoak
```

The default run uses eight active timing probes, checkpoints at 5,000, 10,000, 17,280, and 20,000 successful retirements, and 30-second measurement windows. Expect several minutes of runtime and substantial CPU use at higher counts. It is deliberately **not** included in `test` or `check`.

A short smoke run exercises the full setup, retirement, measurement, cancellation, and shutdown sequence:

```sh
./gradlew :mazegame-server-ktor:trapeaterSoak --args="--checkpoints=10 --sample-seconds=5"
```

Customize the population, sampling duration, and number of active probes:

```sh
./gradlew :mazegame-server-ktor:trapeaterSoak --args="--checkpoints=1000,5000,10000 --sample-seconds=30 --players=8"
```

Use `--args="--help"` for all options. Checkpoints must be positive and strictly increasing. The runner requires at least ten measured intervals per probe per window; increase `--sample-seconds` when investigating very severe delays.

## Outputs

Each run creates a new timestamped directory under `mazegame-server-ktor/build/reports/trapeater-soak/`, printed as `OUTPUT` at startup:

- `results.csv`: mean, median, p95, maximum interval, retired population, active client child jobs, CPU consumption, heap use, thread count, GC time, and the UI's cumulative `Player.moveTime` statistic. Timing columns describe the first probe; all probes must remain healthy.
- `timingprobe*-intervals.csv`: raw monotonic timestamps and ready-message intervals for every probe, including warmup and churn.
- `environment.txt`: Java/OS details, JVM arguments, and experiment parameters.
- `churn.txt`: attempted connections, successful retirements, and failed attempts.

The console prints checkpoint results, all-probe means, and failed connection attempts. Raw timing files and churn counts are also written on failure once the server lifecycle has started.

Set `--output-dir=PATH` to choose another directory. Relative paths are resolved from the server module. The runner refuses to overwrite a directory containing `results.csv`.

The Gradle task uses a 512 MB maximum heap and `-XX:ActiveProcessorCount=2`, matching the recorded investigation. **ActiveProcessorCount is not a physical CPU cap**: the JVM can consume more than two cores. Change the task's JVM settings if testing a different deployment configuration; record those settings with your results.

## What the experiment does

1. Starts a fresh `MazeServer` on a temporary port. The production server uses its normal bind behavior; the test clients connect over loopback.
2. Connects independent TCP probes on dedicated threads. Each sends `TURN;r` immediately after `RDY.`. Turning in place avoids wall crashes, collisions, strategy computation, and intentional idle time affecting the probe's move average.
3. Repeatedly connects the real `MazeClient` using the real `Trapeater` strategy, allows its no-target retry to start, and logs it out. Retirements are sequential. Failed lifecycle attempts are cleaned up, logged, and excluded; three consecutive failures abort the experiment.
   The harness checks that client shutdown leaves its caller-provided scope active.
4. At each checkpoint, pauses churn, settles for five seconds, and measures latency and resource use. The initial warmup is also five seconds.
5. Cancels retired clients' coroutine scopes, measures again in the same server process, then closes the probes and stops the server.

The server and retired bots stay at **150 ms** throughout. This version does not accelerate their retry interval. Only the rate of connection/retirement cycles is accelerated.

The maze uses the default 40 × 30 generator. Bait generation and random events are disabled to isolate the idle retry leak. Auto-trapeater is enabled, but without traps it does not spawn naturally; the harness explicitly creates and retires real trapeater clients instead of waiting for automatic cooldowns.

Retired clients and scopes are tracked through weak references. The harness itself therefore does not keep them alive. The active-job metric counts direct children of retired client scopes, predominantly retry jobs; it can transiently include lifecycle jobs. It is not a count obtained from coroutine stack inspection. No assertion requires a hardware-dependent slowdown.

The runner now fails if retired clients still have active jobs after a measurement window. The historical measurements below were recorded before this fix, when the runner reported those jobs without failing.

## Recorded investigation

The [aggregate measurements](performance/trapeater-soak-2026-09-23.csv) were recorded on September 23, 2026 against production commit `d3d0470b10ed644b3426dc839e6823c886a6bc6b`, using Temurin Java 21 on a macOS laptop. The original standalone harness used the same workload and JVM settings. The repository runner adds Gradle integration, configurable checkpoints, output metadata, and validation of all probes.

| Condition | Mean interval | p95 | Maximum | CPU core-equivalents |
|---|---:|---:|---:|---:|
| Fresh server | 153.6 ms | 155.3 ms | 155.5 ms | 0.066 |
| 5,000 retired bots | 156.4 ms | 176.3 ms | 312.2 ms | 2.579 |
| 10,000 retired bots | 166.5 ms | 232.0 ms | 326.6 ms | 4.012 |
| 17,280 retired bots | 196.2 ms | 361.0 ms | 549.7 ms | 6.293 |
| 20,000 retired bots | 211.5 ms | 382.1 ms | 542.2 ms | 7.280 |
| Same process after cancellation | 153.4 ms | 155.4 ms | 155.8 ms | 0.064 |

All eight probes showed similar means. Five stalled logins were cleaned up and excluded from the 20,000 successful retirements. CPU core-equivalents are process CPU time divided by elapsed wall time: 1.0 means one core's worth of CPU time.

The result demonstrates a causal latency and CPU problem: accumulating abandoned jobs increased load, and cancelling them restored timing without a restart. It did **not** reproduce a sustained 300 ms average on this laptop. An older or smaller server may exhaust its CPU capacity sooner, but this measurement cannot establish its exact latency.

The 17,280 count corresponds to 60 days at one retirement every five minutes **if each retirement leaves an idle loop**. Actual automatic cycles can be longer, and not every retirement necessarily leaves a loop. This is a strong test of the suspected mechanism, not a full simulation of two months of gameplay or arbitrary bot strategies.

The UI's `Player.moveTime` is cumulative elapsed time divided by counted moves. It does not represent just the latest measurement window. Its recorded value reached 168.6 ms before cancellation and remained 167.87 ms afterward, even though current TCP intervals recovered to 153.4 ms.
