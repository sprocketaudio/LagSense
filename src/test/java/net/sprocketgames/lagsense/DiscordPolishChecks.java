package net.sprocketgames.lagsense;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

final class DiscordPolishChecks {
    private static final String WEBHOOK="https://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwx",ROLE="123456789012345678",AVATAR="https://cdn.example.com/lagsense.png?size=128";
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static ServerSettings.Delivery option(String group,int top,String threshold,String role,String file){return new ServerSettings.Delivery(true,0,top,0,0,"either",group,file,role,threshold);}
    private static ServerSettings.Discord connection(ServerSettings.Delivery option,String avatar,String name){return new ServerSettings.Discord(true,WEBHOOK,name,option,option,option,avatar);}
    private static Report fixture(int ms,int tps){
        var entries=new ArrayList<Report.Entry>();for(int i=0;i<8;i++)for(int j=0;j<2;j++)entries.add(new Report.Entry("Block","fixture:machine_"+i,"minecraft:overworld",i*16+j,64,0,(8-i)*(j+1)*100_000_000L,100));
        var counter=new CapturePerformance.Counter();counter.start(0);long now=0;for(int i=0;i<200;i++){now+=1_000_000_000L/tps;counter.tick(now,ms*1_000_000L);}
        return new Report(1001,1793810700000L,200,ms,true,false,entries,false,0,Report.aggregate(entries),counter.finish(now,"").averageTps(),new Report.Diagnostics("Manual",0,0,List.of(),List.of(),List.of(),"disabled",CpuInvestigation.empty("disabled"),null,BackgroundCpu.empty("disabled"),counter.finish(now,"")));
    }
    private static JsonObject payload(Report report,ServerSettings.Delivery option,String avatar,String name){return JsonParser.parseString(DiscordMessage.build(report,connection(option,avatar,name),option,Profiler.JSON).json()).getAsJsonObject();}
    private static Map<String,String> text(JsonObject payload){var result=new LinkedHashMap<String,String>();for(var part:payload.getAsJsonArray("components").get(0).getAsJsonObject().getAsJsonArray("components")){var item=part.getAsJsonObject();if(item.get("type").getAsInt()==10){String value=item.get("content").getAsString();int end=value.indexOf('\n');result.put(end<0?value:value.substring(0,end),end<0?"":value.substring(end+1));}}return result;}
    private static int lines(String section){return (int)section.lines().filter(line->line.contains("ms/t")).count();}
    static void run()throws Exception{
        releaseReview();
        var report=fixture(10,20);String original=Profiler.JSON.toJson(report);
        for(String group:List.of("target","type","both"))for(int top:List.of(1,5,10)){
            var option=option(group,top,"degraded","","html");var payload=payload(report,option,"","LagSense");DiscordReportingChecks.layout(payload,"html");var text=text(payload);
            check(text.get("## LagSense | Manual Capture").isEmpty(),"Redundant LagSense server name and blank spacing are omitted");
            if(group.equals("both")){
                String individuals=text.get("**Top measured individuals**"),types=text.get("**Top measured types**");
                check(lines(individuals)==Math.min(top,16)&&lines(types)==Math.min(top,8),"Both applies Top results independently to individual and grouped sections");
                check(individuals.startsWith("Overworld · (")&&types.startsWith("Overworld · 2 blocks\n"),"Every contributor starts with dimension and coordinates or contributor count");
                check(individuals.lines().count()==2L*Math.min(top,16)&&types.lines().count()==2L*Math.min(top,8),"Both contributor lists use exactly two lines per result without blank lines");
            }
            else check(lines(text.get("**Top measured contributors**"))==Math.min(top,group.equals("type")?8:16),"Single grouping keeps the configured result count");
            check(lines(text.get("**Top measured chunks**"))==Math.min(top,8),"Chunk section uses the same Top results instead of a fixed top three");
        }
        var individual=DiscordMessage.rows(report,option("target",10,"degraded","","none"));var grouped=DiscordMessage.rows(report,option("type",10,"degraded","","none"));
        check(Math.abs(individual.stream().mapToDouble(DiscordMessage.Row::msPerTick).sum()-grouped.stream().mapToDouble(DiscordMessage.Row::msPerTick).sum())<1e-9,"Individual and Type sections are separate views of the same cost without double-counting");
        check(grouped.stream().allMatch(row->row.targets()==2&&Math.abs(row.percent()-row.msPerTick()/report.mspt()*100)<1e-9),"Type grouping retains measured instance counts and the actual MSPT percentage denominator");
        var filtered=new ServerSettings.Delivery(true,0,5,10,0,"either","both","none","","degraded");check(DiscordMessage.build(report,connection(filtered,"","Fixture"),filtered,Profiler.JSON)!=null,"Both can qualify through grouped totals when individual targets are below the filter");
        var missing=new Report(1002,report.capturedAt(),report.ticks(),report.mspt(),true,false,report.entries());
        var severities=List.of(report,fixture(45,20),fixture(20,15),fixture(110,8),missing);
        for(var capture:severities)for(String threshold:List.of("never","critical","degraded","warning","always"))for(String file:List.of("none","html","json","csv")){
            var severity=ReportSeverity.evaluate(capture);boolean expected=threshold.equals("always")||severity!=ReportSeverity.UNKNOWN&&(threshold.equals("critical")&&severity==ReportSeverity.CRITICAL||threshold.equals("degraded")&&(severity==ReportSeverity.DEGRADED||severity==ReportSeverity.CRITICAL)||threshold.equals("warning")&&severity!=ReportSeverity.HEALTHY);
            var option=option("both",5,threshold,ROLE,file);var payload=payload(capture,option,AVATAR,"FTB Evolution 2");DiscordReportingChecks.layout(payload,file);
            check(payload.toString().contains("<@&"+ROLE+">")==expected&&payload.getAsJsonObject("allowed_mentions").has("roles")==expected,"Mention severity changes only mention text/allowlist for "+severity+" at "+threshold);
            check(payload.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").isEmpty()&&payload.get("avatar_url").getAsString().equals(AVATAR),"Explicit role-only mentions and optional avatar work for every severity and attachment mode");
            check(text(payload).entrySet().stream().filter(e->e.getKey().startsWith("## LagSense |")).findFirst().orElseThrow().getValue().startsWith("FTB Evolution 2"),"A distinct configured server name is retained");
            check(payload(capture,option("target",5,threshold,"",file),"","Fixture").getAsJsonObject("allowed_mentions").has("roles")==false,"Missing role never produces a mention");
        }
        for(String origin:List.of("Manual","Scheduled","Spike follow-up"))for(String format:List.of("none","html","json","csv")){
            var d=report.diagnostics();var capture=new Report(report.id(),report.capturedAt(),report.ticks(),report.mspt(),true,false,report.entries(),false,0,report.chunks(),report.tps(),new Report.Diagnostics(origin,0,0,d.tasks(),d.notes(),d.callbacks(),d.callbackState(),d.cpu(),d.coverage(),d.background(),d.performance()));
            var setting=option("both",5,"degraded","",format);var received=new ArrayList<JsonObject>();
            try(var delivery=new DiscordDelivery(connection(setting,AVATAR,"Fixture"),(uri,body)->{String content=new String(body.bytes(),java.nio.charset.StandardCharsets.UTF_8);if(content.startsWith("--")){int begin=content.indexOf("\r\n\r\n")+4;content=content.substring(begin,content.indexOf("\r\n--",begin));}received.add(JsonParser.parseString(content).getAsJsonObject());return new DiscordDelivery.Response(204,0);},ms->{},s->{})){
                delivery.capture(capture,"");delivery.flush();check(received.size()==1&&received.getFirst().get("avatar_url").getAsString().equals(AVATAR),"Actual asynchronous delivery preserves avatar in every origin/attachment mode");
            }
        }
        check(ServerSettings.validateAvatar("https://8.8.8.8/image.png").equals("https://8.8.8.8/image.png"),"Public HTTPS IP URLs are accepted without a hostname lookup");
        check(!payload(report,option("target",5,"always",ROLE,"none"),"","Fixture").has("avatar_url"),"Blank avatar is entirely omitted for the webhook default");
        check(JsonParser.parseString(DiscordMessage.test(connection(option("target",5,"degraded","","none"),AVATAR,"Fixture"),Profiler.JSON).json()).getAsJsonObject().get("avatar_url").getAsString().equals(AVATAR),"Connectivity tests use the same configured avatar");
        for(String invalid:List.of("http://example.com/a.png","file:///image.png","data:image/png;base64,x","javascript:alert(1)","https://localhost/a.png","https://127.0.0.1/a.png","https://192.168.0.2/a.png","https://10.0.0.1/a.png","https://[::1]/a.png","https://[fc00::1]/a.png","https://user:secret@example.com/a.png","https://example.com/a.png#fragment")){
            boolean rejected=false;try{ServerSettings.validateAvatar(invalid);}catch(IllegalArgumentException error){rejected=!error.getMessage().contains("secret");}check(rejected,"Unsupported/private avatar URL rejected without echoing the value");
        }
        check(ServerSettings.validateAvatar("  ").isEmpty()&&ServerSettings.validateAvatar(" "+AVATAR+" ").equals(AVATAR),"Blank and surrounding whitespace are handled consistently");
        for(String role:List.of("bad","123","<@&123456789012345678>")){boolean rejected=false;try{option("target",5,"always",role,"none");}catch(IllegalArgumentException error){rejected=true;}check(rejected,"Invalid role IDs cannot be saved or sent");}
        var defaults=ServerSettings.defaults().discord();
        check(defaults.timer().equals(new ServerSettings.Delivery(false,0,3,0,0,"either","both","html","","degraded")),"Timer defaults are top three, Both, no filters or cooldown, HTML and Degraded mentions");
        check(defaults.spikes().equals(new ServerSettings.Delivery(false,300,5,0,0,"either","both","html","","degraded")),"Spike defaults are top five, Both, HTML and 300 seconds");
        check(defaults.manual().equals(new ServerSettings.Delivery(false,0,5,0,0,"either","both","html","","never")),"Manual defaults are top five, Both, HTML and no mentions");
        check(ServerSettings.parse("",Map.of()).discord().equals(defaults),"Missing settings and the bundled template agree on defaults");
        check(defaults.avatarUrl().isEmpty(),"Avatar defaults to blank");
        check(!ServerSettings.template().contains("group_repeats")&&!ServerSettings.template().contains("top_offenders"),"Obsolete config settings are removed from the template");
        Path directory=Files.createTempDirectory("lagsense-discord-polish-");try{
            Path file=directory.resolve("settings.toml");Files.writeString(file,"[discord]\navatar_url=\""+AVATAR+"\"\n[discord.manual]\ngroup_repeats=true\ntop_offenders=7\ngroup_by=\"type\"\n");
            var old=ServerSettings.load(file);check(old.discord().manual().top()==7&&old.discord().manual().mentionSeverity().equals("never"),"Old result counts migrate and missing mention settings receive the new default");
            var desired=new ServerSettings.Delivery(true,30,5,0,0,"either","both","json",ROLE,"warning");var before=ServerSettingsForms.view(old);var discord=new ServerSettingsForms.Discord(false,false,"Fixture",before.discord().timer(),before.discord().spikes(),desired,AVATAR);
            var edit=new ServerSettingsForms.Edit(before,new ServerSettingsForms.View(before.advanced(),discord),"");var changes=ServerSettingsForms.changes("discord",old,edit);var saved=ServerSettings.update(file,old,changes);
            check(saved.discord().manual().equals(desired)&&saved.discord().avatarUrl().equals(AVATAR)&&ServerSettings.load(file).equals(saved),"Both, mention threshold, avatar and file format survive in-game form save and disk reload");
            String disk=Files.readString(file);check(!disk.contains("group_repeats")&&!disk.contains("top_offenders")&&disk.contains("top_results"),"Saving old config removes obsolete fields and preserves migrated values");
            String json=Profiler.JSON.toJson(ServerSettingsForms.view(saved));check(!json.contains("groupRepeats")&&!json.contains("group_repeats")&&Profiler.JSON.fromJson(json,ServerSettingsForms.View.class).equals(ServerSettingsForms.view(saved)),"New settings DTO round-trips without inactive repeat fields or lost avatar/mention settings");
        }finally{try(var files=Files.list(directory)){for(var file:files.toList())Files.deleteIfExists(file);}Files.deleteIfExists(directory);}
        var names=new HashSet<String>();for(long id=0;id<20000;id++){var capture=new Report(id,report.capturedAt(),200,10,true,false,List.of());String name=DiscordMessage.filename(capture);check(name.matches("LagSense-[0-9]{6}-[0-9]{6}-[a-z0-9]+\\.html")&&name.length()<=41&&names.add(name),"Compact same-second capture filenames are distinct and safe");for(String format:List.of("html","json","csv"))check(DiscordMessage.filename(capture,format).equals(name.replace(".html","."+format)),"All file formats use the same deterministic capture token");}
        for(long id:List.of(Long.MIN_VALUE,Long.MAX_VALUE,-1L)){String name=DiscordMessage.filename(new Report(id,report.capturedAt(),200,10,true,false,List.of()));check(names.add(name)&&name.length()<=41,"Lossless compact IDs also cover signed long edge cases");}
        var option=new ServerSettings.Delivery(true,600,5,0,0,"either","target","none","","degraded");var posted=new ArrayList<String>();
        try(var delivery=new DiscordDelivery(connection(option,"","Fixture"),(uri,body)->{posted.add(new String(body.bytes(),java.nio.charset.StandardCharsets.UTF_8));return new DiscordDelivery.Response(204,0);},ms->{},s->{})){
            delivery.capture(report,"");delivery.flush();delivery.capture(report,"");delivery.flush();check(posted.size()==1&&delivery.status().contains("Cooldown active"),"Cooldown simply skips repeated reports");
            var field=DiscordDelivery.class.getDeclaredField("nextEligible");field.setAccessible(true);((Map<?,?>)field.get(delivery)).clear();delivery.capture(report,"");delivery.flush();check(posted.size()==2&&posted.stream().noneMatch(message->message.contains("matching notifications skipped")||message.contains("Grouped repeats")),"A later delivery contains no accumulated repeat count");
        }
        check(Profiler.JSON.toJson(report).equals(original),"Reporting changes never modify capture data or severity inputs");
        System.out.println("Discord polish checks passed (Both/independent section counts, neutral mention thresholds, avatar payload/default/URL validation, legacy migration, compact collision-free capture filenames, duplicate heading removal and skip-only cooldowns).");
    }
    private static void releaseReview()throws Exception{
        var option=option("both",5,"degraded","","none");
        var counter=new CaptureActivity.Counter();counter.add(0,List.of(new CaptureActivity.Observation("minecraft:overworld",100,40,16)));
        for(int i=0;i<2;i++){
            long peak=(i==0?434:324)*1_000_000L;
            var performance=new CapturePerformance(30_000_000_000L,600,peak,1,peak,i==0?9.5:18.9,30,"");
            var diagnostics=new Report.Diagnostics("Manual",0,0,List.of(),List.of(),List.of(),"disabled",CpuInvestigation.empty("disabled"),null,null,performance,counter.snapshot());
            var report=new Report(800+i,1793810700000L,600,i==0?4.5:6.6,true,false,fixture(10,20).entries(),false,0,List.of(),20,diagnostics);
            String message=DiscordMessage.build(report,connection(option,"","Fixture"),option,Profiler.JSON).json();
            check(ReportSeverity.evaluate(report)==(i==0?ReportSeverity.WARNING:ReportSeverity.HEALTHY),"The two observed captures retain their existing health classifications");
            check(message.contains("Tick spike observed")&&message.contains("capture averages")&&message.contains("Capture information"),"Significant peaks have a separate explanation and labelled capture details");
            check(message.contains("Active ·")&&!message.contains("Idle ·"),"Ticking force-loaded work remains Active with zero players");
            check(message.contains("brief TPS dip")== (i==0),"A brief low TPS window explains the Warning rating when applicable");
        }
        var zero=new Report.Entry("Block","fixture:zero","minecraft:overworld",0,64,0,0,10);
        var tiny=new Report.Entry("Block","fixture:tiny","minecraft:overworld",16,64,0,100,10);
        var small=new Report(900,1793810700000L,200,10,true,false,List.of(zero,tiny));
        String message=text(payload(small,option,"","Fixture")).values().toString();
        check(!message.contains("fixture:zero")&&message.contains("<0.001 ms/t")&&!message.contains("0.000 ms/t"),"Zero-cost results are omitted and positive sub-microsecond results keep an honest precision label");
        check(message.contains("Overworld · (16, 64, 0)\n<0.001 ms/t")&&message.contains("Overworld · 1 block\n<0.001 ms/t total"),"Individuals and types put location or count before timing, with singular grammar");
        var entityReport=new Report(901,small.capturedAt(),200,10,true,false,List.of(new Report.Entry("Entity","fixture:mob","minecraft:the_nether",1,64,2,1_000_000,10),new Report.Entry("Entity","fixture:mob","minecraft:the_nether",3,64,4,1_000_000,10)));
        String entityTypes=text(payload(entityReport,option,"","Fixture")).get("**Top measured types**");
        check(entityTypes.startsWith("Nether · 2 entities\n0.010 ms/t total ·")&&!entityTypes.contains("(")&&!entityTypes.contains("Entity fixture:"),"Grouped entities show a dimension and population without a fabricated shared location or redundant category");
        var filtered=new ServerSettings.Delivery(true,0,5,1,0,"either","both","none","","never");
        check(!DiscordMessage.qualifies(new DiscordMessage.Row("Block","fixture:tiny","minecraft:overworld",1,.1,99,null,null,null),filtered),"A disabled percentage filter cannot bypass an enabled milliseconds filter with Either");
        var directory=Files.createTempDirectory("lagsense-default-upgrade-");
        try{
            var path=directory.resolve("server.toml");
            Files.writeString(path,ServerSettings.template().replace("top_results = 3","top_results = 5").replace("cooldown_seconds = 300","cooldown_seconds = 600").replace("group_by = \"both\"","group_by = \"target\"").replace("attachment = \"html\"","attachment = \"none\"").replace("mention_severity = \"never\"","mention_severity = \"degraded\""));
            var existing=ServerSettings.load(path);var saved=ServerSettings.update(path,existing,Map.of("discord.server_name","Renamed"));
            check(existing.discord().timer().equals(saved.discord().timer())&&existing.discord().spikes().equals(saved.discord().spikes())&&existing.discord().manual().equals(saved.discord().manual()),"Explicit old preferences survive loading and an unrelated save");
            check(!saved.discord().enabled()&&!saved.discord().timer().enabled()&&!saved.discord().spikes().enabled()&&!saved.discord().manual().enabled(),"New defaults preserve all opt-in switches");
        }finally{Files.deleteIfExists(directory.resolve("server.toml"));Files.deleteIfExists(directory);}
        var cooldown=new ServerSettings.Delivery(true,300,5,0,0,"either","both","none","","degraded");
        var sent=new ArrayList<String>();
        try(var delivery=new DiscordDelivery(connection(cooldown,"","Fixture"),(uri,body)->{sent.add(new String(body.bytes(),java.nio.charset.StandardCharsets.UTF_8));return new DiscordDelivery.Response(204,0);},ms->{},s->{})){
            var report=fixture(10,20);delivery.capture(report,"","spikes");delivery.flush();delivery.capture(report,"","spikes");delivery.flush();
            check(sent.size()==1,"A repeated spike signature is subject to cooldown");
            var other=new Report(1002,report.capturedAt(),report.ticks(),report.mspt(),true,false,List.of(new Report.Entry("Block","fixture:other","minecraft:the_nether",1000,64,0,1_000_000_000L,100)));
            delivery.capture(other,"","spikes");delivery.flush();
            check(sent.size()==2,"A different measured problem can send inside another spike's cooldown");
            delivery.capture(fixture(110,8),"","spikes");delivery.flush();
            check(sent.size()==3,"A severity escalation can send during a previous signature's cooldown");
        }
    }
}
