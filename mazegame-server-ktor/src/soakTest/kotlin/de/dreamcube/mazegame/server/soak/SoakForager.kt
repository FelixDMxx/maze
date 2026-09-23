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

import de.dreamcube.mazegame.client.maze.Bait
import de.dreamcube.mazegame.client.maze.events.BaitEventListener
import de.dreamcube.mazegame.client.maze.strategy.Bot
import de.dreamcube.mazegame.client.maze.strategy.SingleTargetAStar
import de.dreamcube.mazegame.common.maze.BaitType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock

/**
 * Test-only proxy for bait-seeking players. It follows visible, non-trap baits
 * using the production A* pathfinder, allowing traps to accumulate naturally.
 */
@Bot("soakforager")
class SoakForager : SingleTargetAStar(), BaitEventListener {
    override fun selectTarget() {
        val position = mazeClient.ownPlayerSnapshot.position
        val target = currentTarget?.takeIf { it in potentialTargets } ?: potentialTargets.minByOrNull {
            getManhattanDistance(position.x, it.x, position.y, it.y)
        }
        if (target != null) lockOnTarget(target)
    }

    override fun onBaitAppeared(bait: Bait) {
        if (bait.type == BaitType.TRAP) return
        runBlocking {
            accessMutex.withLock { potentialTargets.add(bait) }
        }
    }

    override fun onBaitVanished(bait: Bait) {
        runBlocking {
            accessMutex.withLock {
                potentialTargets.remove(bait)
                if (currentTarget == bait) clearTarget()
            }
        }
    }
}
