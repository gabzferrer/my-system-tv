package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Draws the tvOS style lighter band behind the first (dock) row and adds a gap below it. */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    /** Extra band below the tiles for their names (always_show_names), in px. */
    private final float namesRoom;
    /** Light glass like the top bar's pills (config.xml dock_glass): a white tint that fades downwards. */
    private final boolean lightGlass;
    /** Padding all round the tiles equal to the gap between them (config.xml dock_even_padding). */
    private final boolean evenPadding;
    /** The band's top and bottom the glass gradient was made for; it is remade when they move. */
    private float shaderTop = Float.NaN, shaderBottom = Float.NaN;

    /**
     * @param translucent the cinematic shelf's band: the artwork and the glow show through it.
     * @param names       the dock tiles show their names, so the band reaches down over them.
     * @param opacity     the plain band's opacity in percent (dock_opacity), so a wallpaper can show through it.
     * @param lightGlass  a light frosted band like the pills, for a sky or wallpaper behind it.
     * @param evenPadding the band's padding is the gap between the tiles on all four sides (dock_even_padding).
     */
    DockDecoration(int dockSize, float density, boolean translucent, boolean names, int opacity, boolean lightGlass,
                   boolean evenPadding) {
        this.dockSize = dockSize;
        this.density = density;
        this.namesRoom = names ? 22 * density : 0;
        this.lightGlass = lightGlass;
        this.evenPadding = evenPadding;
        // With light glass the shaders carry the alpha; an alpha in the paint colour would multiply into them.
        band.setColor(lightGlass ? 0xFFFFFFFF : translucent ? 0x8C121014 : (Math.round(opacity * 2.55f) << 24) | 0x141416);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(lightGlass ? 1.5f * density : density);
        edge.setColor(lightGlass ? 0xFFFFFFFF : translucent ? 0x26FFFFFF : 0x1FFFFFFF);
    }

    /** Concentric corners: the band's radius is the tiles' radius plus the band's padding around them. */
    static float radiusPx(float tileRadiusPx, float paddingPx) {
        return tileRadiusPx + paddingPx;
    }

    /**
     * The band's padding on every side for dock_even_padding: the gap between two dock tiles (a cell minus its tile, as
     * AppAdapter.tileWidthPx lays them out), capped by the room the grid leaves at its edges.
     */
    static float evenPaddingPx(int innerWidthPx, int columns, int tileWidthPx, float roomPx) {
        float gap = columns > 0 ? innerWidthPx / (float) columns - tileWidthPx : 0f;
        return Math.max(0f, Math.min(gap, roomPx));
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && pos < dockSize) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (12 * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        boolean any = false;
        int cardWidth = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            RecyclerView.ViewHolder vh = parent.getChildViewHolder(child);
            View card = vh instanceof AppAdapter.Holder ? ((AppAdapter.Holder) vh).card : child.findViewById(R.id.card);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            cardWidth = card.getWidth();
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) return;
        float pad = 14 * density;
        // The band can only draw inside the grid, and the first and last dock tiles sit 12dp in from its
        // edges: a full 14dp at the sides sliced the rounded ends flat (#454). Keep the sides, with the
        // 1dp edge line, inside the grid; the top and bottom have room.
        // 22dp at the sides (the grid's 10dp padding makes room): a focused first or last tile grows about 7dp
        // outwards and still keeps about the band's top margin from its rounded end.
        float sidePad = 22 * density;
        float side = Math.max(0f, Math.min(sidePad, Math.min(rect.left, parent.getWidth() - rect.right) - density));
        float radius = AppAdapter.cornerRadius(cardWidth, density);
        if (evenPadding) {
            // The same padding on all four sides, so the band's corners are concentric with the tiles' everywhere.
            int inner = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
            float room = Math.min(rect.top, Math.min(rect.left, parent.getWidth() - rect.right)) - density;
            float even = evenPaddingPx(inner, dockSize, cardWidth, room);
            rect.inset(-even, -even);
            radius = radiusPx(radius, even);
        } else {
            // dock_even_padding off: the band as before, with its fixed 26 dp corners.
            rect.inset(-side, -pad);
            radius = 26 * density;
        }
        rect.bottom += namesRoom;
        if (lightGlass && (rect.top != shaderTop || rect.bottom != shaderBottom)) {
            // Liquid-glass look, My System TV in black: a dark translucent tint (it was white) under a rim lit
            // from above that fades down the sides, so it still reads as glass over a light video.
            band.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, 0x66000000, 0x4D000000,
                    Shader.TileMode.CLAMP));
            edge.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                    new int[] {0x73FFFFFF, 0x1AFFFFFF, 0x26FFFFFF}, new float[] {0f, 0.55f, 1f},
                    Shader.TileMode.CLAMP));
            shaderTop = rect.top;
            shaderBottom = rect.bottom;
        }
        c.drawRoundRect(rect, radius, radius, band);
        c.drawRoundRect(rect, radius, radius, edge);
    }
}
