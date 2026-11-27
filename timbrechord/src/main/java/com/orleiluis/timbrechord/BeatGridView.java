package com.orleiluis.timbrechord;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

import com.orleiluis.timbrechord.core.ChordChart;

/**
 * Grade de tempos: cada célula é um tempo da música e o acorde aparece na célula em que ele entra.
 * A linha de cima é a que está tocando (a célula acesa é o tempo de agora); a de baixo é o que vem
 * depois. Células mais escuras são trechos que o app ainda não ouviu.
 */
final class BeatGridView extends View {
    static final int COLS = 8, ROWS = 2;
    private static final int CELL = 0xFF2A323D, UNKNOWN = 0xFF1B2128, TEXT = 0xFFE8EDF2, CARRIED = 0xFF7C8896;
    private static final int ACCENT = 0xFFFFB300, ON_ACCENT = 0xFF101418;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private ChordChart.Grid grid;
    private String shown = "";

    BeatGridView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        paint.setTextAlign(Paint.Align.CENTER);
    }

    /** grid null = grade vazia (nenhuma música tocando). */
    void set(ChordChart.Grid value) {
        String signature = signature(value);
        if (signature.equals(shown)) return;
        shown = signature;
        grid = value;
        invalidate();
    }

    private static String signature(ChordChart.Grid g) {
        if (g == null) return "";
        StringBuilder sb = new StringBuilder().append(g.cursor).append('|').append(g.carried);
        for (int i = 0; i < g.labels.length; i++) sb.append('|').append(g.known[i] ? '1' : '0').append(g.labels[i]);
        return sb.toString();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float gap = density * 2, rowGap = density * 6;
        float cellW = (w - gap * (COLS - 1)) / COLS, cellH = (h - rowGap * (ROWS - 1)) / ROWS;
        float textSize = Math.min(cellH * 0.44f, cellW * 0.42f);
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int i = row * COLS + col;
                float x = col * (cellW + gap), y = row * (cellH + rowGap);
                boolean cursor = grid != null && i == grid.cursor;
                boolean known = grid != null && i < grid.known.length && grid.known[i];
                paint.setColor(cursor ? ACCENT : known ? CELL : UNKNOWN);
                canvas.drawRect(x, y, x + cellW, y + cellH, paint);
                if (grid == null || i >= grid.labels.length) continue;
                String label = grid.labels[i];
                boolean carried = false;
                if (label.isEmpty() && i == 0 && !grid.carried.isEmpty()) {
                    // o acorde que já vinha tocando quando a linha começou
                    label = grid.carried;
                    carried = true;
                }
                if (label.isEmpty()) continue;
                paint.setColor(cursor ? ON_ACCENT : carried ? CARRIED : TEXT);
                paint.setTypeface(carried ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
                paint.setTextSize(textSize);
                float max = cellW - density * 4, measured = paint.measureText(label);
                if (measured > max) paint.setTextSize(textSize * max / measured);
                canvas.drawText(label, x + cellW / 2, y + cellH / 2 - (paint.descent() + paint.ascent()) / 2, paint);
            }
        }
    }
}
