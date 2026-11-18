package net.sprocketgames.lagsense;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.sprocketgames.lagsense.client.UiTheme;

/** Lossless source inspection and repeatable HTML-size benchmarks. */
final class HtmlDataChecks {
    private static final String START="<script id=\"report-data\" type=\"application/json\">";

    static JsonObject document(String html){
        int start=html.indexOf(START)+START.length();
        return JsonParser.parseString(html.substring(start,html.indexOf("</script>",start))).getAsJsonObject();
    }

    static <T> T capture(String html,Class<T> type){
        var document=document(html);
        var data=document.get("data").deepCopy();
        if(document.get("format").getAsInt()==2)expand(data,document.getAsJsonArray("entryColumns"),document.getAsJsonArray("strings"));
        return Profiler.JSON.fromJson(data,type);
    }

    private static void expand(JsonElement data,JsonArray columns,JsonArray strings){
        if(data.isJsonArray()){
            for(var item:data.getAsJsonArray())expand(item,columns,strings);
        }else if(data.isJsonObject()){
            for(var field:data.getAsJsonObject().entrySet()){
                String name=field.getKey();var value=field.getValue();
                if(Set.of("entries","earlier","later").contains(name)&&value.isJsonArray()){
                    var rows=value.getAsJsonArray();
                    for(int i=0;i<rows.size();i++)if(rows.get(i).isJsonArray())rows.set(i,entry(rows.get(i).getAsJsonArray(),columns,strings));
                }else if(Set.of("entry","hotspot").contains(name)&&value.isJsonArray()){
                    field.setValue(entry(value.getAsJsonArray(),columns,strings));
                }else expand(value,columns,strings);
            }
        }
    }

    private static JsonObject entry(JsonArray row,JsonArray columns,JsonArray strings){
        var entry=new JsonObject();
        for(int i=0;i<row.size();i++){
            var value=row.get(i);
            if(!value.isJsonNull())entry.add(columns.get(i).getAsString(),i<3?strings.get(value.getAsInt()):value);
        }
        return entry;
    }

    static void roundTrip(Path path,Object expected)throws Exception{
        Object restored=capture(Files.readString(path),expected.getClass());
        if(!expected.equals(restored))throw new AssertionError("Compact HTML round trip changed "+expected.getClass().getSimpleName());
    }

    static void precision(Path directory)throws Exception{
        var entries=List.of(
            new Report.Entry("Entity","fixture:precise","minecraft:overworld",-30000000,-1024,29999999,
                Long.MAX_VALUE-1,9007199254740993L,UUID.fromString("12345678-1234-1234-1234-123456789abc")),
            new Report.Entry("Block","fixture:missing","minecraft:the_nether",0,0,0,0,0,null,false));
        var report=new Report(Long.MAX_VALUE,1793740000000L,100,5,true,false,entries);
        Path first=directory.resolve("precision.html"),second=directory.resolve("precision-repeat.html");
        Files.deleteIfExists(first);Files.deleteIfExists(second);
        HtmlReportExport.write(first,report,UiTheme.DEFAULT);
        HtmlReportExport.write(second,report,UiTheme.DEFAULT);
        roundTrip(first,report);
        String html=Files.readString(first);int dataStart=html.indexOf(START)+START.length();
        var rowLines=html.substring(dataStart,html.indexOf("</script>",dataStart)).lines().filter(line->line.startsWith("[")).toList();
        if(rowLines.size()!=entries.size()||rowLines.stream().anyMatch(line->!line.endsWith("]")&&!line.endsWith("],")))throw new AssertionError("Each compact entry must occupy its own complete line");
        if(!Arrays.equals(Files.readAllBytes(first),Files.readAllBytes(second)))throw new AssertionError("Compact HTML output must be deterministic");
        var rows=document(Files.readString(first)).getAsJsonObject("data").getAsJsonArray("entries");
        if(!rows.get(0).getAsJsonArray().get(6).getAsJsonPrimitive().isString())throw new AssertionError("Exact entry timings must use decimal strings");
        Files.delete(second);
    }

    public static void main(String[] args)throws Exception{
        Path output=Path.of("work/html-size-audit");Files.createDirectories(output);
        var results=new ArrayList<Map<String,Object>>();
        for(String argument:args){
            Path source=Path.of(argument);String html=Files.readString(source);
            var document=document(html);
            Class<?> type=switch(document.get("kind").getAsString()){
                case "profile"->Report.class;case "comparison"->ProfileComparison.class;
                case "cpu"->CpuInvestigation.class;case "chunk"->ChunkInvestigation.class;case "frames"->FrameProfile.class;
                default->throw new IllegalArgumentException("Unknown report kind");
            };
            Object capture=capture(html,type);
            String stem=source.getFileName().toString().replaceFirst("\\.html$","");
            Path whitespace=output.resolve(stem+"-whitespace.html"),compact=output.resolve(stem+"-compact.html");
            int start=html.indexOf(START)+START.length(),end=html.indexOf("</script>",start);
            Files.writeString(whitespace,html.substring(0,start)+new Gson().toJson(document)+html.substring(end));
            roundTrip(whitespace,capture);
            Files.deleteIfExists(compact);
            var selectedTheme=java.util.regex.Pattern.compile("data-theme=\"([a-z_]+)\"").matcher(html);
            UiTheme.Theme theme=selectedTheme.find()?UiTheme.Theme.valueOf(selectedTheme.group(1).toUpperCase(Locale.ROOT)):UiTheme.DEFAULT;
            long began=System.nanoTime();HtmlReportExport.write(compact,capture,theme);
            double milliseconds=(System.nanoTime()-began)/1e6;
            roundTrip(compact,capture);
            var result=new LinkedHashMap<String,Object>();
            result.put("source",source.toString());result.put("originalBytes",Files.size(source));
            result.put("whitespaceBytes",Files.size(whitespace));result.put("compactBytes",Files.size(compact));
            result.put("generationMs",milliseconds);result.put("whitespace",whitespace.toString());result.put("compact",compact.toString());
            results.add(result);System.out.println(new Gson().toJson(result));
        }
        Files.writeString(output.resolve("sizes.json"),new GsonBuilder().setPrettyPrinting().create().toJson(results));
    }
}
