package net.sprocketgames.lagsense;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Builds a bounded notification DTO. Administrator-configured webhooks receive the complete capture. */
public final class DiscordMessage {
    public record Row(String kind, String type, String dimension, int targets, double msPerTick, double percent, Integer x, Integer y, Integer z) {}
    public record Message(String json) {
        @Override public String toString() { return "DiscordMessage[content hidden]"; }
    }
    private record Key(String kind, String type, String dimension, int x, int y, int z) {}
    public static List<Row> rows(Report report,ServerSettings.Delivery settings){return rows(report,settings.groupBy().equals("type")?"type":"target");}
    private static List<Row> rows(Report report,String grouping) {
        if (grouping.equals("target")) return report.entries().stream().map(e -> {
            double ms = e.nanos() / 1_000_000.0 / Math.max(1, report.ticks());
            return new Row(e.kind(), e.type(), e.dimension(), 1, ms, report.mspt() > 0 ? ms / report.mspt() * 100 : 0,
                e.x(), e.y(), e.z());
        }).sorted(Comparator.comparingDouble(Row::msPerTick).reversed().thenComparing(Row::type).thenComparing(Row::dimension)).toList();
        Map<Key, List<Report.Entry>> grouped = new LinkedHashMap<>();
        for (var e : report.entries()) {
            boolean type = grouping.equals("type");
            Key key = new Key(e.kind(), e.type(), e.dimension(), type ? 0 : e.x(), type ? 0 : e.y(), type ? 0 : e.z());
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }
        List<Row> result = new ArrayList<>();
        for (var group : grouped.entrySet()) {
            Key key = group.getKey(); long nanos = group.getValue().stream().mapToLong(Report.Entry::nanos).sum();
            double ms = nanos / 1_000_000.0 / Math.max(1, report.ticks());
            double percent = report.mspt() > 0 ? ms / report.mspt() * 100 : 0;
            boolean positioned = grouping.equals("target");
            result.add(new Row(key.kind(), key.type(), key.dimension(), group.getValue().size(), ms, percent,
                positioned ? key.x() : null, positioned ? key.y() : null, positioned ? key.z() : null));
        }
        return result.stream().sorted(Comparator.comparingDouble(Row::msPerTick).reversed().thenComparing(Row::type).thenComparing(Row::dimension)).toList();
    }
    public static boolean qualifies(Row row, ServerSettings.Delivery settings) {
        if (!Double.isFinite(row.msPerTick()) || row.msPerTick() <= 0) return false;
        boolean ms = settings.minimumMs() > 0, percent = settings.minimumPercent() > 0;
        if (!ms && !percent) return true;
        boolean a = row.msPerTick() >= settings.minimumMs(), b = row.percent() >= settings.minimumPercent();
        return settings.match().equals("both") ? (!ms || a) && (!percent || b) : ms && a || percent && b;
    }
    public static Message build(Report report, ServerSettings.Discord connection, ServerSettings.Delivery settings, Gson gson) {
        return build(report, connection, settings, gson, false);
    }
    static Message build(Report report, ServerSettings.Discord connection, ServerSettings.Delivery settings, Gson gson, boolean scheduled) {
        if(!report.admin()||report.recording())return null;
        boolean individual=!settings.groupBy().equals("type"),types=!settings.groupBy().equals("target");
        var individuals=individual?rows(report,"target").stream().filter(r->qualifies(r,settings)).limit(settings.top()).toList():List.<Row>of();
        var grouped=types?rows(report,"type").stream().filter(r->qualifies(r,settings)).limit(settings.top()).toList():List.<Row>of();
        if(!scheduled&&individuals.isEmpty()&&grouped.isEmpty()&&(settings.minimumMs()>0||settings.minimumPercent()>0))return null;
        String origin=origin(report);
        var severity=ReportSeverity.evaluate(report);
        var components=new ArrayList<Map<String,Object>>();
        String role=mention(settings,severity)?settings.roleId():"";
        String heading="## LagSense | "+origin+" Capture";
        if(!connection.serverName().strip().equalsIgnoreCase("LagSense"))heading+="\n"+safe(connection.serverName(),80);
        if(!role.isEmpty())heading+="\n<@&"+role+">";
        components.add(text(heading));
        components.add(separator());
        var performance=report.diagnostics()==null?null:report.diagnostics().performance();
        boolean measured=performance!=null&&performance.valid();
        String average=measured?format(performance.averageTps(),1):report.tps()>0?format(Math.min(20,report.tps()),1):"N/A";
        String lowest=measured&&performance.windows()>0?format(performance.lowestTps(),1):"N/A";
        String duration=measured?format(performance.durationSeconds(),1)+" seconds":"N/A";
        String timestamp=report.capturedAt()>0?java.time.format.DateTimeFormatter.ofPattern("d MMMM uuuu, HH:mm 'UTC'",Locale.ENGLISH).withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.ofEpochMilli(report.capturedAt())):"Not recorded";
        String details="**Performance**\nAverage TPS: **"+average+" / 20**\nLowest TPS: **"+lowest+"**\nAverage MSPT: **"+format(report.mspt(),1)+" ms**\nPeak MSPT: **"+(measured?format(performance.peakNanos()/1e6,1):"N/A")+" ms**";
        double peakThreshold=connection.timerPolicy().significantPeakMs();
        if(measured&&peakThreshold>0&&performance.peakNanos()/1e6>=peakThreshold)
            details+="\nTick spike observed: **"+format(performance.peakNanos()/1e6,1)+" ms**. Contributor costs below are capture averages.";
        if(measured&&severity==ReportSeverity.WARNING&&performance.windows()>0&&performance.lowestTps()<18)
            details+="\nA brief TPS dip contributes to the Warning rating.";
        var activity = report.diagnostics()==null ? null : report.diagnostics().activity();
        details += "\n\n" + activitySummary(activity, severity) + "\n\n**Capture information**\nCapture duration: "+duration+"\nCaptured: "+timestamp+"\nProfile ID: "+report.id();
        if(origin.equals("Spike")&&measured&&!performance.triggerReason().isBlank())details+="\n"+safe(performance.triggerReason(),300);
        components.add(text(details));
        var notes=captureWarnings(report);
        String note=notes.isEmpty()?"":safe(String.join("\n",notes),600);
        var sections=new LinkedHashMap<String,List<String>>();
        if(individual)sections.put(settings.groupBy().equals("both")?"Top measured individuals":"Top measured contributors",contributorLines(individuals,false));
        if(types)sections.put(settings.groupBy().equals("both")?"Top measured types":"Top measured contributors",contributorLines(grouped,true));
        var chunkLines=Report.aggregate(report.entries()).stream().filter(chunk->chunk.micros(report.ticks())>0).limit(settings.top()).map(chunk->cost(chunk.micros(report.ticks())/1000)+" ms/t · "+dimension(chunk.dimension())+" chunk "+chunk.x()+", "+chunk.z()).toList();
        if(!chunkLines.isEmpty())sections.put("Top measured chunks",chunkLines);
        // Keep every configured summary entry inside Discord's text budget. Full identities stay in the attachment.
        int used=heading.length()+details.length()+note.length();
        int overhead=sections.keySet().stream().mapToInt(name->name.length()+6).sum();
        int lines=sections.values().stream().mapToInt(List::size).sum();
        int lineLimit=Math.max(1,(3900-used-overhead)/Math.max(1,lines)-1);
        for(var section:sections.entrySet())components.add(text("**"+section.getKey()+"**\n"+String.join("\n",section.getValue().stream().map(line->summaryEntry(line,lineLimit)).toList())));
        if(!note.isEmpty())components.add(text(note));
        if(!settings.attachment().equals("none")){
            components.add(separator());
            components.add(Map.of("type",13,"file",Map.of("url","attachment://"+filename(report,settings.attachment()))));
        }
        return message(components,severity.color,role,connection.avatarUrl(),gson);
    }
    private static String activitySummary(CaptureActivity activity, ReportSeverity severity) {
        if (activity == null) return "**Server Activity**\nUnknown · **" + severity.label + "**";
        var counts = activity.total();
        return "**Server Activity**\nOnline players (peak): **" + counts.peakPlayers() + "**\nLoaded chunks (avg / peak): **"
            + format(counts.averageLoaded(), 1) + " / " + counts.peakLoaded() + "**\nTicking chunks (avg / peak): **"
            + format(counts.averageTicking(), 1) + " / " + counts.peakTicking() + "**\nForce-loaded chunks (peak): **"
            + counts.peakForced() + "**\n" + counts.samples() + " observations · " + activity.intervalTicks()
            + "-tick sampling · sampled peaks.\n**"
            + (activity.idle() ? "Idle" : "Active") + " · " + severity.label + "**";
    }
    static Message scheduledNotice(ServerSettings.Discord settings, Report report, ScheduledDiscord.Decision decision, Gson gson) {
        String detail = decision == ScheduledDiscord.Decision.IDLE
            ? "Server activity went idle. The latest scheduled capture was healthy."
            : "Scheduled captures are running. No performance notification was sent in the last 24 hours.";
        return message(List.of(text("## LagSense | Scheduled Capture\n" + safe(settings.serverName(),80) + "\n" + detail)),
            ReportSeverity.evaluate(report).color, "", settings.avatarUrl(), gson);
    }
    static List<String> captureWarnings(Report report){
        var notes=new LinkedHashSet<String>();var diagnostics=report.diagnostics();
        if(report.truncated())notes.add(diagnostics!=null&&diagnostics.notes().stream().anyMatch(n->n.startsWith("Capture stopped:"))?"Capture stopped early because its memory limit was reached.":"Some target measurements are missing from this capture.");
        if(diagnostics!=null){
            diagnostics.notes().stream().filter(n->n.startsWith("Backup")).forEach(notes::add);
            var cpu=diagnostics.cpu();var background=diagnostics.background();
            if(cpu.state().equals("unavailable"))notes.add("Server-thread CPU sampling was unavailable for this capture.");
            if(diagnostics.callbackState().equals("unavailable"))notes.add("Event timings were unavailable for this capture.");
            else if(diagnostics.notes().stream().anyMatch(n->n.startsWith("Event results limited:")||n.startsWith("Event timings limited:")||n.equals("Detailed callback limit reached.")))notes.add("Some event timings could not be recorded.");
            if(missingPaths(cpu))notes.add("Some server-thread CPU call paths could not be retained.");
            if(diagnostics.notes().contains("CPU results limited: some of the sampling period is missing because recording data was lost or reached its storage limit."))notes.add("Part of the CPU sampling period is missing.");
            if(background!=null){
                if(background.state().equals("unavailable"))notes.add("Background CPU sampling was unavailable for this capture.");
                if(background.omittedSamples()>0||background.threads().stream().anyMatch(t->missingPaths(t.cpu())))notes.add("Some background CPU samples or call paths could not be retained.");
            }
        }
        return List.copyOf(notes);
    }
    private static boolean missingPaths(CpuInvestigation cpu){return cpu!=null&&cpu.limits()!=null&&(cpu.limits().omittedPaths()>0||cpu.limits().omittedEdges()>0);}
    private static List<String> contributorLines(List<Row> rows,boolean combined){
        if(rows.isEmpty())return List.of("No measured contributors pass the filters.");
        return rows.stream().map(row->{
            String context=combined?targetCount(row):"("+row.x()+", "+row.y()+", "+row.z()+")";
            String measurement=String.format(Locale.ROOT,"%s ms/t%s · %.1f%% · %s",cost(row.msPerTick()),combined?" total":"",row.percent(),safe(row.type(),70));
            return dimension(row.dimension())+" · "+context+"\n"+measurement;
        }).toList();
    }
    private static String targetCount(Row row){
        boolean singular=row.targets()==1;
        String unit=switch(row.kind()){
            case "Entity"->singular?"entity":"entities";
            case "Block","Scheduled block","Random block"->singular?"block":"blocks";
            case "Scheduled fluid","Random fluid"->singular?"fluid location":"fluid locations";
            default->singular?"target":"targets";
        };
        return row.targets()+" "+unit;
    }
    private static String summaryEntry(String entry,int limit){
        int newline=entry.indexOf('\n');
        if(newline<0)return safe(entry,limit);
        String context=entry.substring(0,newline),measurement=entry.substring(newline+1);
        int separator=context.indexOf(" · ");
        String detail=context.substring(separator+3);
        int contextBudget=Math.max(detail.length()+5,limit/2);
        if(context.length()>contextBudget)context=safe(context.substring(0,separator),contextBudget-detail.length()-3)+" · "+detail;
        // Dense messages keep two bounded lines, prioritising coordinates or contributor counts.
        if(context.length()>=limit-1)context=safe(context,Math.max(1,limit/2));
        return context+"\n"+safe(measurement,Math.max(1,limit-context.length()-1));
    }
    private static String cost(double milliseconds){return milliseconds>0&&milliseconds<.001?"<0.001":format(milliseconds,3);}
    private static String dimension(String id){return switch(id){case "minecraft:overworld"->"Overworld";case "minecraft:the_nether"->"Nether";case "minecraft:the_end"->"End";default->safe(id,60);};}
    static boolean mention(ServerSettings.Delivery settings,ReportSeverity severity){
        if(settings.roleId()==null||!settings.roleId().matches("[0-9]{16,22}"))return false;
        return switch(settings.mentionSeverity()){
            case "always"->true;
            case "critical"->severity==ReportSeverity.CRITICAL;
            case "degraded"->severity==ReportSeverity.DEGRADED||severity==ReportSeverity.CRITICAL;
            case "warning"->severity==ReportSeverity.WARNING||severity==ReportSeverity.DEGRADED||severity==ReportSeverity.CRITICAL;
            default->false;
        };
    }
    private static Map<String,Object> text(String value){return Map.of("type",10,"content",value);}
    private static Map<String,Object> separator(){return Map.of("type",14,"divider",true,"spacing",1);}
    private static Message message(List<Map<String,Object>> components,int color,String role,String avatarUrl,Gson gson){
        Map<String,Object> mentions=new LinkedHashMap<>();mentions.put("parse",List.of());if(!role.isEmpty())mentions.put("roles",List.of(role));
        var payload=new LinkedHashMap<String,Object>();payload.put("flags",32768);payload.put("components",List.of(Map.of("type",17,"accent_color",color,"components",components)));payload.put("allowed_mentions",mentions);if(!avatarUrl.isEmpty())payload.put("avatar_url",avatarUrl);return new Message(gson.toJson(payload));
    }
    public static Message test(ServerSettings.Discord connection,Gson gson){return message(List.of(text("## LagSense | Test notification\n"+safe(connection.serverName(),80)+"\nWebhook connected. No profile data attached.")),ReportSeverity.UNKNOWN.color,"",connection.avatarUrl(),gson);}
    private static String safe(String value,int length){if(value==null)return "";String clean=value.replace("@","@\u200b").replace('\r',' ').replace('\n',' ').replace('`','\'');return clean.length()<=length?clean:clean.substring(0,length-1)+"…";}
    static Message withoutAttachment(Message message,String notice,Gson gson){
        var payload=com.google.gson.JsonParser.parseString(message.json()).getAsJsonObject();
        var components=payload.getAsJsonArray("components").get(0).getAsJsonObject().getAsJsonArray("components");
        for(int i=components.size()-1;i>=0;i--)if(components.get(i).getAsJsonObject().get("type").getAsInt()==13){
            components.remove(i);
            if(i>0&&components.get(i-1).getAsJsonObject().get("type").getAsInt()==14){components.remove(i-1);i--;}
        }
        // Leave room for the attachment notice within Discord's shared text budget.
        int remaining=4000-notice.length();
        var iterator=components.iterator();
        while(iterator.hasNext()){
            var part=iterator.next().getAsJsonObject();
            if(part.get("type").getAsInt()!=10)continue;
            if(remaining<=0){iterator.remove();continue;}
            String content=part.get("content").getAsString();
            if(content.length()>remaining)content=content.substring(0,remaining-1)+"…";
            part.addProperty("content",content);remaining-=content.length();
        }
        components.add(gson.toJsonTree(text(notice)));
        payload.remove("attachments");
        return new Message(gson.toJson(payload));
    }
    public static String origin(Report report){String trigger=report.diagnostics()==null?"":report.diagnostics().trigger();return trigger.equals("Scheduled")?"Scheduled":trigger.equals("Spike follow-up")?"Spike":trigger.equals("Manual")?"Manual":"Unknown";}
    public static String filename(Report report){
        String stamp=java.time.format.DateTimeFormatter.ofPattern("yyMMdd-HHmmss",Locale.ROOT).withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.ofEpochMilli(Math.max(0,report.capturedAt())));
        // Lossless base-36 ID avoids collisions from truncated hashes, including same-second captures.
        return "LagSense-"+stamp+"-"+Long.toUnsignedString(report.id(),36)+".html";
    }
    public static String filename(Report report,String format){if(!Set.of("html","json","csv").contains(format))throw new IllegalArgumentException("Invalid report format");return filename(report).replace(".html","."+format);}
    private static String format(double value,int places){return Double.isFinite(value)?String.format(Locale.ROOT,"%."+places+"f",value):"N/A";}
    public static final class Body implements AutoCloseable {
        private final String contentType;private final byte[] bytes;private final java.nio.file.Path file;
        private final Map<String,Long> expected;
        Body(String contentType,byte[] bytes){this.contentType=contentType;this.bytes=bytes;this.file=null;expected=Map.of();}
        Body(String contentType,java.nio.file.Path file,Map<String,Long> expected){this.contentType=contentType;this.bytes=null;this.file=file;this.expected=Map.copyOf(expected);}
        boolean attachmentsAccepted(String json){
            if(expected.isEmpty())return true;
            try{var files=com.google.gson.JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("attachments");var found=new HashMap<String,Long>();for(var item:files){var f=item.getAsJsonObject();found.put(f.get("filename").getAsString(),f.get("size").getAsLong());}return expected.entrySet().stream().allMatch(e->Objects.equals(found.get(e.getKey()),e.getValue()));}catch(RuntimeException error){return false;}
        }
        public String contentType(){return contentType;}
        public byte[] bytes(){try{return bytes!=null?bytes:java.nio.file.Files.readAllBytes(file);}catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}}
        public java.net.http.HttpRequest.BodyPublisher publisher()throws java.io.IOException{return file==null?java.net.http.HttpRequest.BodyPublishers.ofByteArray(bytes):java.net.http.HttpRequest.BodyPublishers.ofFile(file);}
        public long length()throws java.io.IOException{return file==null?bytes.length:java.nio.file.Files.size(file);}
        @Override public void close(){if(file!=null)try{java.nio.file.Files.deleteIfExists(file);}catch(java.io.IOException ignored){}}
        @Override public String toString(){return "DiscordBody[content hidden]";}
    }
    public static Body body(Message message){return new Body("application/json; charset=utf-8",message.json().getBytes(StandardCharsets.UTF_8));}
    public static Body body(Message message,java.nio.file.Path report,String filename)throws java.io.IOException{
        String contentType=filename.endsWith(".html")?"text/html":filename.endsWith(".json")?"application/json":"text/csv";
        String boundary="lagsense-"+UUID.randomUUID();var file=java.nio.file.Files.createTempFile("lagsense-discord-body-",".multipart");
        try(var out=java.nio.file.Files.newOutputStream(file)){
            var json=com.google.gson.JsonParser.parseString(message.json()).getAsJsonObject();var attachments=new com.google.gson.JsonArray();var attachment=new com.google.gson.JsonObject();attachment.addProperty("id",0);attachment.addProperty("filename",filename);attachments.add(attachment);json.add("attachments",attachments);
            out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n"+json+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\""+filename+"\"\r\nContent-Type: "+contentType+"; charset=utf-8\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            java.nio.file.Files.copy(report,out);out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        }catch(Throwable error){java.nio.file.Files.deleteIfExists(file);throw error;}
        return new Body("multipart/form-data; boundary="+boundary,file,Map.of(filename,java.nio.file.Files.size(report)));
    }
}
