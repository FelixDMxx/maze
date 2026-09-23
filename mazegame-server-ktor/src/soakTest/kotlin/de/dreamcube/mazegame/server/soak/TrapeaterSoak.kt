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
import kotlinx.coroutines.*
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.ref.WeakReference
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.math.roundToInt

private const val USAGE = """Trapeater soak test (opt-in; not part of test/check).
Options use --name=value:
  --checkpoints=5000,10000,17280,20000  Increasing successful retirement counts
  --sample-seconds=30                 Measurement duration per stage (minimum 3)
  --players=8                        Active TCP timing probes (1..20)
  --output-dir=PATH                   Defaults to build/reports/trapeater-soak/<timestamp>

Short run: --checkpoints=10 --sample-seconds=5
Both the server and retired bots use the normal 150 ms speed throughout.
"""

private data class Options(
    val checkpoints: List<Int>,
    val sampleSeconds: Int,
    val players: Int,
    val outputDirectory: File
) {
    companion object {
        fun parse(args: Array<String>): Options {
            val values = mutableMapOf<String, String>()
            for (arg in args) {
                val parts = arg.split('=', limit = 2)
                require(parts.size == 2 && parts[0] in setOf("--checkpoints", "--sample-seconds", "--players", "--output-dir")) {
                    "Unknown option '$arg'.\n$USAGE"
                }
                require(values.put(parts[0], parts[1]) == null) { "Duplicate option '${parts[0]}'" }
            }
            val checkpoints = (values["--checkpoints"] ?: "5000,10000,17280,20000").split(',').map { it.toInt() }
            require(checkpoints.all { it > 0 } && checkpoints.zipWithNext().all { (a, b) -> a < b }) {
                "Checkpoints must be positive and strictly increasing"
            }
            val seconds = (values["--sample-seconds"] ?: "30").toInt()
            require(seconds >= 3) { "Sample duration must be at least 3 seconds" }
            val players = (values["--players"] ?: "8").toInt()
            require(players in 1..20) { "Players must be between 1 and 20" }
            val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC).format(Instant.now())
            val directory = File(values["--output-dir"] ?: "build/reports/trapeater-soak/$timestamp")
            require(!directory.exists() || directory.isDirectory) { "Output path must be a directory" }
            require(!File(directory, "results.csv").exists()) { "Output directory already contains results; choose a new directory" }
            return Options(checkpoints, seconds, players, directory)
        }
    }
}

/** Uses a dedicated thread so probe scheduling does not depend on the server's coroutine dispatcher. */
internal class Probe(port: Int, val name: String) : AutoCloseable {
    private val socket = Socket("127.0.0.1", port).also {
        it.tcpNoDelay = true
        it.soTimeout = 60_000
    }
    private val samples = Collections.synchronizedList(mutableListOf<Pair<Long, Double>>())
    @Volatile var playerId = -1
        private set
    @Volatile var position: Pair<Int, Int>? = null
        private set
    @Volatile var failure: Exception? = null
        private set
    @Volatile private var running = true
    private val worker = thread(name = name, isDaemon = true) {
        try {
            val writer = socket.getOutputStream().bufferedWriter()
            fun send(message: String) {
                writer.write("$message\n")
                writer.flush()
            }
            var previous = 0L
            socket.getInputStream().bufferedReader().useLines { lines ->
                for (line in lines) {
                    when {
                        line.startsWith("MSRV;") -> send("HELO;$name")
                        line.startsWith("WELC;") -> {
                            playerId = line.split(';')[1].toInt()
                            send("MAZ?")
                        }
                        line.startsWith("PPOS;") -> {
                            val fields = line.split(';')
                            if (fields[1].toInt() == playerId) {
                                position = fields[2].toInt() to fields[3].toInt()
                            }
                        }
                        line == "RDY." -> {
                            val now = System.nanoTime()
                            if (previous != 0L) samples.add(now to (now - previous) / 1e6)
                            previous = now
                            send("TURN;r")
                        }
                        line.startsWith("INFO;45") -> error("$name received protocol error: $line")
                    }
                }
            }
            check(!running) { "$name connection closed unexpectedly" }
        } catch (ex: Exception) {
            if (running) {
                failure = ex
                ex.printStackTrace()
            }
        }
    }

    fun between(start: Long, end: Long): List<Double> = synchronized(samples) {
        samples.filter { it.first > start && it.first <= end }.map { it.second }
    }

    fun export(directory: File) {
        synchronized(samples) {
            File(directory, "$name-intervals.csv").bufferedWriter().use { writer ->
                writer.appendLine("monotonic_ns,interval_ms")
                samples.forEach { writer.appendLine("${it.first},${it.second}") }
            }
        }
    }

    override fun close() {
        running = false
        socket.close()
        worker.join(2000)
    }
}

// Bookkeeping must not itself keep retired clients/scopes alive.
private data class Retired(val client: WeakReference<MazeClient>, val scope: WeakReference<CoroutineScope>)

fun main(args: Array<String>) {
    if (args.contentEquals(arrayOf("--help"))) {
        println(USAGE)
        return
    }
    val options = Options.parse(args)
    runBlocking { runSoak(options) }
}

