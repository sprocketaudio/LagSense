package net.sprocketgames.lagsense;

import net.sprocketgames.lagsense.client.UiTheme;
import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Client export worker only: a complete immutable capture, never server configuration. */
public final class HtmlReportExport {
    public static final long MAX_BYTES=CaptureBudget.MAX_FILE_BYTES;
    private static final List<String> ENTRY_COLUMNS=List.of("kind","type","dimension","x","y","z","nanos","calls","entityId","locationKnown");
    public static final class TooLarge extends IOException {public TooLarge(){super("HTML export exceeds 128 MiB and could not be saved.");}}
    /** Per-export dictionary; rows stream directly to disk without building a second capture tree. */
    private static final class EntryRows extends TypeAdapter<Report.Entry> {
        private final Map<String,Integer> indices=new HashMap<>();
        private final List<String> strings=new ArrayList<>();
        private final ScriptData output;
        EntryRows(ScriptData output){this.output=output;}
        private Integer index(String value){
            if(value==null)return null;
            return indices.computeIfAbsent(value,key->{strings.add(key);return strings.size()-1;});
        }
        @Override public void write(JsonWriter out,Report.Entry entry)throws IOException{
            if(entry==null){out.nullValue();return;}
            output.entryBoundary();
            out.beginArray();
            out.value(index(entry.kind()));out.value(index(entry.type()));out.value(index(entry.dimension()));
            out.value(entry.x());out.value(entry.y());out.value(entry.z());
            out.value(Long.toString(entry.nanos()));out.value(Long.toString(entry.calls()));
            if(entry.entityId()!=null||entry.locationKnown()!=null)out.value(entry.entityId()==null?null:entry.entityId().toString());
            if(entry.locationKnown()!=null)out.value(entry.locationKnown());
            out.endArray();
            output.entryBoundary();
        }
        @Override public Report.Entry read(JsonReader in)throws IOException{
            throw new IOException("Compact HTML rows are read by the report viewer.");
        }
    }
    private static final class Limited extends FilterOutputStream {
        private final long maximum;private long size;
        Limited(OutputStream out,long maximum){super(out);this.maximum=maximum;}
        private void check(int length)throws IOException{if(length>maximum-size)throw new TooLarge();size+=length;}
        @Override public void write(int value)throws IOException{check(1);out.write(value);}
        @Override public void write(byte[] value,int off,int len)throws IOException{check(len);out.write(value,off,len);}
    }
    /** Defence independent of Gson configuration: data cannot close its inert script element. */
    private static final class ScriptData extends FilterWriter {
        private boolean entryBoundary;
        ScriptData(Writer out){super(out);}
        void entryBoundary(){entryBoundary=true;}
        @Override public void write(char[] value,int off,int len)throws IOException{
            if(entryBoundary&&len>0){
                entryBoundary=false;
                // Keep separating commas on the preceding row and closing delimiters on a new line.
                if(value[off]==','){out.write(',');off++;len--;}
                out.write('\n');
            }
            int start=off;for(int i=off;i<off+len;i++){
            String escaped=switch(value[i]){case '<'->"\\u003c";case '>'->"\\u003e";case '&'->"\\u0026";case '\u2028'->"\\u2028";case '\u2029'->"\\u2029";default->null;};
            if(escaped!=null){out.write(value,start,i-start);out.write(escaped);start=i+1;}
        }out.write(value,start,off+len-start);}
        @Override public void write(String value,int off,int len)throws IOException{write(value.substring(off,off+len).toCharArray(),0,len);}
        @Override public void write(int value)throws IOException{write(new char[]{(char)value},0,1);}
    }
    public static Report withCpu(Report report,CpuInvestigation cpu){
        if(!report.admin()||report.diagnostics()==null)return report;
        CpuInvestigation.validate(cpu);var d=report.diagnostics();
        return new Report(report.id(),report.capturedAt(),report.ticks(),report.mspt(),report.admin(),report.truncated(),report.entries(),report.recording(),report.secondsRemaining(),report.chunks(),report.tps(),
            new Report.Diagnostics(d.trigger(),d.bookkeepingNanos(),d.snapshotNanos(),d.tasks(),d.notes(),d.callbacks(),d.callbackState(),cpu,d.coverage(),d.background(),d.performance(),d.activity()));
    }
    public static boolean needsCpu(Report report){var d=report.diagnostics();return report.admin()&&d!=null&&d.cpu().state().equals("ready")&&d.cpu().samples()>0&&d.cpu().methods().isEmpty()&&d.cpu().nodes().isEmpty();}
    public static void write(Path destination,Object capture,UiTheme.Theme theme)throws IOException{write(destination,capture,theme,MAX_BYTES);}
    static void write(Path destination,Object capture,UiTheme.Theme theme,long maximum)throws IOException{
        Objects.requireNonNull(theme);String kind;
        if(capture instanceof Report r){if(r.recording())throw new IOException("Finish the capture before exporting HTML.");ProfileArchive.validate(r);kind="profile";if(!r.admin())capture=r.view(false,e->true);}
        else if(capture instanceof ProfileComparison)kind="comparison";
        else if(capture instanceof CpuInvestigation cpu){CpuInvestigation.validate(cpu);kind="cpu";}
        else if(capture instanceof ChunkInvestigation)kind="chunk";
        else if(capture instanceof FrameProfile)kind="frames";
        else throw new IOException("Unsupported HTML report type");
        Path target=destination.toAbsolutePath(),parent=target.getParent();Files.createDirectories(parent);
        Path temporary=Files.createTempFile(parent,".lagsense-html-",".tmp");
        try{
            String css="\n"+style(),script="\n"+resource("viewer.js"),worker=resource("worker.js");
            try(var output=new Limited(Files.newOutputStream(temporary),Math.min(MAX_BYTES,maximum));var writer=new OutputStreamWriter(output,StandardCharsets.UTF_8)){
                writer.write("<!doctype html>\n<!--\nLagSense performance report\n\nGenerated by the LagSense Minecraft profiler.\nContains profiling data, CSS and JavaScript for the interactive report.\nEverything runs locally. No external connections are made.\n-->\n");
                writer.write("<html lang=\"en\" data-theme=\"");writer.write(theme.name().toLowerCase(Locale.ROOT));writer.write("\">\n<head>\n  <meta charset=\"utf-8\">\n  <meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n");
                writer.write("  <meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'sha256-"+hash(script)+"'; style-src 'sha256-"+hash(css)+"'; font-src 'none'; worker-src blob:; connect-src 'none'; img-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'\">\n");
                writer.write("  <title>LagSense | "+(kind.equals("comparison")?"What changed?":"Captured report")+"</title>\n\n  <!-- Report styles -->\n  <style>");writer.write(css);writer.write("</style>\n</head>\n<body>\n");
                writer.write(resource("shell.html"));
                String health = capture instanceof Report report && report.admin() ? ReportSeverity.evaluate(report).label : null;
                writer.write("\n<!-- Complete captured profiling data (format 2).\nTarget entry rows follow entryColumns: [kind,type,dimension,x,y,z,nanos,calls,entityId,locationKnown].\nThe first three cells are zero-based indices into strings. For example, 2 means strings[2], the third dictionary value.\nCoordinates are numbers. nanos/calls are exact decimal strings. Trailing entityId/locationKnown cells may be absent.\nOther data keeps its named fields. -->\n<script id=\"report-data\" type=\"application/json\">\n");
                var dataWriter=new ScriptData(writer);
                var rows=new EntryRows(dataWriter);
                var json=new GsonBuilder().setLongSerializationPolicy(LongSerializationPolicy.STRING).registerTypeAdapter(Report.Entry.class,rows).create();
                var dataOut=new JsonWriter(dataWriter);
                dataOut.beginObject();
                dataOut.name("format").value(2);dataOut.name("kind").value(kind);
                if(health!=null)dataOut.name("health").value(health);
                dataOut.name("entryColumns");json.toJson(ENTRY_COLUMNS,List.class,dataOut);
                dataOut.name("data");json.toJson(capture,capture.getClass(),dataOut);
                dataOut.name("strings");json.toJson(rows.strings,List.class,dataOut);
                dataOut.endObject();dataOut.flush();
                writer.write("\n</script>\n\n<!-- Local data worker -->\n<script id=\"viewer-worker\" type=\"application/x-lagsense-worker\">\n");writer.write(worker);
                writer.write("</script>\n\n<!-- Interactive report viewer -->\n<script>");writer.write(script);writer.write("</script>\n</body>\n</html>\n");
            }
            Files.move(temporary,target); // Complete file only; existing exports are never overwritten.
        }finally{Files.deleteIfExists(temporary);}
    }
    static String style()throws IOException{
        var css=new StringBuilder(resource("viewer.css"));
        for(var theme:UiTheme.Theme.values()){
            css.append("\nhtml[data-theme=\"").append(theme.name().toLowerCase(Locale.ROOT)).append("\"] {\n");
            for(var field:UiTheme.Palette.class.getRecordComponents())try{int color=(int)field.getAccessor().invoke(theme.palette());css.append("  --").append(field.getName()).append(':').append(String.format(Locale.ROOT,"#%06x",color&0xffffff)).append(";\n");}catch(ReflectiveOperationException e){throw new IOException(e);}
            css.append("}\n");
        }
        css.append(":root {\n  --warning: #ffab40;\n  --improvement: #69f0ae;\n  --timing-colours: ");
        for(int i=0;i<5;i++){if(i>0)css.append(',');css.append(String.format(Locale.ROOT,"#%06x",PerformanceGradient.color(i/4.0)&0xffffff));}return css.append(";\n}\n").toString();
    }
    private static String hash(String value)throws IOException{try{return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IOException(e);}}
    private static String resource(String name)throws IOException{try(var in=HtmlReportExport.class.getResourceAsStream("/assets/lagsense/report/"+name)){if(in==null)throw new IOException("Missing HTML report resource: "+name);return new String(in.readAllBytes(),StandardCharsets.UTF_8).replace("\r\n","\n").replace("\r","\n");}}
    private HtmlReportExport(){}
}
