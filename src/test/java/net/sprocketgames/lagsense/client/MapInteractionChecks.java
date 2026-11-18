package net.sprocketgames.lagsense.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.sprocketgames.lagsense.*;
import java.util.*;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

final class MapInteractionChecks {
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    private static Object field(Object target,String name)throws Exception{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void set(Object target,String name,Object value)throws Exception{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    static void run()throws Exception{
        var report=new Report(101,1000,100,10,true,false,List.of(
            new Report.Entry("Block","fixture:machine","minecraft:overworld",0,64,0,1000000,100),
            new Report.Entry("Block","fixture:machine","minecraft:overworld",16,64,0,500000,100))).view(true,List.of());
        for(int scale:List.of(2,3)){
            var screen=new HeatmapScreen(null,report,false);screen.width=1920/scale;screen.height=1080/scale;screen.init();
            double x=screen.width/2.0,y=(int)field(screen,"mapTop")+10;
            check(screen.mouseScrolled(x,y,0,1)&&(int)field(screen,"cell")==22,"Wheel up zooms in over the map");
            check(screen.mouseScrolled(x,y,0,-1)&&(int)field(screen,"cell")==18,"Wheel down zooms out over the map");
            for(int i=0;i<3;i++)screen.mouseScrolled(x,y,0,.25);
            check((int)field(screen,"cell")==18,"Trackpad deltas accumulate without excessive zoom jumps");
            screen.mouseScrolled(x,y,0,.25);check((int)field(screen,"cell")==22,"One accumulated notch uses the existing zoom step");
            screen.mouseScrolled(x,y,0,.5);screen.mouseScrolled(x,y,0,-.5);check((int)field(screen,"cell")==22,"Direction reversal clears fractional motion");
            screen.mouseScrolled(x,y,0,-.5);check((int)field(screen,"cell")==18,"Reversed trackpad motion accumulates normally");
            screen.mouseScrolled(x,20,0,1);screen.mouseScrolled(x,screen.height-40,0,-1);screen.mouseScrolled(0,y,0,1);
            screen.mouseScrolled(x,y,1,0);screen.mouseScrolled(x,y,0,Double.NaN);screen.mouseScrolled(x,y,0,Double.POSITIVE_INFINITY);
            check((int)field(screen,"cell")==18,"Headers, footers, horizontal and invalid deltas cannot change zoom");
            for(int i=0;i<50;i++)screen.mouseScrolled(x,y,0,1);check((int)field(screen,"cell")==34,"Existing maximum remains unchanged");
            for(int i=0;i<50;i++)screen.mouseScrolled(x,y,0,-1);check((int)field(screen,"cell")==2,"Existing minimum remains unchanged");
            check(field(screen,"plan")==null,"Wheel hit testing does not rebuild map geometry");
            var buttons=ProfileLayoutChecks.widgets(screen).stream().filter(UiButton.class::isInstance).map(UiButton.class::cast).toList();
            check(buttons.stream().noneMatch(b->List.of("+","−","Left","Right","Up","Down").contains(b.getMessage().getString())),"Wheel/drag replace duplicate zoom and direction buttons");
            for(String label:List.of("Hottest","Fit")){
                var button=buttons.stream().filter(b->b.getMessage().getString().equals(label)).findFirst().orElseThrow();button.setFocused(true);
                check(!(boolean)field(button,"selected")&&field(button,"role")==UiButton.Role.SECONDARY,"Hottest and Fit remain one-shot actions");
                check(!UiButton.highlighted(false,button.isFocused(),false)&&UiButton.highlighted(false,button.isFocused(),true),"Mouse focus never leaves a selected-looking highlight");
            }
            check(field(screen,"selected")==report.chunks().getFirst(),"Zoom preserves the selected chunk");
            checkPointerInput(report,screen.width,screen.height);
        }
        var gesture=new MapInteraction();gesture.begin(100,100);check(gesture.drag(101,101).equals(new MapInteraction.Shift(0,0))&&gesture.release(),"Small pointer jitter remains a click");
        gesture.begin(100,100);check(gesture.drag(120,110).equals(new MapInteraction.Shift(20,10))&&!gesture.release(),"Dragging pans and suppresses chunk activation");
        gesture.begin(10,10);gesture.cancel();check(!gesture.release(),"Leaving a screen cancels pending map clicks");
        var viewport=new MapInteraction.Viewport(10,100,900,500);
        for(int cell:List.of(2,6,18,30,34))for(int step:List.of(-1,1)){
            var zoom=MapInteraction.zoom(viewport,-123.25,75.75,cell,711,234,step);
            double beforeX=-123.25+(711-viewport.midX())/cell,beforeZ=75.75+(234-viewport.midY())/cell;
            check(Math.abs(beforeX-zoom.x()-(711-viewport.midX())/zoom.cell())<1e-9&&Math.abs(beforeZ-zoom.z()-(234-viewport.midY())/zoom.cell())<1e-9,"Wheel zoom anchors the world coordinate under the cursor, including negative chunks");
            var shape=MapInteraction.geometry(viewport,zoom.x(),zoom.z(),zoom.cell());
            check(shape.left()<=viewport.left()&&shape.top()<=viewport.top()&&shape.left()+shape.columns()*zoom.cell()>=viewport.right()&&shape.top()+shape.rows()*zoom.cell()>=viewport.bottom(),"Fractional panning always covers the full map viewport");
        }
        var state=new ProfileArchive.State(ProfileArchive.Settings.defaults(),List.of());var history=new HistoryScreen(null,state,"");history.width=640;history.height=360;history.init();
        set(history,"baseline",123L);var count=(net.minecraft.client.gui.components.EditBox)field(history,"count");count.setValue("12");
        for(String status:List.of("Loading…","Comparing…")){set(history,"message",status);history.removed();check(field(history,"message").equals("")&&field(history,"baseline").equals(123L)&&count.getValue().equals("12")&&field(history,"state")==state,"Completed history navigation clears transient loading while preserving the draft");}
        for(String status:List.of("Applying…","Saved profile unavailable.","Settings applied.")){set(history,"message",status);history.removed();check(field(history,"message").equals(status),"History retains actual setting and error messages");}
        System.out.println("Map/history checks passed (native mouse input, Mark sizing, wheel/trackpad zoom, cursor anchoring, viewport coverage and navigation status).");
    }
    private static void checkPointerInput(Report report,int width,int height)throws Exception{
        var screen=new HeatmapScreen(null,report,false);
        screen.width=width;
        screen.height=height;
        screen.init();
        var selected=report.chunks().getFirst();
        var mark=ProfileLayoutChecks.widgets(screen).stream().filter(UiButton.class::isInstance).map(UiButton.class::cast)
            .filter(button->button.getMessage().getString().equals("Mark")).findFirst().orElseThrow();
        var originalMarker=LagSenseClient.waypoint;
        try{
            LagSenseClient.waypoint=HistoricalTargets.chunk(selected);
            mark.refreshAvailability();
            check(mark.getMessage().getString().equals("Unmark")&&mark.getWidth()>=UiFonts.labelWidth("Unmark")+10,
                "The map's marked state keeps the full Unmark label visible");
        }finally{LagSenseClient.waypoint=originalMarker;}

        double x=screen.width/2.0,y=(int)field(screen,"mapTop")+10;
        var left=new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0);
        var right=new MouseButtonInfo(InputConstants.MOUSE_BUTTON_RIGHT,0);
        check(!screen.mouseClicked(new MouseButtonEvent(x,y,right),false),"Right-click on an empty tile does not inspect or start a gesture");
        double center=(double)field(screen,"centerX");
        check(screen.mouseClicked(new MouseButtonEvent(x,y,left),false),"Native left press starts a map gesture");
        check(screen.mouseDragged(new MouseButtonEvent(x+24,y,left),24,0),"Native left drag pans the map");
        check(screen.mouseReleased(new MouseButtonEvent(x+24,y,left)),"Native left release ends the drag");
        check((double)field(screen,"centerX")==center-24.0/(int)field(screen,"cell")&&field(screen,"selected")==selected,
            "Dragging changes the map centre and preserves the selected chunk");

        var click=new MouseButtonEvent(x,y,left);
        check(screen.mouseClicked(click,false)&&screen.mouseReleased(new MouseButtonEvent(x,screen.height-20,left)),
            "Releasing a map press outside the viewport consumes the release");
        check(!((MapInteraction)field(screen,"gesture")).pressed()&&field(screen,"selected")==selected,
            "Releasing outside the map ends the gesture without changing selection");

        var other=report.chunks().getLast();
        var getMap=HeatmapScreen.class.getDeclaredMethod("map");
        getMap.setAccessible(true);
        var plan=(HeatmapPlan)getMap.invoke(screen);
        var tile=new MouseButtonEvent(plan.pixelX(plan.column(other.x()))+8,plan.pixelY(plan.row(other.z()))+8,left);
        check(screen.mouseClicked(tile,false)&&screen.mouseReleased(tile)&&field(screen,"selected")==other,
            "Left-click changes the selected measured chunk without opening another screen");
        var footer=ProfileLayoutChecks.widgets(screen).stream().filter(w->w.getY()==screen.footer()).toList();
        var newMark=footer.stream().filter(w->w.getMessage().getString().equals("Mark")).findFirst().orElseThrow();
        var inspect=footer.stream().filter(w->w.getMessage().getString().equals("Inspect")).findFirst().orElseThrow();
        check(!footer.contains(mark)&&newMark.getX()+newMark.getWidth()+4==inspect.getX(),
            "The new chunk gets its own Mark and adjacent Inspect actions");
        var empty=new MouseButtonEvent(screen.margin()+1,y,left);
        check(screen.mouseClicked(empty,false)&&screen.mouseReleased(empty)&&field(screen,"selected")==null,
            "Left-click on an empty tile clears the chunk selection");
        check(!screen.children().contains(newMark)&&!screen.children().contains(inspect),
            "An empty selection removes the chunk actions");
    }
}
