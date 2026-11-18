package net.sprocketgames.lagsense;

import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import java.util.ArrayList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.sprocketgames.lagsense.mixin.ChunkMapActivityAccessor;

/** Read-only observation of fully loaded holders and the server's block-tick eligibility. */
final class ServerActivitySampler {
    static void sample(MinecraftServer server, CaptureActivity.Counter counter) {
        var observed = new ArrayList<CaptureActivity.Observation>();
        boolean running = server.tickRateManager().runsNormally();
        for (var level : server.getAllLevels()) {
            var cache = level.getChunkSource();
            var holders = ((ChunkMapActivityAccessor) cache.chunkMap).lagsense$visibleChunks();
            var chunks = Long2ObjectMaps.fastIterator(holders);
            long loaded = 0, ticking = 0;
            while (chunks.hasNext()) {
                var entry = chunks.next();
                if (entry.getValue().getChunkIfPresent(ChunkStatus.FULL) == null) continue;
                loaded++;
                if (running && level.shouldTickBlocksAt(entry.getLongKey()) && entry.getValue().getTickingChunk() != null) ticking++;
            }
            observed.add(new CaptureActivity.Observation(level.dimension().identifier().toString(),
                loaded, ticking, level.getForceLoadedChunks().size()));
        }
        counter.add(server.getPlayerList().getPlayerCount(), observed);
    }

    private ServerActivitySampler() {}
}
