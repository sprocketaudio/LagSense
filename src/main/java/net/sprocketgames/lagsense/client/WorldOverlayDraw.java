package net.sprocketgames.lagsense.client;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.client.submit.RenderPhaseKeys;

/** Submits overlays to NeoForge's always-on-top pass after translucent terrain. */
interface WorldOverlayDraw {
    void submitCustomGeometry(PoseStack pose,RenderType type,SubmitNodeCollector.CustomGeometryRenderer geometry);
    void submitText(PoseStack pose,float x,float y,FormattedCharSequence text,boolean shadow,Font.DisplayMode mode,int light,int color,int background,int outline);
    default void submitMarkerText(PoseStack pose,float x,float y,FormattedCharSequence text,int color){submitText(pose,x,y,text,false,Font.DisplayMode.SEE_THROUGH,15728880,color,WorldText.background(color),0);}
    default void flush(){}
    static void render(SubmitCustomGeometryEvent event,BiConsumer<PoseStack,WorldOverlayDraw> draw){
        var collector = event.getSubmitNodeCollector();
        draw.accept(event.getPoseStack(), new WorldOverlayDraw() {
            private int order;
            @Override public void flush() { order++; }
            @Override public void submitMarkerText(PoseStack pose,float x,float y,FormattedCharSequence text,int color) {
                WorldText.submit(Minecraft.getInstance().font,text,x,y,color,pose,this,15728880);
                flush();
            }
            @Override public void submitCustomGeometry(PoseStack pose,RenderType type,SubmitNodeCollector.CustomGeometryRenderer geometry) {
                collector.order(order).submitSpecial(RenderPhaseKeys.ALWAYS_ON_TOP,
                    new CustomFeatureRenderer.Submit(pose.last().copy(),type,geometry));
            }
            @Override public void submitText(PoseStack pose,float x,float y,FormattedCharSequence text,boolean shadow,Font.DisplayMode mode,int light,int color,int background,int outline) {
                collector.order(order).submitText(pose,x,y,text,shadow,mode,light,color,background,outline);
            }
        });
    }
}
