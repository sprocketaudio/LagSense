package net.sprocketgames.lagsense.client;

import java.util.function.Predicate;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;

/** Reuses native editing, selection, validation and narration inside a flat field. */
public final class UiInput extends EditBox {
    private Predicate<String> filter = value -> true;
    private int maxLength = 32;

    public UiInput(Font font, int x, int y, int w, String label) {
        super(font, x + 5, y + 6, w - 10, 8, Component.literal(label));
        setBordered(false); setTextShadow(false); setTextColor(UiTheme.TEXT); setTextColorUneditable(UiTheme.MUTED);
    }
    void setFilter(Predicate<String> filter) { this.filter = filter; }
    @Override public void setMaxLength(int length) { maxLength = length; super.setMaxLength(length); }
    @Override public void setValue(String value) {
        if (filter == null || filter.test(value)) super.setValue(value);
    }
    @Override public void insertText(String input) {
        String value = getValue();
        String selection = getHighlighted();
        // EditBox exposes the selection contents but keeps the selection endpoints private.
        // LagSense fields use character allowlists, so only the inserted characters need checking.
        String text = StringUtil.filterText(input);
        int capacity = maxLength - value.length() + selection.length();
        if (capacity > 0 && text.length() > capacity) {
            if (Character.isHighSurrogate(text.charAt(capacity - 1))) capacity--;
            text = text.substring(0, capacity);
        }
        if (filter.test(text)) super.insertText(input);
    }
    @Override public boolean isMouseOver(double x, double y) { return x >= getX() - 5 && x < getX() + getWidth() + 5 && y >= getY() - 6 && y < getY() + 14; }
    @Override public net.minecraft.client.gui.navigation.ScreenRectangle getRectangle() { return new net.minecraft.client.gui.navigation.ScreenRectangle(getX()-5,getY()-6,getWidth()+10,20); }
    @Override public boolean mouseClicked(MouseButtonEvent e, boolean twice) {
        if (!active || !isMouseOver(e.x(), e.y())) return false;
        return super.mouseClicked(new MouseButtonEvent(Math.max(getX(), Math.min(getX() + getWidth() - 1, e.x())), getY() + 3, e.buttonInfo()), twice);
    }
    @Override public void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
        setTextColor(UiTheme.TEXT);setTextColorUneditable(UiTheme.MUTED);
        g.fill(getX() - 5, getY() - 6, getX() + getWidth() + 5, getY() + 14, UiTheme.PANEL);
        UiTheme.border(g, getX() - 5, getY() - 6, getWidth() + 10, 20, isFocused() ? UiTheme.ICE : UiTheme.BORDER);
        super.extractWidgetRenderState(g, mx, my, partial); UiHints.widget(this, g, mx, my);
    }
}
