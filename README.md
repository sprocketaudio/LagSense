# LagSense

**Find what's slowing down your Minecraft server, directly in-game.**

LagSense is a performance profiler for Minecraft NeoForge, inspired by LagGoggles,
Spark and Observable. It combines detailed server profiling with world
visualisation to help locate and investigate performance problems.

## Features

### Help players investigate their own bases

Players can inspect reports captured by server admins or automatic captures.
Open Parties and Claims can restrict player results to their permitted claims.
Admins can also define public profiling areas. Players cannot start server
captures or change server settings.

### Visualise lag directly in-game

View measured entities, blocks and chunks in the world, including through walls.
Inspect timings, follow moving entities and explore hotspots using the chunk
heatmap. Captured timings remain fixed until another profile is taken.

### More than ticking entities

Investigate directly measured entity, block entity, scheduled tick, random tick
and NeoForge event-handler timings. Explore sampled CPU activity and Java call
paths on the server thread and background threads. Group results by mod, type or
individual target.

### Capture, save and compare

Run profiles manually, schedule them or trigger captures after sustained lag
spikes. Save profile history and compare captures to investigate improvements
and slowdowns. Saved reports remain available after server restarts.

### Discord reporting

Send performance summaries containing TPS, MSPT, severity, measured contributors
and chunks. Configure role mentions by severity and optionally attach one complete
HTML, JSON or CSV report. New configurations default to three results per Timer
section and five per Spikes/Manual section, showing both individuals and combined
types. Timer, Spikes and Manual all default to HTML attachments.
Existing saved preferences are preserved. If a file cannot be generated or exceeds
the fixed 20 MiB attachment limit or Discord's accepted size, the summary still sends
with an attachment notice.

Discord and offline HTML reports show server activity alongside performance.
LagSense observes loaded and block-tick-eligible chunks on the first and final
captured ticks and every 20 ticks in between. The averages and peaks describe
those observations. Force-loaded counts come from the server's forced-ticket
set. HTML includes a per-dimension breakdown. A capture with zero measured
targets can still show active chunks.

Scheduled Discord delivery suppresses routine healthy reports by default.
Captures and configured local history continue. Warnings, degradation, large
tick peaks and newly significant groups can still trigger a message, subject to
the scheduled delivery cooldown. Configure these rules in Discord's Settings tab
or the server TOML. Turn off Suppress routine to send every scheduled report.
Idle transition notices and the 24-hour quiet heartbeat are optional and off by
default. Spike delivery defaults to a 300-second cooldown for matching severity,
trigger kind and leading measured areas. Significant peak notes identify a tick
spike separately from the capture's overall health rating.

Reports can also be exported as standalone interactive HTML files that work
offline, without an external website or account. In-game reports include search,
grouping, historical comparisons, hotspot markers and operator-only teleport tools.
Three UI themes and configurable HUD positioning are included.

## Compatibility

| Requirement | Version |
| --- | --- |
| Minecraft | 26.3 |
| NeoForge | 26.3.0.113 or a compatible later 26.3 build |
| Java | 25 |
| Optional Open Parties and Claims | 0.33.0 for NeoForge 26.3 |

For FTB Chunks and FTB Teams, use the Minecraft 26.1.2 build of LagSense.

Install LagSense on the server for profiling and on clients for report screens
and overlays. Use the same LagSense version on both sides. Single-player requires
one installation. Claim integrations are optional and are not bundled.

## Getting started

1. Install LagSense and start Minecraft or the server.
2. As an operator, run `/lagsense profile` to capture 30 seconds of server activity.
3. Open `/lagsense report` to investigate results. Other players receive only the
   results they are permitted to view.
4. Open Settings to configure captures, Discord delivery and profiling options.
   Server settings are restricted to operators.

| Default shortcut | Action |
| --- | --- |
| Ctrl+F9 | Open the profiler and restore temporarily hidden visuals |
| Ctrl+F10 | Start or stop profiling as an operator; open permitted results otherwise |
| Ctrl+F11 | Open settings |
| Ctrl+F12 | Hide or restore performance visuals |

Shortcuts can be remapped in Minecraft Controls. Hiding visuals preserves each
individual display preference. Public-area creation helpers remain visible.

## Commands

| Command | Purpose |
| --- | --- |
| `/lagsense report` | Open the latest permitted results, using saved history if needed |
| `/lagsense profile [seconds]` | Start an operator capture, from 5 to 120 seconds |
| `/lagsense stop` | Finish a running capture early |
| `/lagsense status` | Show capture and automation status |
| `/lagsense reload` | Reload the server configuration |
| `/lagsense schedule every <minutes> [seconds]` | Configure scheduled captures |
| `/lagsense schedule off` | Disable scheduled captures |
| `/lagsense spikes on` | Enable configured spike detection |
| `/lagsense spikes off` | Disable spike detection |
| `/lagsense discord test` | Send a test notification to the configured webhook |
| `/lagsense discord send` | Send the latest capture using manual-delivery settings |
| `/lagsense return` | Return to the position before the last profiler teleport |

Capture, automation, configuration, Discord and teleport commands require operator
access. Opening a report does not start a capture.

### Public areas

Operators can share captured results from an area with every player:

```text
/lagsense area pos1
/lagsense area pos2
/lagsense area create area1
```

Stand at opposite corners when setting pos1 and pos2. Areas cover the selected
X/Z footprint across the dimension's full build height. Use `/lagsense area show`
or `/lagsense area hide` to inspect boundaries. `/lagsense area delete` removes
the area you are standing in; overlapping areas require a name.

Public areas grant permission to view results, not to run captures or teleport.
Claim ownership and membership are checked when results are requested. Where
claim providers overlap, all claiming providers must permit access unless the
area has been explicitly shared by an administrator.

## Configuration and measurements

Server settings are stored in `config/lagsense-server.toml`. Client display
preferences are stored in `config/lagsense-client.json`. Operator settings can be
edited in-game. Use `/lagsense reload` after editing server configuration on disk.

Scheduled captures, spike detection, history and Discord delivery are opt-in.
Spike captures measure activity after detection, so they cannot recover a hitch
that has already finished. Timers skip captures while another capture is running.

Direct timings cover supported calls across all ticking loaded chunks and
loaded dimensions. Non-ticking chunks produce no measurements. CPU activity and
call paths are execution samples, not exact timings. Event-handler timings are
inclusive; the separate coverage calculation counts overlapping execution once.
Background-thread samples do not contribute to server MSPT.

Capture and transfer budgets protect server and client memory. Reports identify
incomplete captures, and oversized transfers or exports fail explicitly rather
than silently removing results. HTML, JSON and CSV exports include all available
retained data and can contain coordinates, entity UUIDs and server diagnostics.

## License

LagSense is licensed under the [MIT License](LICENSE).
Bundled Noto fonts retain their [SIL Open Font License](src/main/resources/assets/lagsense/font/OFL-Noto.txt).
Font sources are listed in [SOURCES.txt](src/main/resources/assets/lagsense/font/SOURCES.txt).
