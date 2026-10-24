package com.gathin.tvhome;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * My System TV: the app grid with a fading edge at the top only, so rows dissolve where they scroll under the header
 * (as on tvOS) instead of being cut off; the bottom edge, where the dock sits, is left untouched.
 */
public class TopFadeGrid extends RecyclerView {

    public TopFadeGrid(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected float getBottomFadingEdgeStrength() {
        return 0f;
    }

    // The grid's large top padding is what parks the dock low on the screen, and Android draws a fading edge
    // inside the padding. Offset it back out, so the fade sits on the grid's real top edge, under the header.
    @Override
    protected boolean isPaddingOffsetRequired() {
        return true;
    }

    @Override
    protected int getTopPaddingOffset() {
        return -getPaddingTop();
    }

    @Override
    protected int getLeftPaddingOffset() {
        return -getPaddingLeft();
    }

    @Override
    protected int getRightPaddingOffset() {
        return getPaddingRight();
    }
}