private suspend fun runSoak(options: Options) {
    check(options.outputDirectory.isDirectory || options.outputDirectory.mkdirs()) { "Cannot create output directory" }
    val out = File(options.outputDirectory, "results.csv")
    out.writeText("stage,retired,active_client_jobs,samples,mean_ms,p50_ms,p95_ms,max_ms,ui_lifetime_ms,cpu_cores,heap_mb,threads,gc_ms\n")
    // The production server accepts a port number, not a pre-bound socket.
    val port = ServerSocket(0).use { it.localPort }
    val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val server = MazeServer(
        MazeServerConfigurationDto(
            connection = ConnectionDto(port = port, maxClients = 100, loginTimeout = 30_000),
            game = GameDto(initialSpeed = GameSpeed.NORMAL, generateBaitsAtStart = false, autoTrapeater = true, delayCompensation = true)
        ),
        serverScope
    )
    val retired = mutableListOf<Retired>()
    val probes = mutableListOf<Probe>()
    val os = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
    val gc = ManagementFactory.getGarbageCollectorMXBeans()
    File(options.outputDirectory, "environment.txt").writeText(
        "started=${Instant.now()}\njava=${System.getProperty("java.runtime.version")}\n" +
            "os=${System.getProperty("os.name")} ${System.getProperty("os.arch")}\n" +
            "jvmArgs=${ManagementFactory.getRuntimeMXBean().inputArguments}\n" +
            "players=${options.players}\ncheckpoints=${options.checkpoints}\nsampleSeconds=${options.sampleSeconds}\n"
    )
    println("OUTPUT ${options.outputDirectory.absolutePath}")

    suspend fun uiTime(): Double {
        val probe = probes.first()
        val result = CompletableDeferred<Result<Double>>()
        server.commandExecutor.addCommand {
            result.complete(runCatching {
                val (x, y) = checkNotNull(probe.position) { "Probe has not received its position" }
                val player = checkNotNull(server.getPlayerAt(x, y)) { "Probe player is missing" }
                check(player.id == probe.playerId)
                player.moveTime
            })
        }
        return withTimeout(30_000) { result.await().getOrThrow() }
    }

    suspend fun measure(stage: String) {
        delay(5000)
        val start = System.nanoTime()
        val cpu = os.processCpuTime
        val gcStart = gc.sumOf { it.collectionTime.coerceAtLeast(0) }
        delay(options.sampleSeconds * 1000L)
        val end = System.nanoTime()
        val cpuCores = (os.processCpuTime - cpu).toDouble() / (end - start)
        val allSamples = probes.associateWith { probe ->
            check(probe.failure == null) { "${probe.name} failed: ${probe.failure}" }
            probe.between(start, end).sorted().also {
                check(it.size >= 10) { "${probe.name} produced only ${it.size} intervals; use a longer sample or inspect the server" }
            }
        }
        val samples = allSamples.getValue(probes.first())
        fun percentile(p: Double) = samples[((samples.size - 1) * p).roundToInt()]
        val jobs = retired.sumOf { it.scope.get()?.coroutineContext?.get(Job)?.children?.count() ?: 0 }
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used / 1048576
        val row = listOf(
            stage, retired.size, jobs, samples.size, samples.average(), percentile(.5), percentile(.95), samples.last(),
            uiTime(), cpuCores, heap, ManagementFactory.getThreadMXBean().threadCount,
            gc.sumOf { it.collectionTime.coerceAtLeast(0) } - gcStart
        ).joinToString(",")
        out.appendText("$row\n")
        println("RESULT $row")
        println("LIVE_RETIRED ${retired.count { it.client.get() != null }}")
        println("ALL_PROBES " + allSamples.entries.joinToString { (probe, times) -> "${probe.name}=${times.average()}ms" })
    }

    var attempts = 0
    var failures = 0
    suspend fun churnTo(target: Int) {
        var consecutiveFailures = 0
        while (retired.size < target) {
            val attempt = attempts++
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val client = MazeClient(MazeClientConfigurationDto("127.0.0.1", port, "trapeater", false, "retired$attempt"), scope)
            var phase = "start"
            var successfullyRetired = false
            try {
                client.start()
                phase = "login"
                withTimeout(10_000) { while (client.status != ConnectionStatus.PLAYING) delay(5) }
                // Allow the production Trapeater to enter its no-target retry before logout.
                delay(10)
                phase = "logout"
                client.logout()
                withTimeout(10_000) { while (client.status != ConnectionStatus.DEAD) delay(5) }
                retired.add(Retired(WeakReference(client), WeakReference(scope)))
                successfullyRetired = true
                consecutiveFailures = 0
            } catch (ex: TimeoutCancellationException) {
                failures++
                consecutiveFailures++
                println("CHURN_RETRY attempt=$attempt phase=$phase status=${client.status} retired=${retired.size} failures=$failures")
                check(consecutiveFailures < 3) { "Repeated connection failures; stopping rather than skewing results" }
            } finally {
                if (!successfullyRetired) {
                    withContext(NonCancellable) {
                        try {
                            withTimeoutOrNull(3000) { client.logout() }
                        } finally {
                            scope.cancel()
                        }
                    }
                }
            }
            if (!successfullyRetired) {
                delay(100)
                continue
            }
            if (retired.size % 250 == 0) println("CHURN retired=${retired.size} attempts=$attempts failures=$failures")
        }
    }

    try {
        server.start().await()
        repeat(options.players) { probes.add(Probe(port, "timingprobe$it")) }
        delay(5000)
        measure("baseline")
        for (count in options.checkpoints) {
            churnTo(count)
            measure("retired_$count")
        }
        retired.forEach { it.scope.get()?.cancel() }
        measure("same_process_after_cancellation")
    } finally {
        withContext(NonCancellable) {
            retired.forEach { it.scope.get()?.cancel() }
            probes.forEach { it.close() }
            try {
                withTimeoutOrNull(5000) { server.stop() }
            } finally {
                serverScope.cancel()
                // Preserve raw timing data even if a stage fails.
                probes.forEach { it.export(options.outputDirectory) }
                File(options.outputDirectory, "churn.txt").writeText("attempts=$attempts\nretired=${retired.size}\nfailures=$failures\n")
            }
        }
    }
    println("DONE ${out.absolutePath}")
}
