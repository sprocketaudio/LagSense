package net.sprocketgames.lagsense.client;

import net.sprocketgames.lagsense.mixin.KeyMappingLookupAccessor;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.*;
import net.minecraft.client.*;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.settings.*;

/** Native remappable bindings, dispatched once per physical press, including LagSense screens. */
public final class ShortcutBindings {
    public enum Action {REPORT,CAPTURE,SETTINGS,VISIBILITY,FPS}
    static final KeyMapping.Category CATEGORY=new KeyMapping.Category(net.minecraft.resources.Identifier.fromNamespaceAndPath("lagsense","controls"));
    static final IKeyConflictContext CONTEXT=new IKeyConflictContext(){
        public boolean isActive(){var mc=Minecraft.getInstance();return mc!=null&&mc.player!=null&&(mc.gui.screen()==null||mc.gui.screen() instanceof LagScreen);}
        public boolean conflicts(IKeyConflictContext other){return other==this||other==KeyConflictContext.IN_GAME||other==KeyConflictContext.UNIVERSAL;}
    };
    static final KeyMapping REPORT=binding("key.lagsense.report",InputConstants.KEY_F9),CAPTURE=binding("key.lagsense.capture",InputConstants.KEY_F10),SETTINGS=binding("key.lagsense.settings",InputConstants.KEY_F11),VISIBILITY=binding("key.lagsense.overlay",InputConstants.KEY_F12);
    // Existing client frame profiling stays remappable, with no unmodified F-key default.
    static final KeyMapping FPS=new KeyMapping("key.lagsense.fps",CONTEXT,KeyModifier.NONE,InputConstants.UNKNOWN,CATEGORY);
    private static final Map<Action,KeyMapping> MAPPINGS=new EnumMap<>(Action.class);
    private static final Set<InputConstants.Key> held=new HashSet<>();
    private static final ArrayDeque<Action> pending=new ArrayDeque<>();
    static{MAPPINGS.put(Action.REPORT,REPORT);MAPPINGS.put(Action.CAPTURE,CAPTURE);MAPPINGS.put(Action.SETTINGS,SETTINGS);MAPPINGS.put(Action.VISIBILITY,VISIBILITY);MAPPINGS.put(Action.FPS,FPS);}
    private static KeyMapping binding(String name,int key){return new KeyMapping(name,CONTEXT,KeyModifier.CONTROL,InputConstants.Type.KEYBOARD,key,CATEGORY);}
    static List<KeyMapping> all(){return List.of(REPORT,CAPTURE,SETTINGS,VISIBILITY,FPS);}
    public static void key(long window,int action,KeyEvent event){
        var mc=Minecraft.getInstance();if(mc==null||mc.getWindow()==null||mc.getWindow().handle()!=window)return;
        // Preserve vanilla's priority when a user deliberately maps an unmodified global key.
        var key=InputConstants.getKey(event);
        if(action==InputConstants.PRESS&&mc.options!=null&&(mc.options.keyFullscreen.matches(key)||mc.options.keyScreenshot.matches(key))&&!claimsModified(key))return;
        input(key,action);
    }
    static void input(InputConstants.Key key,int action){
        if(!pressEdge(key,action)||!CONTEXT.isActive())return;
        enqueue(KeyMappingLookupAccessor.lagsense$lookup().getAll(key,false));
    }
    static boolean pressEdge(InputConstants.Key key,int action){
        if(action==InputConstants.RELEASE){held.remove(key);return false;}
        if(action!=InputConstants.PRESS)return false;
        for(var mapping:MAPPINGS.values())if(!mapping.isUnbound()&&mapping.getKey().equals(key))return held.add(key);
        return false;
    }
    static void enqueue(List<KeyMapping> nativeMatches){MAPPINGS.forEach((action,mapping)->{if(nativeMatches.contains(mapping))pending.addLast(action);});}
    static List<Action> takeActions(){var actions=List.copyOf(pending);pending.clear();return actions;}
    public static boolean claimsModified(InputConstants.Key key){
        if(!CONTEXT.isActive())return false;
        var matches=KeyMappingLookupAccessor.lagsense$lookup().getAll(key,false);
        return MAPPINGS.values().stream().anyMatch(m->m.getKeyModifier()!=KeyModifier.NONE&&matches.contains(m));
    }
    static void tick(){
        var mc=Minecraft.getInstance();if(mc==null||mc.options==null)return;
        migrateDefaults(LagSenseClient.settings,mc.options);
        // Native click counters also include key repeats. Only raw press edges queued above run actions.
        for(var mapping:MAPPINGS.values())while(mapping.consumeClick()){}
        if(!mc.isWindowActive()){held.clear();pending.clear();return;}
        if(pending.isEmpty())return;
        var actions=takeActions();if(!CONTEXT.isActive())return;
        for(var action:actions)switch(action){
            case REPORT->openProfiler();
            case CAPTURE->startStop();
            case SETTINGS->LagSenseClient.openSettings();
            case VISIBILITY->feedback(VisualVisibility.toggle()?"Performance visuals shown":"Performance visuals hidden");
            case FPS->{if(ClientFrames.active)ClientFrames.stop();else mc.gui.setScreen(new FpsScreen(null));}
        }
    }
    static void openProfiler(){VisualVisibility.reset();LagSenseClient.openLatestReport();}
    public static void startStop(){if(!ClientAccess.request("toggle"))feedback("LagSense is required on the server to open profiling.");}
    static void start(){if(ClientAccess.admin()&&!ClientAccess.request("capture"))feedback("LagSense is required on the server to profile it.");}
    static void feedback(String text){var mc=Minecraft.getInstance();if(mc!=null&&mc.player!=null)mc.player.sendOverlayMessage(Component.literal(text));}
    static String hint(KeyMapping mapping){return mapping.isUnbound()?"":mapping.getTranslatedKeyMessage().getString();}
    static String hudHint(){return hudHint(hint(REPORT),hint(VISIBILITY));}
    static String hudHint(String a,String b){a=a.replace(" + ","+");b=b.replace(" + ","+");return (a.isEmpty()?"":a+" Profiler")+(!a.isEmpty()&&!b.isEmpty()?" | ":"")+(b.isEmpty()?"":b+" Toggle visuals");}
    static void clear(){held.clear();pending.clear();VisualVisibility.reset();}
    static void migrateDefaults(OverlaySettings settings,Options options){
        if(settings.shortcutVersion>=1)return;
        boolean changed=migrate(REPORT,InputConstants.KEY_F9)|migrate(SETTINGS,InputConstants.KEY_F10)|migrate(VISIBILITY,InputConstants.KEY_F8)|migrate(FPS,InputConstants.KEY_F7);
        settings.shortcutVersion=1;settings.save();if(changed){KeyMapping.resetMapping();options.save();}
    }
    static boolean migrate(KeyMapping mapping,int oldKey){if(mapping.getKeyModifier()==KeyModifier.NONE&&mapping.getKey().equals(InputConstants.Type.KEYBOARD.getOrCreate(oldKey))){mapping.setToDefault();return true;}return false;}
    private ShortcutBindings(){}
}
