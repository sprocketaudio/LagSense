package net.sprocketgames.lagsense;

import java.nio.file.*;
import java.util.*;

final class SettingsFormChecks {
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static void run(com.google.gson.Gson gson)throws Exception {
        require(SettingsMenu.categories(true).equals(List.of(SettingsMenu.Category.HUD,SettingsMenu.Category.CAPTURES,SettingsMenu.Category.DISCORD,SettingsMenu.Category.ADVANCED)),"Admin Settings has exactly four categories, with no History or Frame profiling entry");
        require(SettingsMenu.categories(false).equals(List.of(SettingsMenu.Category.HUD)),"Local/player Settings cannot expose server editors");
        require(SettingsMenu.Category.CAPTURES.label.equals("Automatic Captures"),"Automatic capture settings use their explicit menu label");
        String webhook="https://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwx";
        var defaults=ServerSettings.defaults();
        var configured=new ServerSettings(defaults.timer(),defaults.spikes(),false,defaults.history(),new ServerSettings.Discord(false,webhook,"Fixture",defaults.discord().timer(),defaults.discord().spikes(),defaults.discord().manual()),defaults.backups(),true,false);
        var view=ServerSettingsForms.view(configured);
        String publicJson=gson.toJson(view);
        require(view.discord().webhookConfigured()&&!publicJson.contains(webhook)&&!publicJson.contains("abcdefghijklmnopqrstuvwx")&&!publicJson.contains("webhook_url"),"Server responses contain only webhook presence, never a URL or token");
        require(gson.fromJson(publicJson,ServerSettingsForms.View.class).equals(view),"Editable view wire round trip");
        var advanced=new ServerSettingsForms.Advanced(false,true,true,new BackupMonitor.Settings("warn",15));
        var desired=new ServerSettingsForms.View(advanced,new ServerSettingsForms.Discord(true,true,"Changed",view.discord().timer(),view.discord().spikes(),view.discord().manual()));
        var edit=new ServerSettingsForms.Edit(view,desired,webhook);
        require(!edit.toString().contains(webhook)&&!new ServerSettingsRequest("discord",gson.toJson(edit)).toString().contains(webhook),"Request diagnostics redact submitted credentials");
        var edits=ServerSettingsForms.changes("advanced",configured,edit);
        require(edits.size()==10&&edits.keySet().stream().noneMatch(k->k.startsWith("discord")||k.startsWith("history")||k.startsWith("capture.timer")),"Advanced saves only its allowed five fields even when another section is modified in the request");
        var delivery=new ServerSettings.Delivery(true,120,3,2.5,10,"both","both","csv","123456789012345678","warning");
        var timerPolicy=new ServerSettings.TimerDelivery(false,375,2.5,true,true);
        var discord=new ServerSettingsForms.Discord(true,true,"Changed",delivery,view.discord().spikes(),view.discord().manual(),"",timerPolicy);
        var discordEdit=new ServerSettingsForms.Edit(view,new ServerSettingsForms.View(advanced,discord),"");
        var discordEdits=ServerSettingsForms.changes("discord",configured,discordEdit);
        require(discordEdits.keySet().stream().allMatch(k->k.startsWith("discord."))&&!discordEdits.containsKey("discord.webhook_url"),"Blank webhook input preserves the current credential and unrelated settings");
        Path directory=Files.createTempDirectory("lagsense-settings-forms-");Path file=directory.resolve("server.toml");
        try {
            Files.writeString(file,ServerSettings.template());
            var initial=ServerSettings.load(file);
            var first=ServerSettings.update(file,initial,Map.of("discord.webhook_url",webhook));
            var savedAdvanced=ServerSettings.update(file,first,edits);
            require(!savedAdvanced.modTasks()&&savedAdvanced.detailedCallbacks()&&savedAdvanced.monitorEmptyServer()&&savedAdvanced.backups().equals(advanced.backups()),"Advanced values save and reload");
            require(savedAdvanced.discord().webhookUrl().equals(webhook)&&savedAdvanced.timer().equals(first.timer())&&savedAdvanced.history().equals(first.history()),"Advanced saves preserve credentials and capture/history settings");
            var live=ServerSettingsForms.view(savedAdvanced);
            var commit=new ServerSettingsForms.Edit(live,new ServerSettingsForms.View(live.advanced(),discord),"");
            var savedDiscord=ServerSettings.update(file,savedAdvanced,ServerSettingsForms.changes("discord",savedAdvanced,commit));
            require(savedDiscord.discord().enabled()&&savedDiscord.discord().timer().equals(delivery)&&savedDiscord.discord().webhookUrl().equals(webhook),"Discord delivery switches, filters, role and attachment save without changing the existing endpoint");
            require(savedDiscord.discord().timerPolicy().equals(timerPolicy)&&ServerSettings.load(file).discord().timerPolicy().equals(timerPolicy),"Notification rules save and reload through the settings editor");
            var policyView=ServerSettingsForms.view(savedDiscord);
            require(gson.fromJson(gson.toJson(policyView),ServerSettingsForms.View.class).equals(policyView),"Custom notification rules survive the editor wire round trip");
            require(ServerSettingsForms.changes("discord",savedDiscord,new ServerSettingsForms.Edit(policyView,policyView,"")).get("discord.timer.significant_peak_ms").equals(375.0),"Saving another Discord tab preserves custom notification rules");
            var changedPolicy=ServerSettings.parse(Files.readString(file).replace("375.0","400.0"),Map.of());
            try{ServerSettingsForms.changes("discord",changedPolicy,new ServerSettingsForms.Edit(policyView,policyView,""));throw new AssertionError("Stale notification rules accepted");}catch(IllegalArgumentException expected){}
            String before=Files.readString(file);
            try{ServerSettingsForms.changes("discord",savedDiscord,commit);throw new AssertionError("Stale page accepted");}catch(IllegalArgumentException expected){}
            require(Files.readString(file).equals(before),"Stale form conflict makes no file changes");
            var now=ServerSettingsForms.view(savedDiscord);
            var invalid=new ServerSettingsForms.Edit(now,now,"secret-invalid-webhook");
            try{ServerSettingsForms.changes("discord",savedDiscord,invalid);throw new AssertionError("Bad webhook accepted");}catch(IllegalArgumentException error){require(!error.getMessage().contains("secret-invalid-webhook"),"Validation errors never echo a webhook");}
            require(Files.readString(file).equals(before),"Invalid webhook does not overwrite the file");
            var disabledNoEndpoint=new ServerSettingsForms.View(view.advanced(),new ServerSettingsForms.Discord(true,false,"Fixture",view.discord().timer(),view.discord().spikes(),view.discord().manual()));
            Files.writeString(file,ServerSettings.template());var noEndpoint=ServerSettings.load(file);
            try{ServerSettings.update(file,noEndpoint,ServerSettingsForms.changes("discord",noEndpoint,new ServerSettingsForms.Edit(ServerSettingsForms.view(noEndpoint),disabledNoEndpoint,"")));throw new AssertionError("Discord enabled without an endpoint");}catch(IllegalArgumentException expected){}
            require(ServerSettings.load(file).equals(noEndpoint),"Rejected Discord configuration leaves previous settings intact");
        }finally{Files.deleteIfExists(file);Files.deleteIfExists(directory);}
        try{ServerSettingsForms.changes("arbitrary",configured,edit);throw new AssertionError("Unknown editor section accepted");}catch(IllegalArgumentException expected){}
        require(publicJson.length()<6000&&gson.toJson(edit).length()<12000,"Editor payloads fit their bounded codecs");
        System.out.println("Settings editor checks passed (menu roles, scoped persistence, conflicts, validation and credential redaction).");
    }
}
