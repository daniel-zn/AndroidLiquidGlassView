/*
 * MIT License
 *
 * Copyright (c) 2025-2026 Donny Yang
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.qmdeve.liquidglass;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * The liquid glass lens as a {@link Drawable}, for a view's background: the same shader as
 * {@link com.qmdeve.liquidglass.widget.LiquidGlassView}, over a picture of {@code source} (a view behind the
 * host that is not one of its ancestors, e.g. a wallpaper or a background stack, or a view holding a
 * picture of the window under the host's window, placed in screen coordinates).
 * <p>
 * The source is recorded into a RenderNode only when what the glass sees changes: the first draw, a new
 * size, the glass moving relative to the source, a change in which of the source's children are drawn, the
 * system having dropped the recording (it frees a hidden window's display lists), or
 * {@link #invalidateBackdrop()}. The recording references the source's children's display lists, so their
 * own redraws show through without recording again; and the lens node keeps its GPU layer, so an idle
 * screen (or an animation elsewhere in the window) costs the glass nothing.
 * <p>
 * A positive refraction amount samples outward: the band shows what lies just beyond the glass's edge,
 * drawn in toward the rim, so lines crossing the edge curve into the glass without a break. The source is
 * then recorded with a margin of that reach around the bounds (outside the shape the result is clear).
 */
@RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
public class LiquidGlassDrawable extends Drawable {

    private final RuntimeShader shader;
    private final RenderNode node = new RenderNode("LiquidGlassDrawable");
    private View host;
    private View source;
    private final int[] hostLoc = new int[2];
    private final int[] srcLoc = new int[2];

    // What the last recording was made for.
    private boolean dirty = true;
    private int recW = -1, recH = -1;
    private float recDx = Float.NaN, recDy = Float.NaN;
    private int recSig, recM;
    private boolean effectDirty = true;

    // Parameters (px where a length).
    private float cornerRadius;
    private float refractionHeight, refractionAmount, depthEffect = 0.3f, dispersion = 0.5f;
    private float contrast, whitePoint, chromaMultiplier = 1f;
    private float tintR = 1f, tintG = 1f, tintB = 1f, tintAlpha;
    private float rimLight, rimWidth = 2f, lightX, lightY = -1f;
    private float bandSoftness, rimGlow;
    private int alpha = 255;

    /** Records made (measuring: an idle screen should leave it unchanged). */
    public static int records;

    public LiquidGlassDrawable(@NonNull Resources res) {
        shader = new RuntimeShader(loadRaw(res, R.raw.liquidglass_effect));
    }

    /** The view this is the background of, and the view behind it whose picture the glass bends. */
    public void setSource(@Nullable View host, @Nullable View source) {
        if (this.host == host && this.source == source) return;
        this.host = host;
        this.source = source;
        dirty = true;
        invalidateSelf();
    }

    public View getSource() {
        return source;
    }

    /** Something the recording can't see changed (e.g. a picture swapped in a view of the source): record again. */
    public void invalidateBackdrop() {
        dirty = true;
        invalidateSelf();
    }

    public void setCornerRadius(float px) { if (px != cornerRadius) { cornerRadius = px; effectDirty = true; invalidateSelf(); } }
    /** How far the bending band reaches in from the edge, px. */
    public void setRefractionHeight(float px) { if (px != refractionHeight) { refractionHeight = px; effectDirty = true; invalidateSelf(); } }
    /** How far the band bends what's behind it, px: negative samples inward (magnifies the edge). */
    public void setRefractionAmount(float px) { if (px != refractionAmount) { refractionAmount = px; effectDirty = true; invalidateSelf(); } }
    public void setDepthEffect(float v) { if (v != depthEffect) { depthEffect = v; effectDirty = true; invalidateSelf(); } }
    public void setDispersion(float v) { if (v != dispersion) { dispersion = v; effectDirty = true; invalidateSelf(); } }
    public void setContrast(float v) { if (v != contrast) { contrast = v; effectDirty = true; invalidateSelf(); } }
    /** Toward white (> 0) or black (< 0), 0-1. */
    public void setWhitePoint(float v) { if (v != whitePoint) { whitePoint = v; effectDirty = true; invalidateSelf(); } }
    public void setChromaMultiplier(float v) { if (v != chromaMultiplier) { chromaMultiplier = v; effectDirty = true; invalidateSelf(); } }
    public void setTint(float r, float g, float b, float a) {
        if (r == tintR && g == tintG && b == tintB && a == tintAlpha) return;
        tintR = r; tintG = g; tintB = b; tintAlpha = a; effectDirty = true; invalidateSelf();
    }
    /** A light line along the edges facing (lightX, lightY) and the opposite ones (0 = none), [widthPx] wide. */
    /** 0 = circular edge profile (default); 1 = a long smooth falloff into the flat centre. */
    public void setBandSoftness(float v) { if (v != bandSoftness) { bandSoftness = v; effectDirty = true; invalidateSelf(); } }
    /** A soft rim glow across the band, fading out at its inner end (0 = none, the default). */
    public void setRimGlow(float v) { if (v != rimGlow) { rimGlow = v; effectDirty = true; invalidateSelf(); } }

