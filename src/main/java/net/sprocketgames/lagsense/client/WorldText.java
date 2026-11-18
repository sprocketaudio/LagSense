package net.sprocketgames.lagsense.client;

import net.sprocketgames.lagsense.mixin.*;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import java.util.*;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.rendertype.*;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

/** Native font layout/atlases, with glyph coverage applied once rather than twice. */
final class WorldText {
    static final RenderPipeline INTENSITY=pipeline("intensity",true),COLOR=pipeline("color",false);
    private static final Map<RenderType,RenderType> TYPES=new IdentityHashMap<>();
    private static RenderPipeline pipeline(String name,boolean intensity){
        var builder=RenderPipeline.builder(RenderPipelines.TEXT_SNIPPET).withLocation(Identifier.fromNamespaceAndPath("lagsense","world_text_"+name))
            .withVertexShader(Identifier.fromNamespaceAndPath("lagsense","core/world_text")).withFragmentShader(Identifier.fromNamespaceAndPath("lagsense","core/world_text"))
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS,false));
        if(intensity)builder.withShaderDefine("INTENSITY");return builder.build();
    }
    static void clear(){TYPES.clear();}
    private static RenderType type(TextRenderable glyph){
        var original=glyph.renderType(Font.DisplayMode.SEE_THROUGH);var cached=TYPES.get(original);if(cached!=null)return cached;
        var setup=((WorldTextRenderTypeAccessor)(Object)original).lagsense$setup();
        var binding=((WorldTextSetupAccessor)(Object)setup).lagsense$textures().get("Sampler0");
        if(binding==null)return original;
        var atlas=((WorldTextBindingAccessor)binding).lagsense$location();
        boolean intensity=original.pipeline().getShaderDefines().flags().contains("IS_GRAYSCALE");
        var value=RenderType.create("lagsense_world_text",RenderSetup.builder(intensity?INTENSITY:COLOR)
            .withTexture("Sampler0",atlas,()->RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)).createRenderSetup());
        TYPES.put(original,value);return value;
    }
    static int background(int color){return 0xff08080b;}
    static Font.PreparedText prepare(Font font,FormattedCharSequence text,float x,float y,int color){
        return font.prepareText(text,x,y,color,false,false,background(color));
    }
    static void submit(Font font,FormattedCharSequence text,float x,float y,int color,PoseStack pose,WorldOverlayDraw draw,int light){
        prepare(font,text,x,y,color).visit(new Font.GlyphVisitor(){
            @Override public void acceptGlyph(TextRenderable.Styled glyph){submitPart(glyph);}
            @Override public void acceptEffect(TextRenderable effect){
                submitPart(effect);
                // Feature rendering batches by type within each order. Draw the backing before the glyphs.
                draw.flush();
            }
            private void submitPart(TextRenderable part){
                draw.submitCustomGeometry(pose,type(part),(transform,buffer)->part.render(transform.pose(),buffer,light,false));
            }
        });
    }
    private WorldText(){}
}
