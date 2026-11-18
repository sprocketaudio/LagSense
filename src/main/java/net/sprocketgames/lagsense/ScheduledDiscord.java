package net.sprocketgames.lagsense;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/** Worker-owned delivery history for one world's scheduled notifications. */
final class ScheduledDiscord {
    enum Decision { REPORT, IDLE, HEARTBEAT, SUPPRESS }
    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final int STATE_LIMIT = 32 * 1024;
    private static final int MAX_HOTSPOTS = 10;

    private record Hotspot(String kind, String type, String dimension) {}
    private record State(String webhookId, long lastMessage, long observedAt, Boolean idle,
                         boolean idlePending, List<Hotspot> notifiedHotspots) {}

    private final Path path;
    private final Consumer<String> log;
    private State saved, state;
    private List<Hotspot> currentHotspots = List.of();
    private boolean loaded, saveFailed;

    ScheduledDiscord(Path path, Consumer<String> log) {
        this.path = path;
        this.log = log;
    }

    private void open(ServerSettings.Discord settings, long now) {
        if (!loaded) {
            loaded = true;
            if (path != null && Files.isRegularFile(path)) {
                try {
                    if (Files.size(path) > STATE_LIMIT) throw new IOException("State too large");
                    saved = Profiler.JSON.fromJson(Files.readString(path), State.class);
                    if (saved != null && saved.notifiedHotspots() != null
                        && saved.notifiedHotspots().size() <= MAX_HOTSPOTS) state = saved;
                } catch (IOException | RuntimeException error) {
                    log.accept("Could not read Discord scheduled delivery history");
                }
            }
        }
        // A webhook ID identifies the destination without storing its secret.
        var webhook = java.net.URI.create(settings.webhookUrl()).getPath().split("/");
        String webhookId = webhook[webhook.length - 2];
        if (state == null || !webhookId.equals(state.webhookId())) {
            state = new State(webhookId, now, 0, null, false, List.of());
            currentHotspots = List.of();
        }
    }

    Decision decide(Report report, ServerSettings.Discord settings, long now) {
        open(settings, now);
        var policy = settings.timerPolicy();
        var diagnostics = report.diagnostics();
        var activity = diagnostics == null ? null : diagnostics.activity();
        var severity = ReportSeverity.evaluate(report);
        var performance = diagnostics == null ? null : diagnostics.performance();
        boolean significantPeak = performance != null && policy.significantPeakMs() > 0
            && performance.peakNanos() / 1e6 >= policy.significantPeakMs();

        currentHotspots = state.notifiedHotspots();
        if (report.capturedAt() >= state.observedAt()) {
            Boolean idle = activity == null ? state.idle() : Boolean.valueOf(activity.idle());
            boolean idlePending = policy.idleNotice() && Boolean.TRUE.equals(idle)
                && (Boolean.FALSE.equals(state.idle()) || state.idlePending());
            if (!report.truncated()) currentHotspots = hotspots(report, policy.hotspotMsPerTick());
            var stillPresent = state.notifiedHotspots().stream().filter(currentHotspots::contains).toList();
            state = new State(state.webhookId(), state.lastMessage(), report.capturedAt(), idle, idlePending, stillPresent);
        }

        if (!policy.suppressRoutine() || severity != ReportSeverity.HEALTHY || activity == null || significantPeak
            || !state.notifiedHotspots().containsAll(currentHotspots)) return Decision.REPORT;
        if (state.idlePending() && activity.idle()) return Decision.IDLE;
        return policy.dailyHeartbeat() && now - state.lastMessage() >= DAY ? Decision.HEARTBEAT : Decision.SUPPRESS;
    }

    void sent(ServerSettings.Discord settings, long now, Decision decision) {
        open(settings, now);
        boolean observedReport = decision == Decision.REPORT;
        state = new State(state.webhookId(), now, state.observedAt(), state.idle(),
            decision == null && state.idlePending(), observedReport ? currentHotspots : state.notifiedHotspots());
    }

    void persist() {
        if (path == null || state == null || state.equals(saved)) return;
        Path temporary = null;
        try {
            Files.createDirectories(path.getParent());
            temporary = Files.createTempFile(path.getParent(), ".discord-scheduled-", ".tmp");
            ReportFiles.json(temporary, state, Profiler.JSON, STATE_LIMIT);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException error) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
            saved = state;
            saveFailed = false;
        } catch (IOException | RuntimeException error) {
            if (!saveFailed) log.accept("Could not save Discord scheduled delivery history");
            saveFailed = true;
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
        }
    }

    private static List<Hotspot> hotspots(Report report, double minimum) {
        if (minimum <= 0 || report.ticks() <= 0) return List.of();
        var groups = new HashMap<Hotspot, Long>();
        for (var entry : report.entries()) {
            if (entry.nanos() > 0) groups.merge(new Hotspot(entry.kind(), entry.type(), entry.dimension()), entry.nanos(), Long::sum);
        }
        double threshold = minimum * 1e6 * report.ticks();
        return groups.entrySet().stream().filter(entry -> entry.getValue() >= threshold)
            .sorted(Map.Entry.<Hotspot, Long>comparingByValue().reversed()
                .thenComparing(entry -> entry.getKey().kind()).thenComparing(entry -> entry.getKey().type())
                .thenComparing(entry -> entry.getKey().dimension()))
            .limit(MAX_HOTSPOTS).map(Map.Entry::getKey).toList();
    }
}
