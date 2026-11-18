package net.sprocketgames.lagsense;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

final class ActivityReportingChecks {
    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final String URL = "https://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwx";

    static void run() throws Exception {
        var counter = new CaptureActivity.Counter();
        counter.add(2, List.of(new CaptureActivity.Observation("minecraft:overworld", 100, 40, 2),
            new CaptureActivity.Observation("minecraft:the_nether", 10, 3, 1)));
        counter.add(0, List.of(new CaptureActivity.Observation("minecraft:overworld", 20, 4, 2),
            new CaptureActivity.Observation("minecraft:the_nether", 30, 8, 1)));
        var active = counter.snapshot();
        check(active.total().averageLoaded() == 80 && active.total().peakLoaded() == 110
            && active.total().averageTicking() == 27.5 && active.total().peakTicking() == 43,
            "Server peaks are simultaneous totals, not the sum of per-dimension peaks");
        check(active.total().peakPlayers() == 2 && !active.idle(), "An end-of-capture disconnect preserves earlier activity");
        var idleCounter = new CaptureActivity.Counter();
        idleCounter.add(0, List.of(new CaptureActivity.Observation("minecraft:overworld", 4, 0, 0)));
        var idle = idleCounter.snapshot();
        check(idle.idle(), "Loaded chunks can be idle when no players or chunks tick");
        var directory = Files.createTempDirectory("lagsense-activity-check");
        try {
            var healthy = capture(1, 10, active, List.of(), 20);
            var statePath = directory.resolve("scheduled.json");
            var settings = settings(new ServerSettings.TimerDelivery(true, 250, 5, true, true), "json");
            var state = new ScheduledDiscord(statePath, ActivityReportingChecks::fail);
            check(state.decide(healthy, settings, 10) == ScheduledDiscord.Decision.SUPPRESS,
                "A healthy known capture is routine even if players were online");
            check(state.decide(capture(2, 11, idle, List.of(), 20), settings, 11) == ScheduledDiscord.Decision.IDLE,
                "A healthy transition into idle is noticed once");
            state.sent(settings, 11, ScheduledDiscord.Decision.IDLE);
            state.persist();
            state = new ScheduledDiscord(statePath, ActivityReportingChecks::fail);
            check(state.decide(capture(3, 12, idle, List.of(), 20), settings, 12) == ScheduledDiscord.Decision.SUPPRESS,
                "A restart does not repeat the idle notice");
            check(state.decide(capture(4, DAY + 11, idle, List.of(), 20), settings, DAY + 11) == ScheduledDiscord.Decision.HEARTBEAT,
                "A heartbeat becomes due after 24 quiet hours");
            state.sent(settings, DAY + 11, ScheduledDiscord.Decision.HEARTBEAT);
            state.persist();
            state = new ScheduledDiscord(statePath, ActivityReportingChecks::fail);
            check(state.decide(capture(5, DAY + 12, idle, List.of(), 20), settings, DAY + 12) == ScheduledDiscord.Decision.SUPPRESS,
                "A restart preserves the heartbeat interval");
            check(state.decide(capture(6, DAY + 13, idle, List.of(), 400), settings, DAY + 13) == ScheduledDiscord.Decision.REPORT,
                "A significant peak delivers even when average health is good");
            var machine = new Report.Entry("Block", "test:hot", "minecraft:overworld", 0, 64, 0, 1_200_000_000, 200);
            var expensive = capture(7, DAY + 14, active, List.of(machine), 20);
            check(state.decide(expensive, settings, DAY + 14) == ScheduledDiscord.Decision.REPORT, "New significant type delivers");
            state.sent(settings, DAY + 14, ScheduledDiscord.Decision.REPORT);
            check(state.decide(expensive, settings, DAY + 15) == ScheduledDiscord.Decision.SUPPRESS, "Repeated type is routine");

            var scoped = healthy.view(false, entry -> true);
            check(scoped.diagnostics() == null, "Base Health never receives server activity");
            check(FullReportWire.decode(FullReportWire.encode(healthy)).diagnostics().activity().equals(active),
                "Full report and archive data retain activity observations");
            var html = directory.resolve("activity.html");
            HtmlReportExport.write(html, healthy, net.sprocketgames.lagsense.client.UiTheme.DEFAULT);
            check(Files.readString(html).contains("\"averageTicking\":27.5"), "HTML exports real capture activity");

            var posted = new ArrayList<String>();
            try (var delivery = new DiscordDelivery(settings, (uri, body) -> {
                posted.add(new String(body.bytes(), StandardCharsets.UTF_8));
                return new DiscordDelivery.Response(204, 0);
            }, millis -> {}, ActivityReportingChecks::fail)) {
                delivery.capture(healthy, "");
                delivery.flush();
                check(posted.isEmpty() && delivery.status().contains("suppressed"), "Healthy timer skips payload and attachment");
                delivery.capture(capture(8, 14, idle, List.of(), 75), "");
                delivery.flush();
                check(posted.size() == 1 && posted.getFirst().contains("Idle · Degraded")
                    && posted.getFirst().contains("files[0]"), "Degradation still delivers with no measured targets");
                delivery.capture(capture(9, 15, idle, List.of(), 20), "");
                delivery.flush();
                check(posted.size() == 1, "Idle stays quiet after its transition was covered by a full alert");
            }
            var every = settings(new ServerSettings.TimerDelivery(false, 0, 0, false, false), "none");
            check(new ScheduledDiscord(null, ActivityReportingChecks::fail).decide(healthy, every, 20) == ScheduledDiscord.Decision.REPORT,
                "Admins can opt into all scheduled reports");
            check(ServerSettings.defaults().discord().timerPolicy().suppressRoutine(), "Existing configs default to routine suppression");
        } finally {
            try (var files = Files.list(directory)) {
                for (var file : files.toList()) Files.deleteIfExists(file);
            }
            Files.deleteIfExists(directory);
        }
        System.out.println("Activity reporting checks passed: sampled totals, private scope, archives, healthy/no-target alerts, hotspot notices and persisted idle/heartbeat decisions.");
    }

    private static Report capture(long id, long timestamp, CaptureActivity activity, List<Report.Entry> entries, long peakMs) {
        long work = peakMs == 75 ? 75 : 15;
        int overBudget = work == 75 ? 200 : peakMs > 50 ? 1 : 0;
        var performance = new CapturePerformance(10_000_000_000L, 200, peakMs * 1_000_000,
            overBudget, overBudget * 75_000_000L, 20, 10, "");
        var diagnostics = new Report.Diagnostics("Scheduled", 0, 0, List.of(), List.of(), List.of(),
            "disabled", CpuInvestigation.empty("disabled"), null, null, performance, activity);
        return new Report(id, timestamp, 200, work, true, false, entries, false, 0, Report.aggregate(entries), 20, diagnostics);
    }

    private static ServerSettings.Discord settings(ServerSettings.TimerDelivery policy, String attachment) {
        var delivery = new ServerSettings.Delivery(true, 0, 5, 10, 0, "either", "both", attachment, "", "degraded");
        return new ServerSettings.Discord(true, URL, "Activity fixture", delivery, delivery, delivery, "", policy);
    }

    private static void check(boolean expected, String context) {
        if (!expected) fail(context);
    }

    private static void fail(String context) {
        throw new AssertionError(context);
    }
}
