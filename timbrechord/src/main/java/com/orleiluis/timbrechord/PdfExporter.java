package com.orleiluis.timbrechord;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Transforma o texto da cadeia em PDF (A4), com títulos em destaque e quebra de linha automática. */
final class PdfExporter {
    private PdfExporter() {}

    private static final int WIDTH = 595, HEIGHT = 842, MARGIN = 44;

    static byte[] create(String text) throws IOException {
        PdfDocument doc = new PdfDocument();
        Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        body.setTextSize(10.5f);
        body.setColor(0xFF111111);
        Paint heading = new Paint(Paint.ANTI_ALIAS_FLAG);
        heading.setTextSize(13f);
        heading.setColor(0xFF000000);
        heading.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        int pageNumber = 1;
        PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, pageNumber).create());
        Canvas canvas = page.getCanvas();
        float y = MARGIN;
        String[] lines = text.split("\n", -1);
        try {
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (isRule(line)) continue;
                boolean isHeading = i + 1 < lines.length && isRule(lines[i + 1]);
                Paint paint = isHeading ? heading : body;
                float lineHeight = paint.getTextSize() * 1.45f;
                int indentChars = 0;
                while (indentChars < line.length() && line.charAt(indentChars) == ' ') indentChars++;
                float indent = indentChars * body.measureText(" ");
                String rest = line.substring(indentChars);
                if (isHeading) y += 8;
                if (rest.isEmpty()) { y += lineHeight * 0.6f; continue; }
                while (!rest.isEmpty()) {
                    int count = paint.breakText(rest, true, WIDTH - 2 * MARGIN - indent, null);
                    if (count <= 0) count = 1;
                    if (count < rest.length()) {
                        int space = rest.lastIndexOf(' ', count);
                        if (space > 0) count = space;
                    }
                    if (y + lineHeight > HEIGHT - MARGIN) {
                        doc.finishPage(page);
                        page = doc.startPage(new PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, ++pageNumber).create());
                        canvas = page.getCanvas();
                        y = MARGIN;
                    }
                    y += lineHeight;
                    canvas.drawText(rest.substring(0, count), MARGIN + indent, y, paint);
                    rest = rest.substring(count).trim();
                }
                if (isHeading) y += 3;
            }
            doc.finishPage(page);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.writeTo(out);
            return out.toByteArray();
        } finally {
            doc.close();
        }
    }

    /** Linha só de = ou - (sublinhado de título no texto). */
    private static boolean isRule(String line) {
        if (line.length() < 3) return false;
        char c = line.charAt(0);
        if (c != '=' && c != '-') return false;
        for (int i = 1; i < line.length(); i++) if (line.charAt(i) != c) return false;
        return true;
    }
}
