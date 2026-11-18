package net.sprocketgames.lagsense;

import net.sprocketgames.lagsense.client.UiTheme;
import java.nio.file.*;
import java.util.*;

final class HtmlExportChecks {
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    static void run()throws Exception{
        Path out=Path.of("work/html-export/examples");Files.createDirectories(out);
        HtmlDataChecks.precision(out);
        for(String name:List.of("old","limited","comparison","old-comparison","large","thread","private","oversized","chunk-fallback","frames","validation","shortened-stacks","critical","idle"))Files.deleteIfExists(out.resolve(name+".html"));
        for(var theme:UiTheme.Theme.values())Files.deleteIfExists(out.resolve("small-"+theme.name().toLowerCase(Locale.ROOT)+".html"));
        var uuid=UUID.fromString("12345678-1234-1234-1234-123456789abc");
        var entries=List.of(new Report.Entry("Block","fixture:machine","minecraft:overworld",-17,-40,-33,4000000,10),new Report.Entry("Entity","minecraft:creeper","minecraft:the_nether",-1,64,-1,1000000,10,uuid),new Report.Entry("Random block","fixture:plant","minecraft:overworld",1,64,2,1,1),new Report.Entry("Block","fixture:missing","minecraft:overworld",0,0,0,1,1,null,false));
        var tree=new CpuInvestigation.Builder("Server thread (#1)");tree.add(List.of(new CpuInvestigation.Frame("ae2","appeng.Worker","save","()V"),new CpuInvestigation.Frame("minecraft","net.minecraft.Server","tick","()V")));tree.add(List.of(new CpuInvestigation.Frame("minecraft","net.minecraft.Server","tick","()V")));var cpu=tree.finish();
        var workers=new CpuSamples(1,"Server thread",new DiagnosticBudget(1L<<20));workers.add(2,"network-worker",List.of(new CpuInvestigation.Frame("ae2","appeng.Network","plan","()V")));workers.add(3,"network-worker",List.of(new CpuInvestigation.Frame("unattributed","unknown.Library","work","()V")));var background=workers.finish(false).background();
        String attack="</script><img src=x onerror=globalThis.lagsenseInjected=1><script>globalThis.lagsenseInjected=1</script>\u2028\u2029 & \" '";
        var d=new Report.Diagnostics("Manual",100,200,List.of(new Report.Task("ae2","appeng.Worker#save",1),new Report.Task("minecraft","net.minecraft.Server#tick",1)),List.of(),List.of(new Report.Callback("ae2","test.TickEvent","appeng.Worker#tick",10000000,10,1000000,1),new Report.Callback("unattributed",attack,attack,10,1,10,2)),"ready",cpu,new TimingCoverage.Snapshot(20000000,5000000,10000000,2000000,7000000),background);
        var small=new Report(9007199254740993L,1793740000000L,10,2,true,false,entries,false,0,Report.aggregate(entries),20,d);
        var cleanEntries=new ArrayList<>(entries.subList(0,3));cleanEntries.add(new Report.Entry("Block","fixture:storage","minecraft:overworld",2,64,3,500000,10));
        var activity = new CaptureActivity.Counter();
        activity.add(1, List.of(new CaptureActivity.Observation("minecraft:overworld", 12, 6, 2),
            new CaptureActivity.Observation("minecraft:the_nether", 6, 4, 1)));
        activity.add(0, List.of(new CaptureActivity.Observation("minecraft:overworld", 6, 3, 2),
            new CaptureActivity.Observation("minecraft:the_nether", 2, 1, 1)));
        var cleanDiagnostics=new Report.Diagnostics(d.trigger(),d.bookkeepingNanos(),d.snapshotNanos(),d.tasks(),d.notes(),List.of(d.callbacks().getFirst(),new Report.Callback("minecraft","test.TickEvent","net.minecraft.Server#tick",1000000,10,100000,2)),d.callbackState(),cpu,d.coverage(),background,null,activity.snapshot());
        var clean=new Report(small.id(),small.capturedAt(),small.ticks(),small.mspt(),true,false,cleanEntries,false,0,Report.aggregate(cleanEntries),small.tps(),cleanDiagnostics);
        HtmlReportExport.write(out.resolve("validation.html"),small,UiTheme.Theme.OBSIDIAN_GOLD);
        String securityHtml=Files.readString(out.resolve("validation.html"));
        check(!securityHtml.contains(attack)&&securityHtml.contains("\\u003c/script\\u003e"),"Untrusted data cannot escape its inert JSON script");
        var manifest=new LinkedHashMap<String,Object>();
        var emptyChunks=new Report(6,small.capturedAt(),10,1,true,false,List.of(),false,0,List.of(new Report.Chunk("minecraft:the_end",-2,-3,4000,2,null)),20,new Report.Diagnostics("Manual",0,0));
        HtmlReportExport.write(out.resolve("chunk-fallback.html"),emptyChunks,UiTheme.Theme.OBSIDIAN_GOLD);
        var frameCapture=new FrameProfile.Capture();frameCapture.frame(16000000);frameCapture.frame(20000000);frameCapture.stage("World",8000000);frameCapture.gpu(4000000);
        var frames=frameCapture.finish();
        HtmlReportExport.write(out.resolve("frames.html"),frames,UiTheme.Theme.CHARCOAL_ICE);
        HtmlDataChecks.roundTrip(out.resolve("frames.html"),frames);
        var chunk=ChunkInvestigation.create(clean,"minecraft:overworld",-2,-3,true,entry->true);
        Files.deleteIfExists(out.resolve("chunk.html"));
        HtmlReportExport.write(out.resolve("chunk.html"),chunk,UiTheme.DEFAULT);
        HtmlDataChecks.roundTrip(out.resolve("chunk.html"),chunk);
        for(var theme:UiTheme.Theme.values()){
            Path file=out.resolve("small-"+theme.name().toLowerCase(Locale.ROOT)+".html");HtmlReportExport.write(file,clean,theme);String html=Files.readString(file);
            check(html.contains("data-theme=\""+theme.name().toLowerCase(Locale.ROOT)+"\""),"Export preserves the selected initial theme");
            check(html.contains(String.format(Locale.ROOT,"--accent:#%06x",theme.palette().accent()&0xffffff)),"HTML uses the actual native palette");
            check(!html.contains("lagsenseInjected")&&!html.contains("fixture:missing"),"Normal examples do not contain hostile strings or deliberately missing coordinates");
            check(html.contains("connect-src 'none'")&&html.contains("Content-Security-Policy")&&html.contains("font-src 'none'")&&!html.contains("base64")&&!html.contains("@font-face")&&!html.contains("Font licence"),"Standalone assets and network-denying CSP");
            check(html.contains("\"id\":\"9007199254740993\""),"Capture identifiers survive JavaScript integer precision limits");
            check(html.startsWith("<!doctype html>\n<!--\nLagSense performance report\n")&&html.contains("Everything runs locally. No external connections are made."),"Requested short source comment is near the top");
            check(html.contains("\"format\":2")&&html.contains("\"entries\":[\n[")&&html.contains("entry rows follow entryColumns")&&html.contains("2 means strings[2]")&&!html.contains("atob(")&&!html.contains("DecompressionStream")&&html.indexOf(0x2014)<0,"Report data uses compact rows on separate lines with a short dictionary explanation and readable viewer assets");
            HtmlDataChecks.roundTrip(file,clean);
            for(String element:List.of("style","script")){
                int begin=element.equals("style")?html.indexOf("<style>")+7:html.lastIndexOf("<script>")+8;int end=html.indexOf("</"+element+">",begin);
                String digest=Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(html.substring(begin,end).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                check(html.contains("'sha256-"+digest+"'"),"Readable inline "+element+" retains a matching CSP integrity hash");
            }
            check(!html.contains("webhook_url")&&!html.contains("bot_token"),"Only whitelisted report data is exported");
        }
        check(!HtmlReportExport.needsCpu(small),"Full server paths require no secondary request");
        check(HtmlReportExport.needsCpu(new Report(small.id(),small.capturedAt(),small.ticks(),small.mspt(),true,false,small.entries(),false,0,small.chunks(),small.tps(),d.summary())),"Summarised server paths must be requested for this exact capture");
        var enriched=HtmlReportExport.withCpu(new Report(small.id(),small.capturedAt(),small.ticks(),small.mspt(),true,false,small.entries(),false,0,small.chunks(),small.tps(),d.summary()),cpu);
        check(enriched.id()==small.id()&&enriched.entries()==small.entries()&&enriched.diagnostics().background()==background&&enriched.diagnostics().cpu()==cpu,"CPU enrichment preserves selected capture, targets and worker paths");
        var old=new Report(2,1793740000000L,100,3,true,false,List.of(entries.getFirst()));HtmlReportExport.write(out.resolve("old.html"),old,UiTheme.Theme.CHARCOAL_ICE);
        String oldHtml=Files.readString(out.resolve("old.html"));var legacyDocument=new com.google.gson.JsonObject();legacyDocument.addProperty("format",1);legacyDocument.addProperty("kind","profile");legacyDocument.add("data",Profiler.JSON.toJsonTree(old));
        var dataPattern=java.util.regex.Pattern.compile("(<script id=\"report-data\" type=\"application/json\">)[\\s\\S]*?(</script>)");
        Files.writeString(out.resolve("legacy-format.html"),dataPattern.matcher(oldHtml).replaceFirst(match->match.group(1)+legacyDocument+match.group(2)));
        var limited=new Report(3,1793740000000L,10,2,true,true,entries,false,0,Report.aggregate(entries),20,new Report.Diagnostics("Spike follow-up",0,0,List.of(),List.of("Capture stopped: target memory budget reached. Final tick is partial.","Event results limited: test capture budget reached."),cleanDiagnostics.callbacks(),"ready",cpu,d.coverage(),new BackgroundCpu(background.state(),background.threads(),background.samples(),3,true,"Background capture budget reached (4 MiB).")));
        HtmlReportExport.write(out.resolve("limited.html"),limited,UiTheme.Theme.OBSIDIAN_GOLD);
        var shortenedBuilder = new CpuInvestigation.Builder("Server thread");
        var frame = List.of(new CpuInvestigation.Frame("minecraft","net.minecraft.Server","tick","()V"));
        shortenedBuilder.add(frame, true);
        shortenedBuilder.add(frame, true);
        var shortened = shortenedBuilder.finish();
        var shortenedDiagnostics = new Report.Diagnostics("Manual",0,0,List.of(),List.of(
            "CPU samples show Java code seen running on each thread. Short calls and time spent waiting may be missed.",
            "CPU results limited: " + shortened.limitation()),List.of(),"disabled",shortened);
        var sampling = new Report(8,small.capturedAt(),30,2,true,false,entries,false,0,Report.aggregate(entries),20,shortenedDiagnostics);
        HtmlReportExport.write(out.resolve("shortened-stacks.html"),sampling,UiTheme.Theme.MIDNIGHT_VIOLET);
        var critical = new Report(9,small.capturedAt(),1,2,true,true,List.of(),false,0,List.of(),20,
            new Report.Diagnostics("Manual",0,0,List.of(),List.of("Capture stopped: target memory budget reached.")));
        HtmlReportExport.write(out.resolve("critical.html"),critical,UiTheme.Theme.MIDNIGHT_VIOLET);
        var idleCounts = new CaptureActivity.Counter();
        idleCounts.add(0,List.of(new CaptureActivity.Observation("minecraft:overworld",0,0,0)));
        var idlePerformance = new CapturePerformance(5_000_000_000L,100,300_000,0,0,20,5,"");
        var idle = new Report(10,small.capturedAt(),100,0.2,true,false,List.of(),false,0,List.of(),20,
            new Report.Diagnostics("Scheduled",0,0,List.of(),List.of(),List.of(),"disabled",
                CpuInvestigation.empty("disabled"),null,null,idlePerformance,idleCounts.snapshot()));
        HtmlReportExport.write(out.resolve("idle.html"),idle,UiTheme.Theme.MIDNIGHT_VIOLET);
        var c=ProfileComparison.between(small,new Report(4,small.capturedAt()+30000,20,3,true,false,List.of(new Report.Entry("Block","fixture:machine","minecraft:overworld",-17,-40,-33,12000000,20),new Report.Entry("Entity","minecraft:creeper","minecraft:the_nether",18,65,1,4000000,20,uuid))));
        HtmlReportExport.write(out.resolve("comparison.html"),c,UiTheme.Theme.MIDNIGHT_VIOLET);
        HtmlDataChecks.roundTrip(out.resolve("comparison.html"),c);
        var expected=new ComparisonIndex(c);manifest.put("comparisonTypes",expected.all.types().stream().map(n->Map.of("name",n.name(),"before",n.before(c),"after",n.after(c),"delta",n.delta(c))).toList());
        var legacy=new ProfileComparison(1,2,1000,2000,10,20,2,3,false,c.types(),c.chunks());HtmlReportExport.write(out.resolve("old-comparison.html"),legacy,UiTheme.Theme.CHARCOAL_ICE);
        var many=new ArrayList<Report.Entry>();for(int i=0;i<100000;i++)many.add(new Report.Entry(i%2==0?"Entity":"Block","fixture:target_"+i,"minecraft:overworld",i%512-256,64,i/512-96,i+1,1,i%2==0?new UUID(0,i+1):null));
        var events=new ArrayList<Report.Callback>();for(int i=0;i<9001;i++)events.add(new Report.Callback("fixture","fixture.Event","fixture.Handler#handler_"+i,i+1,1,i+1,i+1));
        var large=new Report(5,1793740000000L,100,5,true,false,many,false,0,Report.aggregate(many),20,new Report.Diagnostics("Scheduled",0,0,List.of(),List.of(),events,"ready",cpu,null,background));
        long start=System.nanoTime();HtmlReportExport.write(out.resolve("large.html"),large,UiTheme.Theme.OBSIDIAN_GOLD);manifest.put("largeTargets",many.size());manifest.put("largeEvents",events.size());manifest.put("largeBytes",Files.size(out.resolve("large.html")));manifest.put("largeGenerationMs",(System.nanoTime()-start)/1000000.0);
        HtmlReportExport.write(out.resolve("thread.html"),cpu,UiTheme.Theme.CHARCOAL_ICE);
        HtmlDataChecks.roundTrip(out.resolve("thread.html"),cpu);
        HtmlDataChecks.roundTrip(out.resolve("large.html"),large);
        boolean failed=false;Path oversized=out.resolve("oversized.html");Files.deleteIfExists(oversized);try{HtmlReportExport.write(oversized,large,UiTheme.DEFAULT,1024);}catch(Exception error){failed=true;}check(failed&&!Files.exists(oversized),"Oversize export fails and leaves no truncated file");
        var privateReport=small.view(false,e->true);HtmlReportExport.write(out.resolve("private.html"),privateReport,UiTheme.Theme.CHARCOAL_ICE);String privateHtml=Files.readString(out.resolve("private.html"));check(!privateHtml.contains("appeng.Worker#save"),"Private views never gain server CPU diagnostics through HTML");
        check(TeleportLocation.command("minecraft:the_nether",-17,-40,-33).equals("/lagsense tp location minecraft:the_nether -17 -40 -33"),"Teleport commands preserve dimension and negative coordinates");
        check(TeleportLocation.chunkCommand("minecraft:the_end",-1,-2).equals("/lagsense tp chunk minecraft:the_end -1 -2"),"Chunk commands preserve negative chunk indices");
        check(TeleportLocation.command("bad dimension",0,64,0)==null&&TeleportLocation.command("minecraft:overworld",Integer.MAX_VALUE,64,0)==null,"Invalid destinations cannot produce commands");
        Files.writeString(out.resolve("manifest.json"),Profiler.JSON.toJson(manifest));
        System.out.println("HTML export checks passed: three exact themes, 100,000 targets, 9,001 handlers, worker paths, old/incomplete/comparison snapshots, injection encoding, privacy, complete data and explicit size failures.");
    }
}
