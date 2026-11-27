package com.orleiluis.timbrechord;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import com.orleiluis.timbrechord.core.ChordShapes;

/**
 * Faixa com três acordes desenhados no braço do violão: o anterior, o atual (em destaque, no meio)
 * e o próximo. Tudo é desenhado aqui, sem imagens, para ficar nítido em qualquer tela.
 */
final class ChordStripView extends View {
    private static final int BRIGHT = 0xFFFFFFFF, DIM = 0xFF6B7785, ACCENT = 0xFFFFB300, FAINT = 0xFF4A545F;
    private static final int FRETS = 4;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private String previous = "", current = "", next = "";
    private boolean nextIsGuess;

    ChordStripView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        paint.setTextAlign(Paint.Align.CENTER);
    }

    /**
     * @param nextIsGuess o próximo acorde é um palpite (a música ainda não foi ouvida até ali)
     */
    void set(String previous, String current, String next, boolean nextIsGuess) {
        if (previous == null) previous = "";
        if (current == null) current = "";
        if (next == null) next = "";
        if (previous.equals(this.previous) && current.equals(this.current) && next.equals(this.next)
                && nextIsGuess == this.nextIsGuess) return;
        this.previous = previous;
        this.current = current;
        this.next = next;
        this.nextIsGuess = nextIsGuess;
        StringBuilder say = new StringBuilder(current.isEmpty() ? "Sem acorde" : "Acorde " + current);
        if (!next.isEmpty()) say.append(nextIsGuess ? ", próximo provável " : ", próximo ").append(next);
        setContentDescription(say);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float side = w * 0.30f, middle = w - 2 * side;
        // o acorde do meio usa toda a altura; os dos lados são menores e apagados
        float big = Math.min(middle * 0.62f, h / 1.95f);
        float small = Math.min(Math.min(side * 0.66f, big * 0.80f), h / 1.95f);
        drawChord(canvas, previous, side / 2, h, small, DIM, DIM, false);
        drawChord(canvas, current, side + middle / 2, h, big, BRIGHT, ACCENT, true);
        drawChord(canvas, next, w - side / 2, h, small, nextIsGuess ? FAINT : DIM, DIM, false);
    }

    /**
     * @param cx    centro horizontal do espaço deste acorde
     * @param width largura do braço desenhado (da 6ª à 1ª corda)
     */
    private void drawChord(Canvas canvas, String chord, float cx, float h, float width, int lineColor, int nameColor, boolean main) {
        float nameSize = width * (main ? 0.34f : 0.30f);
        float gridH = width * 1.18f;
        float marks = width * 0.16f;                 // faixa do × e da corda solta, acima do braço
        float block = marks + gridH + nameSize * 1.35f;
        float top = (h - block) / 2 + marks;
        float nameY = top + gridH + nameSize * 1.12f;

        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setStyle(Paint.Style.FILL);
        if (chord.isEmpty()) {
            if (!main) return;
            // sem acorde (silêncio ou ainda sem som)
            paint.setColor(DIM);
            paint.setTextSize(width * 0.5f);
            canvas.drawText("—", cx, h / 2 + width * 0.16f, paint);
            return;
        }
        ChordShapes.Shape shape = ChordShapes.of(chord);
        String label = chord + (!main && nextIsGuess && lineColor == FAINT ? "?" : "");
        paint.setColor(nameColor);
        paint.setTextSize(nameSize);
        // nomes compridos (F#maj7) encolhem para caber
        float max = width * 1.5f, measured = paint.measureText(label);
        if (measured > max) paint.setTextSize(nameSize * max / measured);
        if (shape == null) {
            canvas.drawText(label, cx, h / 2 + nameSize * 0.35f, paint);
            return;
        }
        canvas.drawText(label, cx, nameY, paint);

        float left = cx - width / 2, gap = width / 5, fretH = gridH / FRETS;
        float thin = Math.max(1f, density * (main ? 1.3f : 1f));
        paint.setColor(lineColor);
        paint.setStrokeWidth(thin);
        for (int s = 0; s < 6; s++) canvas.drawLine(left + s * gap, top, left + s * gap, top + gridH, paint);
        for (int f = 0; f <= FRETS; f++) canvas.drawLine(left, top + f * fretH, left + width, top + f * fretH, paint);
        if (shape.baseFret == 1) {
            // pestana do braço: barra grossa em cima
            canvas.drawRect(left - thin / 2, top - fretH * 0.13f, left + width + thin / 2, top + thin / 2, paint);
        } else {
            // acorde no meio do braço: o número da primeira casa desenhada fica à esquerda
            paint.setTextSize(fretH * 0.52f);
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(String.valueOf(shape.baseFret), left - gap * 0.45f, top + fretH * 0.5f + fretH * 0.18f, paint);
            paint.setTextAlign(Paint.Align.CENTER);
        }

        float r = gap * 0.36f, markY = top - marks * 0.62f, m = gap * 0.22f;
        for (int s = 0; s < 6; s++) {
            int fret = shape.frets[s];
            float x = left + s * gap;
            if (fret < 0) {
                canvas.drawLine(x - m, markY - m, x + m, markY + m, paint);
                canvas.drawLine(x - m, markY + m, x + m, markY - m, paint);
            } else if (fret == 0) {
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawCircle(x, markY, m, paint);
                paint.setStyle(Paint.Style.FILL);
            } else {
                boolean underBarre = shape.barreFret == fret && s >= shape.barreFrom && s <= shape.barreTo;
                if (!underBarre) canvas.drawCircle(x, top + (fret - shape.baseFret + 0.5f) * fretH, r, paint);
            }
        }
        if (shape.barreFret > 0) {
            float y = top + (shape.barreFret - shape.baseFret + 0.5f) * fretH;
            rect.set(left + shape.barreFrom * gap - r, y - r, left + shape.barreTo * gap + r, y + r);
            canvas.drawRoundRect(rect, r, r, paint);
        }
    }
}
