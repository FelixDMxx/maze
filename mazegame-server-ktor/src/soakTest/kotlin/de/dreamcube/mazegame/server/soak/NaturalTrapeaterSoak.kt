/*
 * Maze Game
 * Copyright (c) 2025-2026 Sascha Strauß
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.dreamcube.mazegame.server.soak

import com.sun.management.OperatingSystemMXBean
import de.dreamcube.mazegame.client.config.MazeClientConfigurationDto
import de.dreamcube.mazegame.client.maze.MazeClient
import de.dreamcube.mazegame.common.api.ConnectionDto
import de.dreamcube.mazegame.common.api.GameDto
import de.dreamcube.mazegame.common.api.GameSpeed
import de.dreamcube.mazegame.common.api.MazeServerConfigurationDto
import de.dreamcube.mazegame.common.maze.ConnectionStatus
import de.dreamcube.mazegame.server.maze.MazeServer
import de.dreamcube.mazegame.server.maze.server_bots.ClientWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.ref.WeakReference
import java.net.ServerSocket
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.exp
import kotlin.math.roundToInt

private const val USAGE = """Natural auto-trapeater soak (opt-in; not part of test/check).
Options use --name=value:
  --seconds=3600         Real wall-clock duration; cooldowns are not accelerated
  --players=7            Active bots, plus one timing probe
  --dummy-players=1      Of those bots, how many use the built-in dummy strategy
  --sample-seconds=60    Resource/timing snapshot interval
  --output-dir=PATH      Defaults to build/reports/natural-trapeater/<timestamp>

The server uses default bait generation, default 40 x 30 maze, auto-trapeater,
and 150 ms game speed. Run on the pre-fix branch to count abandoned retry jobs.
"""

private data class NaturalOptions(
    val seconds: Int,
    val players: Int,
    val dummyPlayers: Int,
    val sampleSeconds: Int,
    val outputDirectory: File
) {
    companion object {
        fun parse(args: Array<String>): NaturalOptions {
            val values = mutableMapOf<String, String>()
            for (arg in args) {
                val parts = arg.split('=', limit = 2)
                require(parts.size == 2 && parts[0] in setOf("--seconds", "--players", "--dummy-players", "--sample-seconds", "--output-dir")) {
                    "Unknown option '$arg'.\n$USAGE"
                }
                require(values.put(parts[0], parts[1]) == null) { "Duplicate option '${parts[0]}'" }
            }
            val seconds = (values["--seconds"] ?: "3600").toInt()
            val players = (values["--players"] ?: "7").toInt()
            val dummyPlayers = (values["--dummy-players"] ?: "1").toInt()
            val sampleSeconds = (values["--sample-seconds"] ?: "60").toInt()
            require(seconds > 0) { "Duration must be positive" }
            require(players in 1..20) { "Players must be between 1 and 20" }
            require(dummyPlayers in 0..players) { "Dummy players must be between 0 and total players" }
            require(sampleSeconds > 0) { "Sample interval must be positive" }
            val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC).format(Instant.now())
            val directory = File(values["--output-dir"] ?: "build/reports/natural-trapeater/$timestamp")
            require(!directory.exists() || directory.isDirectory) { "Output path must be a directory" }
            require(!File(directory, "events.csv").exists()) { "Output directory already contains events; choose a new directory" }
            return NaturalOptions(seconds, players, dummyPlayers, sampleSeconds, directory)
        }
    }
}

private data class ActiveTrapeater(val wrapper: ClientWrapper, val spawnedAtNs: Long)
private data class RetiredTrapeater(val scope: WeakReference<CoroutineScope>)

// The production client does not expose its caller scope. Reflection is confined to this opt-in
// diagnostic; it leaves both the production client and the tested lifecycle unchanged.
private val clientScopeField = MazeClient::class.java.getDeclaredField("scope").apply { isAccessible = true }
private fun clientScope(client: MazeClient): CoroutineScope = clientScopeField.get(client) as CoroutineScope
private fun childJobs(scope: CoroutineScope): Int = scope.coroutineContext[Job]?.children?.count() ?: 0

// Exact 95% Poisson count interval. The forecast still assumes a stable workload over 60 days.
private fun poissonCdf(count: Int, mean: Double): Double {
    var term = exp(-mean)
    var sum = term
    for (i in 1..count) {
        term *= mean / i
        sum += term
    }
    return sum
}

private fun poissonMeanAtCdf(count: Int, target: Double): Double {
    var low = 0.0
    var high = (count + 10).toDouble()
    while (poissonCdf(count, high) > target) high *= 2
    repeat(80) {
        val mid = (low + high) / 2
        if (poissonCdf(count, mid) > target) low = mid else high = mid
    }
    return (low + high) / 2
}

fun main(args: Array<String>) {
    if (args.contentEquals(arrayOf("--help"))) {
        println(USAGE)
        return
    }
    runBlocking { runSoak(NaturalOptions.parse(args)) }
}

private suspend fun runSoak(options: NaturalOptions) {
    check(options.outputDirectory.isDirectory || options.outputDirectory.mkdirs()) { "Cannot create output directory" }
    val events = File(options.outputDirectory, "events.csv")
    val snapshots = File(options.outputDirectory, "snapshots.csv")
    events.writeText("event,elapsed_s,bot_id,lifetime_s,current_traps,visible_traps,max_traps,active_jobs_after_disconnect\n")
    snapshots.writeText("elapsed_s,spawns,despawns,retirements_with_jobs,active_retired_jobs,current_traps,visible_traps,active_players,probe_samples,probe_mean_ms,probe_p95_ms,cpu_cores,heap_mb\n")
    File(options.outputDirectory, "environment.txt").writeText(
        "started=${Instant.now()}\njava=${System.getProperty("java.runtime.version")}\n" +
            "os=${System.getProperty("os.name")} ${System.getProperty("os.arch")}\n" +
            "jvmArgs=${ManagementFactory.getRuntimeMXBean().inputArguments}\n" +
            "seconds=${options.seconds}\nplayers=${options.players}\ndummyPlayers=${options.dummyPlayers}\nsampleSeconds=${options.sampleSeconds}\n" +
            "serverSpeedMs=150\nbaitGeneration=default\nautoTrapeater=true\n"
    )
    println("OUTPUT ${options.outputDirectory.absolutePath}")

    val port = ServerSocket(0).use { it.localPort }
    val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val server = MazeServer(
        MazeServerConfigurationDto(
            connection = ConnectionDto(port = port, maxClients = 100, loginTimeout = 30_000),
            game = GameDto(initialSpeed = GameSpeed.NORMAL, generateBaitsAtStart = true, autoTrapeater = true)
        ),
        serverScope
    )
    val players = mutableListOf<Pair<MazeClient, CoroutineScope>>()
    val retired = mutableListOf<RetiredTrapeater>()
    var probe: Probe? = null
    val os = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
    var spawns = 0
    var despawns = 0
    var retirementsWithJobs = 0
    var active: ActiveTrapeater? = null
    val started = System.nanoTime()
    var lastSnapshotNs = started
    var lastCpuNs = os.processCpuTime

    fun elapsedSeconds(now: Long): Double = (now - started) / 1e9
    fun event(kind: String, now: Long, id: Int, lifetime: Double?, jobs: Int?) {
        val row = listOf(
            kind, elapsedSeconds(now), id, lifetime ?: "", server.currentTrapCount.get(),
            server.visibleTrapCount.get(), server.maxTrapCount, jobs ?: ""
        ).joinToString(",")
        events.appendText("$row\n")
        println("EVENT $row")
    }

    fun snapshot(now: Long) {
        val currentProbe = checkNotNull(probe)
        val samples = currentProbe.between(lastSnapshotNs, now).sorted()
        val mean = if (samples.isEmpty()) Double.NaN else samples.average()
        val p95 = if (samples.isEmpty()) Double.NaN else samples[((samples.size - 1) * 0.95).roundToInt()]
        val cpuCores = (os.processCpuTime - lastCpuNs).toDouble() / (now - lastSnapshotNs)
        val activeJobs = retired.sumOf { it.scope.get()?.let(::childJobs) ?: 0 }
        val row = listOf(
            elapsedSeconds(now), spawns, despawns, retirementsWithJobs, activeJobs,
            server.currentTrapCount.get(), server.visibleTrapCount.get(), server.activePlayers.get(),
            samples.size, mean, p95, cpuCores,
            ManagementFactory.getMemoryMXBean().heapMemoryUsage.used / 1048576
        ).joinToString(",")
        snapshots.appendText("$row\n")
        println("SNAPSHOT $row")
        lastSnapshotNs = now
        lastCpuNs = os.processCpuTime
    }

    try {
        server.start().await()
        probe = Probe(port, "timingprobe")
        repeat(options.players) { index ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val strategy = if (index < options.players - options.dummyPlayers) "soakforager" else "dummy"
            val client = MazeClient(MazeClientConfigurationDto("127.0.0.1", port, strategy, false, "player$index"), scope)
            players.add(client to scope)
            client.start()
        }
        withTimeout(30_000) {
            while (players.any { it.first.status != ConnectionStatus.PLAYING }) delay(20)
        }
        println("READY foragers=${options.players - options.dummyPlayers} dummies=${options.dummyPlayers} probe=1 maxTraps=${server.maxTrapCount}")
        val deadline = started + options.seconds * 1_000_000_000L
        while (System.nanoTime() < deadline) {
            val now = System.nanoTime()
            val current = server.autoTrapeaterHandler.client as? ClientWrapper
            if (active?.wrapper !== current) {
                val departed = active
                if (departed != null) {
                    despawns++
                    val departedClient = departed.wrapper.client
                    val departedScope = clientScope(departedClient)
                    val stopped = withTimeoutOrNull(10_000) {
                        while (departedClient.status != ConnectionStatus.DEAD) delay(20)
                        true
                    } ?: false
                    check(stopped) { "Trapeater ${departedClient.id} did not disconnect after despawn" }
                    delay(500)
                    val jobs = childJobs(departedScope)
                    if (jobs > 0) retirementsWithJobs++
                    retired.add(RetiredTrapeater(WeakReference(departedScope)))
                    event("despawn", now, departedClient.id, (now - departed.spawnedAtNs) / 1e9, jobs)
                }
                active = current?.let { wrapper ->
                    spawns++
                    event("spawn", now, wrapper.client.id, null, null)
                    ActiveTrapeater(wrapper, now)
                }
            }
            if (now - lastSnapshotNs >= options.sampleSeconds * 1_000_000_000L) snapshot(now)
            delay(100)
        }
        snapshot(System.nanoTime())
        val elapsed = elapsedSeconds(System.nanoTime())
        val forecastScale = 60.0 * 24 * 60 * 60 / elapsed
        val forecast = retirementsWithJobs * forecastScale
        val forecastLow = if (retirementsWithJobs == 0) 0.0 else
            poissonMeanAtCdf(retirementsWithJobs - 1, 0.975) * forecastScale
        val forecastHigh = poissonMeanAtCdf(retirementsWithJobs, 0.025) * forecastScale
        val hasCompleteLifecycle = despawns > 0 && elapsed >= 600
        val summary = """
            elapsed_seconds=$elapsed
            spawns=$spawns
            despawns=$despawns
            retirements_with_active_jobs=$retirementsWithJobs
            projected_60_day_jobs_at_observed_rate=${if (hasCompleteLifecycle) forecast else "NA"}
            projected_60_day_jobs_poisson_95pct_low=${if (hasCompleteLifecycle) forecastLow else "NA"}
            projected_60_day_jobs_poisson_95pct_high=${if (hasCompleteLifecycle) forecastHigh else "NA"}
            forecast_quality=${when { !hasCompleteLifecycle -> "No complete natural lifecycle"; despawns < 10 -> "Too few natural despawns for a useful forecast"; else -> "Sampling interval only; workload drift is not covered" }}
            forecast_assumption=Stationary spawn/despawn and player activity over 60 days.
        """.trimIndent() + "\n"
        File(options.outputDirectory, "summary.txt").writeText(summary)
        println(summary)
    } finally {
        withContext(NonCancellable) {
            retired.forEach { it.scope.get()?.cancel() }
            players.forEach { (client, scope) ->
                try {
                    withTimeoutOrNull(3000) { client.logout() }
                } finally {
                    scope.cancel()
                }
            }
            probe?.close()
            try {
                withTimeoutOrNull(5000) { server.stop() }
            } finally {
                serverScope.cancel()
                probe?.export(options.outputDirectory)
            }
        }
    }
}
