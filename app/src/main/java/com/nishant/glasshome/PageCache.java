package com.nishant.glasshome;

import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;

import java.util.HashMap;
import java.util.function.Consumer;

/**
 * Records a page (or the dock) once as a GPU display list and replays it with just a translation
 * while you swipe. Only re-records when its content key changes. Android 10+ only.
 */
class PageCache {
    private final HashMap<Integer, RenderNode> nodes = new HashMap<>();
    private final HashMap<Integer, Long> keys = new HashMap<>();

    void draw(Canvas c, int id, long key, float tx, int w, int h, Consumer<Canvas> painter) {
        RenderNode n = nodes.get(id);
        if (n == null) { n = new RenderNode("page" + id); nodes.put(id, n); }
        Long k = keys.get(id);
        if (k == null || k != key || !n.hasDisplayList() || n.getWidth() != w || n.getHeight() != h) {
            n.setPosition(0, 0, w, h);
            RecordingCanvas rc = n.beginRecording(w, h);
            try { painter.accept(rc); } finally { n.endRecording(); }
            keys.put(id, key);
        }
        n.setTranslationX(tx);
        c.drawRenderNode(n);
    }
}