    public void setRimLight(float strength, float widthPx, float lightX, float lightY) {
        if (strength == rimLight && widthPx == rimWidth && lightX == this.lightX && lightY == this.lightY) return;
        rimLight = strength; rimWidth = widthPx; this.lightX = lightX; this.lightY = lightY;
        effectDirty = true; invalidateSelf();
    }

    /** Which of the source's direct children are drawn (a change needs a new recording). */
    private int sourceSignature() {
        View s = source;
        int h = s.getWidth() * 31 + s.getHeight();
        if (s instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) s;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                h = h * 31 + System.identityHashCode(c);
                h = h * 31 + c.getVisibility();
            }
        }
        return h;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        int w = b.width(), h = b.height();
        if (w <= 0 || h <= 0 || host == null || source == null || !canvas.isHardwareAccelerated()) return;
        // Screen positions: the source may be in another window than the host (a popup over a picture
        // of the window under it); in one window this is the same difference as window positions.
        host.getLocationOnScreen(hostLoc);
        source.getLocationOnScreen(srcLoc);
        float dx = hostLoc[0] + b.left - srcLoc[0];
        float dy = hostLoc[1] + b.top - srcLoc[1];
        int sig = sourceSignature();
        int m = margin();
        // !hasDisplayList(): the system dropped the recording (it frees every display list of a window
        // that is hidden, e.g. while another app is in front); without this the lens would draw nothing.
        if (dirty || !node.hasDisplayList() || w != recW || h != recH || dx != recDx || dy != recDy
                || sig != recSig || m != recM) {
            node.setPosition(0, 0, w + 2 * m, h + 2 * m);
            Canvas rec = node.beginRecording(w + 2 * m, h + 2 * m);
            try {
                rec.translate(-dx + m, -dy + m);
                source.draw(rec);
            } finally {
                node.endRecording();
            }
            records++;
            if (w != recW || h != recH || m != recM) effectDirty = true;
            recW = w; recH = h; recDx = dx; recDy = dy; recSig = sig; recM = m;
            dirty = false;
        }
        if (effectDirty) applyEffect(w, h);
        int save = canvas.save();
        canvas.translate(b.left - m, b.top - m);
        canvas.drawRenderNode(node);
        canvas.restoreToCount(save);
    }

    /** How far outside the bounds the lens samples (px): the outward refraction plus its colour split. */
    private int margin() {
        if (refractionAmount <= 0f) return 0;
        return (int) Math.ceil(refractionAmount * (1f + Math.abs(dispersion))) + 1;
    }

    private void applyEffect(int w, int h) {
        float r = Math.min(cornerRadius, Math.min(w, h) / 2f);
        int m = margin();
        shader.setFloatUniform("size", w, h);
        shader.setFloatUniform("offset", -m, -m);
        shader.setFloatUniform("cornerRadii", r, r, r, r);
        shader.setFloatUniform("refractionHeight", Math.max(0.01f, refractionHeight));
        shader.setFloatUniform("refractionAmount", refractionAmount);
        shader.setFloatUniform("depthEffect", depthEffect);
        shader.setFloatUniform("chromaticAberration", dispersion);
        shader.setFloatUniform("contrast", contrast);
        shader.setFloatUniform("whitePoint", whitePoint);
        shader.setFloatUniform("chromaMultiplier", chromaMultiplier);
        shader.setFloatUniform("tintColor", tintR, tintG, tintB);
        shader.setFloatUniform("tintAlpha", tintAlpha);
        shader.setFloatUniform("rimLight", rimLight);
        shader.setFloatUniform("rimWidth", Math.max(0.5f, rimWidth));
        shader.setFloatUniform("lightDir", lightX, lightY);
        shader.setFloatUniform("alpha", alpha / 255f);
        shader.setFloatUniform("bandSoftness", bandSoftness);
        shader.setFloatUniform("rimGlow", rimGlow);
        node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"));
        effectDirty = false;
    }

    @Override
    public void setAlpha(int a) {
        if (a == alpha) return;
        alpha = a;
        effectDirty = true;
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return alpha;
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    private static String loadRaw(Resources res, int id) {
        try (InputStream in = res.openRawResource(id);
             BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (IOException e) {
            throw new RuntimeException("Error loading shader: " + id, e);
        }
    }
}
