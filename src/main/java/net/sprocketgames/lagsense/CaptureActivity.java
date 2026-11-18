package net.sprocketgames.lagsense;

import java.util.*;

/** Sampled counts from the server, independent of which calls the profiler measured. */
public record CaptureActivity(int intervalTicks, Counts total, List<Dimension> dimensions) {
    public static final int INTERVAL_TICKS = 20;
    static final int MAX_DIMENSIONS = 256;

    public CaptureActivity {
        dimensions = List.copyOf(dimensions);
    }

    public boolean idle() {
        return total.samples() > 0 && total.peakPlayers() == 0 && total.peakTicking() == 0;
    }

    boolean valid(int ticks) {
        return intervalTicks > 0 && total.valid(ticks) && dimensions.size() <= MAX_DIMENSIONS
            && dimensions.stream().allMatch(d -> d.id() != null && d.id().length() <= 512 && d.counts().valid(total.samples()));
    }

    public record Counts(int samples, int peakPlayers, double averageLoaded, long peakLoaded,
                         double averageTicking, long peakTicking, long peakForced) {
        boolean valid(int maxSamples) {
            return samples > 0 && samples <= maxSamples && peakPlayers >= 0 && peakForced >= 0
                && peakTicking <= peakLoaded && averageTicking <= averageLoaded
                && averageTicking >= 0 && averageTicking <= peakTicking
                && averageLoaded >= 0 && averageLoaded <= peakLoaded;
        }
    }

    public record Dimension(String id, Counts counts) {}

    record Observation(String dimension, long loaded, long ticking, long forced) {}

    static final class Counter {
        private final Totals total = new Totals();
        private final Map<String, Totals> dimensions = new HashMap<>();

        void add(int players, List<Observation> observed) {
            long loaded = 0, ticking = 0, forced = 0;
            for (Observation level : observed) {
                loaded += level.loaded();
                ticking += level.ticking();
                forced += level.forced();
                Totals dimension = dimensions.get(level.dimension());
                if (dimension == null && dimensions.size() < MAX_DIMENSIONS && level.dimension().length() <= 512) {
                    dimension = new Totals();
                    dimensions.put(level.dimension(), dimension);
                }
                if (dimension != null) dimension.add(0, level.loaded(), level.ticking(), level.forced());
            }
            total.add(players, loaded, ticking, forced);
        }

        CaptureActivity snapshot() {
            if (total.samples == 0) return null;
            var breakdown = dimensions.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> new Dimension(entry.getKey(), entry.getValue().snapshot())).toList();
            return new CaptureActivity(INTERVAL_TICKS, total.snapshot(), breakdown);
        }
    }

    private static final class Totals {
        int samples, peakPlayers;
        long loadedSum, tickingSum, peakLoaded, peakTicking, peakForced;

        void add(int players, long loaded, long ticking, long forced) {
            samples++;
            peakPlayers = Math.max(peakPlayers, players);
            loadedSum += loaded;
            tickingSum += ticking;
            peakLoaded = Math.max(peakLoaded, loaded);
            peakTicking = Math.max(peakTicking, ticking);
            peakForced = Math.max(peakForced, forced);
        }

        Counts snapshot() {
            return new Counts(samples, peakPlayers, (double) loadedSum / samples, peakLoaded,
                (double) tickingSum / samples, peakTicking, peakForced);
        }
    }
}
