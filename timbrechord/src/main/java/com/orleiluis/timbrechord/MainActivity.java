package com.orleiluis.timbrechord;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputFilter;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.database.Cursor;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.orleiluis.timbrechord.core.AiToneEngine;
import com.orleiluis.timbrechord.core.ChainExporter;
import com.orleiluis.timbrechord.core.ChordChart;
import com.orleiluis.timbrechord.core.EffectCatalog;
import com.orleiluis.timbrechord.core.LiveAnalyzer;
import com.orleiluis.timbrechord.core.LocalToneEngine;
import com.orleiluis.timbrechord.core.PrstWriter;
import com.orleiluis.timbrechord.core.ToneAnalyzer;
import com.orleiluis.timbrechord.core.ToneChain;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Tela única: no topo, o botão Separar faixas e o botão Arquivo; embaixo, as abas Acordes (ao vivo) e
 * Timbre (cadeia de efeitos + arquivos). A música pode vir de dois lugares:
 *  - um arquivo de música do aparelho (botão Arquivo), tocado pelo próprio app; esse pode ser separado
 *    em 6 faixas (voz, guitarra, baixo, bateria, piano, outros) com uma barra de volume para cada;
 *  - qualquer outro app (YouTube, player de música): basta tocar em Ouvir e dar play lá.
 */
public class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 10, REQ_PROJECTION = 11, REQ_FILE = 13;
    private static final String IDLE_HINT = "Abra um arquivo de música, ou toque em Ouvir e dê play em qualquer app (YouTube, player de música).";
    private static final int ACCENT = 0xFFFFB300, PANEL = 0xFF1A2028, MUTED = 0xFF9AA7B4;
    private static final float SPEED_MIN = 0.25f, SPEED_STEP = 0.05f;
    private static final int SPEED_MAX_STEP = 35, SPEED_NORMAL_STEP = 15;

    private EditText nameField;
    private TextView speedLabel;
    private SeekBar speedBar;
    private LinearLayout speedRow;
    /** Velocidade do player (1 = normal). O tom da música não muda, só o andamento. */
    private float playbackRate = 1f;
    private TextView infoView, sampleView, resultView, statusView;
    private ChordStripView stripView;
    private BeatGridView gridView;
    private Button listenButton, chordsTab, toneTab, generateButton;
    private Spinner sourceSpinner, styleSpinner;
    private CheckBox simpleBox, aiBox, bubbleBox;
    private LinearLayout fileRow;
    private TextView fileTitle, fileNote, fileTime;
    private PlayButton filePlay;
    private String shownFileError = "";
    private SeekBar fileSeek;
    private boolean seeking;
    private boolean toneTabShown;
    private LinearLayout tabs;
    private LinearLayout chordPanel;
    private ScrollView tonePanel;

    /** O som sai do alto-falante um pouco depois de o arquivo ser decodificado (a fila do áudio). */
    private static final double FILE_OUT_DELAY = 0.12;
    private Uri fileUri;
    private String fileName = "";

    // ---- separação de faixas
    private Button stemsButton, stemAction, stemReset, stemDelete;
    private ScrollView stemPanel;
    private TextView stemInfo, stemNote;
    private ProgressBar stemProgress;
    private LinearLayout stemRows;
    private String stemRowsFor = "";
    private boolean stemPanelShown;
    private Boolean modelAvailable;
    private LinearLayout savedList;
    private TextView savedTitle;
    /** A lista de músicas separadas precisa ser lida de novo do disco. */
    private boolean savedDirty = true;
    private String savedShownFor = "";
    private final java.util.List<StemRow> rows = new java.util.ArrayList<>();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ToneChain lastChain;
    private int tickCount;
    private String shownError = "";
    private boolean generating;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 150);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        showTab(false);
        if (savedInstanceState != null && savedInstanceState.getBoolean("stems", false)) showStemPanel(true);
        // a tela pode ter sido recriada com um arquivo tocando (o player sobrevive a ela)
        if (FilePlayer.shared.isLoaded()) fileName = FilePlayer.shared.title();
        // só na primeira abertura: ao recriar a tela, o arquivo compartilhado antigo não deve abrir de novo
        // (isso fecharia o arquivo que está tocando)
        if (savedInstanceState == null) handleShare(getIntent());
        String interrupted = SeparationJob.interruptedMessage(this);
        if (!interrupted.isEmpty())
            new AlertDialog.Builder(this).setTitle("Separação interrompida").setMessage(interrupted).setPositiveButton("OK", null).show();
        // carrega o catálogo de efeitos em segundo plano, para o primeiro "Gerar timbre" sair rápido
        new Thread(new Runnable() {
            @Override
            public void run() {
                try { AppState.catalog(MainActivity.this); } catch (Exception ignored) { }
            }
        }).start();
    }

    // ------------------------------------------------------------------ interface

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    private TextView label(String text, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String text, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(click);
        return b;
    }

    private View buildUi() {
        final int wrap = ViewGroup.LayoutParams.WRAP_CONTENT, match = ViewGroup.LayoutParams.MATCH_PARENT;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(0xFF101418);

        // barra de cima: separação de faixas e arquivo de música
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(4), dp(8), dp(4));
        stemsButton = button("Separar faixas", new View.OnClickListener() {
            @Override
            public void onClick(View v) { showStemPanel(!stemPanelShown); }
        });
        stemsButton.setTextColor(ACCENT);
        bar.addView(stemsButton, lp(0, wrap, 1));
        bar.addView(button("Arquivo", new View.OnClickListener() {
            @Override
            public void onClick(View v) { pickFile(); }
        }), lp(wrap, wrap, 0));
        root.addView(bar, lp(match, wrap, 0));

        // ---- arquivo de música tocando
        fileRow = new LinearLayout(this);
        fileRow.setOrientation(LinearLayout.VERTICAL);
        fileRow.setBackgroundColor(0xFF222B36);
        fileRow.setPadding(dp(12), dp(8), dp(8), dp(4));
        LinearLayout fileTop = new LinearLayout(this);
        fileTop.setGravity(Gravity.CENTER_VERTICAL);
        filePlay = new PlayButton(this);
        filePlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (FilePlayer.shared.isPlaying()) FilePlayer.shared.pause();
                else FilePlayer.shared.play();
                refresh();
            }
        });
        fileTop.addView(filePlay, lp(dp(52), dp(52), 0));
        LinearLayout fileText = new LinearLayout(this);
        fileText.setOrientation(LinearLayout.VERTICAL);
        fileText.setPadding(dp(10), 0, dp(8), 0);
        fileTitle = label("", 14, 0xFFE8EDF2);
        fileTitle.setSingleLine(true);
        fileTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        fileText.addView(fileTitle, lp(match, wrap, 0));
        fileNote = label("", 12, MUTED);
        fileText.addView(fileNote, lp(match, wrap, 0));
        fileTop.addView(fileText, lp(0, wrap, 1));
        fileTop.addView(button("Fechar", new View.OnClickListener() {
            @Override
            public void onClick(View v) { closeFile(); }
        }), lp(wrap, wrap, 0));
        fileRow.addView(fileTop, lp(match, wrap, 0));
        LinearLayout fileBottom = new LinearLayout(this);
        fileBottom.setGravity(Gravity.CENTER_VERTICAL);
        fileSeek = new SeekBar(this);
        fileSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { }

            @Override
            public void onStartTrackingTouch(SeekBar bar) { seeking = true; }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                seeking = false;
                FilePlayer.shared.seekTo(bar.getProgress());
            }
        });
        fileBottom.addView(fileSeek, lp(0, wrap, 1));
        fileTime = label("0:00", 12, MUTED);
        fileBottom.addView(fileTime, lp(wrap, wrap, 0));
        fileRow.addView(fileBottom, lp(match, wrap, 0));
        fileRow.setVisibility(View.GONE);
        root.addView(fileRow, lp(match, wrap, 0));

        // velocidade do player: 0,25x a 2x em passos de 0,05 (tocar no texto volta para 1x)
        speedRow = new LinearLayout(this);
        speedRow.setGravity(Gravity.CENTER_VERTICAL);
        speedRow.setPadding(dp(12), dp(2), dp(8), dp(2));
        speedLabel = label("", 13, MUTED);
        speedLabel.setMinWidth(dp(118));
        speedLabel.setPadding(0, dp(8), 0, dp(8));
        speedLabel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { speedBar.setProgress(SPEED_NORMAL_STEP); }
        });
        speedRow.addView(speedLabel, lp(wrap, wrap, 0));
        speedBar = new SeekBar(this);
        speedBar.setMax(SPEED_MAX_STEP);
        speedBar.setProgress(SPEED_NORMAL_STEP);
        speedBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { setPlaybackRate(SPEED_MIN + progress * SPEED_STEP); }
            @Override
            public void onStartTrackingTouch(SeekBar bar) { }
            @Override
            public void onStopTrackingTouch(SeekBar bar) { }
        });
        speedRow.addView(speedBar, lp(0, wrap, 1));
        speedRow.setVisibility(View.GONE);
        root.addView(speedRow, lp(match, wrap, 0));
        showPlaybackRate();
        FilePlayer.shared.setSpeed(playbackRate); // o tocador sobrevive à tela: volta a combinar com a barra

        // abas
        tabs = new LinearLayout(this);
        chordsTab = button("Acordes", new View.OnClickListener() {
            @Override
            public void onClick(View v) { showTab(false); }
        });
        toneTab = button("Timbre", new View.OnClickListener() {
            @Override
            public void onClick(View v) { showTab(true); }
        });
        tabs.addView(chordsTab, lp(0, wrap, 1));
        tabs.addView(toneTab, lp(0, wrap, 1));
        root.addView(tabs, lp(match, wrap, 0));

        // ---- aba Acordes
        chordPanel = new LinearLayout(this);
        chordPanel.setOrientation(LinearLayout.VERTICAL);
        chordPanel.setBackgroundColor(PANEL);
        chordPanel.setPadding(dp(12), dp(4), dp(12), dp(8));

        // anterior · atual · próximo, desenhados no braço; ocupa o espaço que sobrar na tela
        stripView = new ChordStripView(this);
        chordPanel.addView(stripView, lp(match, 0, 1f));

        infoView = label(IDLE_HINT, 13, MUTED);
        infoView.setGravity(Gravity.CENTER);
        // o andamento pode sair no dobro ou na metade (70 ou 140?): tocar aqui corrige
        infoView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { chooseTempo(); }
        });
        chordPanel.addView(infoView, lp(match, wrap, 0));

        // grade de tempos: a linha de cima está tocando, a de baixo é o que vem depois
        gridView = new BeatGridView(this);
        LinearLayout.LayoutParams gridLp = lp(match, dp(84), 0);
        gridLp.topMargin = dp(6);
        gridLp.bottomMargin = dp(4);
        chordPanel.addView(gridView, gridLp);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        sourceSpinner = new Spinner(this);
        sourceSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Áudio interno", "Microfone"}));
        controls.addView(sourceSpinner, lp(0, wrap, 1));
        listenButton = button("Ouvir", new View.OnClickListener() {
            @Override
            public void onClick(View v) { toggleListen(); }
        });
        controls.addView(listenButton, lp(wrap, wrap, 0));
        controls.addView(button("Zerar", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetAnalysis();
                forgetChart();
                toast("Acordes e amostra de timbre zerados.");
            }
        }), lp(wrap, wrap, 0));
        chordPanel.addView(controls, lp(match, wrap, 0));

        simpleBox = new CheckBox(this);
        simpleBox.setText("Acordes simples (só maiores e menores)");
        simpleBox.setTextColor(MUTED);
        simpleBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { AppState.analyzer.chords.setSimpleMode(simpleBox.isChecked()); }
        });
        chordPanel.addView(simpleBox, lp(match, wrap, 0));

        bubbleBox = new CheckBox(this);
        bubbleBox.setText("Mostrar o acorde por cima de outros apps");
        bubbleBox.setTextColor(MUTED);
        bubbleBox.setChecked(prefs().getBoolean("bubble", true));
        bubbleBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs().edit().putBoolean("bubble", bubbleBox.isChecked()).apply();
                if (bubbleBox.isChecked()) askOverlayPermission();
            }
        });
        chordPanel.addView(bubbleBox, lp(match, wrap, 0));

        TextView version = label("Timbre Chord " + versionName(), 11, 0xFF5F6B78);
        version.setGravity(Gravity.CENTER);
        chordPanel.addView(version, lp(match, wrap, 0));
        root.addView(chordPanel, lp(match, 0, 1f));

        // ---- aba Timbre
        tonePanel = new ScrollView(this);
        tonePanel.setBackgroundColor(PANEL);
        LinearLayout tone = new LinearLayout(this);
        tone.setOrientation(LinearLayout.VERTICAL);
        tone.setPadding(dp(12), dp(8), dp(12), dp(12));

        sampleView = label("Amostra de timbre: 0 s", 13, MUTED);
        tone.addView(sampleView, lp(match, wrap, 0));

        styleSpinner = new Spinner(this);
        styleSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, LocalToneEngine.STYLE_LABELS));
        tone.addView(styleSpinner, lp(match, wrap, 0));

        LinearLayout aiRow = new LinearLayout(this);
        aiRow.setGravity(Gravity.CENTER_VERTICAL);
        aiBox = new CheckBox(this);
        aiBox.setText("Usar IA online");
        aiBox.setChecked(prefs().getBoolean("useAi", false));
        aiBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs().edit().putBoolean("useAi", aiBox.isChecked()).apply();
                if (aiBox.isChecked() && prefs().getString("apiKey", "").isEmpty()) showAiSettings();
            }
        });
        aiRow.addView(aiBox, lp(0, wrap, 1));
        aiRow.addView(button("Configurar IA", new View.OnClickListener() {
            @Override
            public void onClick(View v) { showAiSettings(); }
        }), lp(wrap, wrap, 0));
        tone.addView(aiRow, lp(match, wrap, 0));

        nameField = new EditText(this);
        nameField.setHint("Nome do timbre (opcional)");
        nameField.setSingleLine(true);
        nameField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        nameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        nameField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
        nameField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        tone.addView(nameField, lp(match, wrap, 0));
        tone.addView(label("Em branco, o app usa o nome da música. No .prst da GP-150 cabem os 15 primeiros caracteres, sem acento.", 12, MUTED),
                lp(match, wrap, 0));

        generateButton = button("Gerar timbre", new View.OnClickListener() {
            @Override
            public void onClick(View v) { generate(); }
        });
        tone.addView(generateButton, lp(match, wrap, 0));

        LinearLayout exports = new LinearLayout(this);
        exports.addView(exportButton("TXT", "txt"), lp(0, wrap, 1));
        exports.addView(exportButton("JSON", "json"), lp(0, wrap, 1));
        exports.addView(exportButton("PDF", "pdf"), lp(0, wrap, 1));
        exports.addView(exportButton(".prst", "prst"), lp(0, wrap, 1));
        tone.addView(exports, lp(match, wrap, 0));

        statusView = label("TXT, JSON e PDF servem para qualquer pedaleira. O .prst é o preset pronto da Valeton GP-150.", 12, MUTED);
        tone.addView(statusView, lp(match, wrap, 0));

        resultView = label("", 13, 0xFFE8EDF2);
        resultView.setTypeface(Typeface.MONOSPACE);
        resultView.setTextIsSelectable(true);
        resultView.setPadding(0, dp(8), 0, 0);
        tone.addView(resultView, lp(match, wrap, 0));

        tonePanel.addView(tone, new ViewGroup.LayoutParams(match, wrap));
        root.addView(tonePanel, lp(match, 0, 1.4f));

        stemPanel = buildStemPanel();
        stemPanel.setVisibility(View.GONE);
        root.addView(stemPanel, lp(match, 0, 1f));
        return root;
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return ""; }
    }

    /** Botão redondo de tocar/pausar. É desenhado, para não depender dos símbolos da fonte do aparelho. */
    private static final class PlayButton extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private boolean playing;

        PlayButton(Context context) {
            super(context);
            setClickable(true);
            setFocusable(true);
            setContentDescription("Tocar");
        }

        void setPlaying(boolean value) {
            if (value == playing) return;
            playing = value;
            setContentDescription(value ? "Pausar" : "Tocar");
            invalidate();
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy);
            paint.setColor(isPressed() ? 0xFFCC8F00 : ACCENT);
            canvas.drawCircle(cx, cy, r, paint);
            paint.setColor(0xFF101418);
            float s = r * 0.42f;
            if (playing) {
                canvas.drawRect(cx - s, cy - s, cx - s * 0.3f, cy + s, paint);
                canvas.drawRect(cx + s * 0.3f, cy - s, cx + s, cy + s, paint);
            } else {
                path.rewind();
                path.moveTo(cx - s * 0.7f, cy - s);
                path.lineTo(cx + s, cy);
                path.lineTo(cx - s * 0.7f, cy + s);
                path.close();
                canvas.drawPath(path, paint);
            }
        }
    }

    private Button exportButton(String text, final String kind) {
        return button(text, new View.OnClickListener() {
            @Override
            public void onClick(View v) { export(kind); }
        });
    }

    private void showTab(boolean tone) {
        toneTabShown = tone;
        // com o painel de faixas aberto, as abas ficam escondidas
        boolean stems = stemPanelShown;
        tabs.setVisibility(stems ? View.GONE : View.VISIBLE);
        chordPanel.setVisibility(stems || tone ? View.GONE : View.VISIBLE);
        tonePanel.setVisibility(stems || !tone ? View.GONE : View.VISIBLE);
        stemPanel.setVisibility(stems ? View.VISIBLE : View.GONE);
        chordsTab.setTextColor(tone ? MUTED : ACCENT);
        toneTab.setTextColor(tone ? ACCENT : MUTED);
    }

    /** Zera acordes, andamento e a amostra de timbre: chamada quando começa outra música. */
    private void resetAnalysis() {
        AppState.analyzer.reset();
        AppState.recorder.pause();
        // som de outro app: o mapa vale só para esta escuta e recomeça junto com ela
        if (AppState.chartId.isEmpty()) AppState.chart = new ChordChart();
    }

    /**
     * Troca o mapa de acordes para o da música que abriu ("" = som de outro app, sem música definida).
     * O mapa anterior é guardado; o da música nova é carregado, se ela já foi ouvida antes.
     */
    private void switchChart(String id) {
        saveChart();
        AppState.recorder.pause();
        ChordChart saved = id.isEmpty() ? null : ChartStore.load(this, id);
        AppState.chart = saved != null ? saved : new ChordChart();
        AppState.chartId = id;
    }

    private void saveChart() {
        String id = AppState.chartId;
        ChordChart chart = AppState.chart;
        if (id.isEmpty() || !chart.isDirty() || chart.coveredSeconds() < 10) return;
        ChartStore.save(this, id, chart);
    }

    /** Botão Zerar: esquece os acordes guardados desta música e aprende de novo. */
    private void forgetChart() {
        String id = AppState.chartId;
        FileScanner.shared.cancel();
        AppState.recorder.pause();
        AppState.chart.clear();
        if (id.isEmpty()) return;
        ChartStore.delete(this, id);
        if (FilePlayer.shared.isLoaded() && fileUri != null)
            FileScanner.shared.start(this, fileUri, AppState.chart, simpleBox.isChecked());
    }

    // ------------------------------------------------------------------ separação de faixas

    /** Uma linha do painel: nome (toque = silenciar), barra de volume, porcentagem e "Só". */
    private final class StemRow {
        final String source;
        final TextView name, percent;
        final SeekBar bar;
        final Button solo;

        StemRow(LinearLayout parent, final String source) {
            this.source = source;
            final int wrap = ViewGroup.LayoutParams.WRAP_CONTENT;
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(2), 0, dp(2));
            name = label(StemMixer.label(source), 15, 0xFFE8EDF2);
            name.setMinWidth(dp(84));
            name.setPadding(0, dp(10), dp(4), dp(10));
            name.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    StemMixer.shared.toggleMute(source);
                    updateRows();
                }
            });
            row.addView(name, lp(wrap, wrap, 0));
            bar = new SeekBar(MainActivity.this);
            bar.setMax(150);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar b, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    StemMixer.shared.setGain(source, progress / 100f);
                    updateRows();
                }

                @Override
                public void onStartTrackingTouch(SeekBar b) { }

                @Override
                public void onStopTrackingTouch(SeekBar b) { }
            });
            row.addView(bar, lp(0, wrap, 1));
            percent = label("100%", 13, MUTED);
            percent.setMinWidth(dp(46));
            percent.setGravity(Gravity.END);
            row.addView(percent, lp(wrap, wrap, 0));
            solo = button("Só", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    StemMixer.shared.toggleSolo(source);
                    updateRows();
                }
            });
            solo.setMinWidth(0);
            solo.setMinimumWidth(dp(52));
            row.addView(solo, lp(wrap, wrap, 0));
            parent.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, wrap, 0));
        }

        void update() {
            StemMixer m = StemMixer.shared;
            float g = m.gain(source);
            boolean muted = m.isMuted(source), soloed = source.equals(m.solo());
            boolean silent = muted || (!m.solo().isEmpty() && !soloed);
            int p = Math.round(g * 100);
            if (bar.getProgress() != p) bar.setProgress(p);
            name.setText(StemMixer.label(source) + (muted ? " (mudo)" : ""));
            name.setTextColor(silent ? 0xFF5F6B78 : 0xFFE8EDF2);
            percent.setText(p + "%");
            percent.setTextColor(p == 100 ? MUTED : ACCENT);
            solo.setTextColor(soloed ? ACCENT : MUTED);
        }
    }

    private ScrollView buildStemPanel() {
        final int wrap = ViewGroup.LayoutParams.WRAP_CONTENT, match = ViewGroup.LayoutParams.MATCH_PARENT;
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(PANEL);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(10), dp(14), dp(14));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(label("Faixas separadas", 17, 0xFFE8EDF2), lp(0, wrap, 1));
        TextView back = label("✕ Acordes", 13, MUTED);
        back.setPadding(dp(12), dp(10), 0, dp(10));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showStemPanel(false); }
        });
        head.addView(back, lp(wrap, wrap, 0));
        box.addView(head, lp(match, wrap, 0));

        stemInfo = label("", 13, MUTED);
        stemInfo.setPadding(0, dp(4), 0, dp(8));
        box.addView(stemInfo, lp(match, wrap, 0));

        stemProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        stemProgress.setMax(1000);
        stemProgress.setVisibility(View.GONE);
        box.addView(stemProgress, lp(match, wrap, 0));

        stemAction = button("", new View.OnClickListener() {
            @Override
            public void onClick(View v) { stemAction(); }
        });
        box.addView(stemAction, lp(match, wrap, 0));

        stemRows = new LinearLayout(this);
        stemRows.setOrientation(LinearLayout.VERTICAL);
        box.addView(stemRows, lp(match, wrap, 0));

        stemNote = label("Toque no nome para silenciar a faixa; \"Só\" deixa tocando só ela. Até 150% para destacar uma faixa.", 12, MUTED);
        stemNote.setPadding(0, dp(6), 0, dp(6));
        box.addView(stemNote, lp(match, wrap, 0));

        LinearLayout buttons = new LinearLayout(this);
        stemReset = button("Todas em 100%", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                StemMixer.shared.reset();
                updateRows();
            }
        });
        buttons.addView(stemReset, lp(0, wrap, 1));
        stemDelete = button("Apagar faixas", new View.OnClickListener() {
            @Override
            public void onClick(View v) { confirmDeleteStems(); }
        });
        buttons.addView(stemDelete, lp(0, wrap, 1));
        box.addView(buttons, lp(match, wrap, 0));

        // músicas já separadas: tocam direto das faixas, sem esperar de novo
        savedTitle = label("", 15, 0xFFE8EDF2);
        savedTitle.setPadding(0, dp(18), 0, dp(4));
        box.addView(savedTitle, lp(match, wrap, 0));
        savedList = new LinearLayout(this);
        savedList.setOrientation(LinearLayout.VERTICAL);
        box.addView(savedList, lp(match, wrap, 0));

        scroll.addView(box, new ViewGroup.LayoutParams(match, wrap));
        return scroll;
    }

    private void showStemPanel(boolean show) {
        stemPanelShown = show;
        savedDirty = true;
        stemsButton.setText(show ? "Voltar aos acordes" : "Separar faixas");
        showTab(toneTabShown);
        refreshStems();
    }

    private void updateRows() {
        for (StemRow r : rows) r.update();
    }

    /** Arquivo aberto agora pode ser separado? (o som de outros apps não fica no aparelho) */
    private boolean fileOpen() {
        return FilePlayer.shared.isLoaded() && fileUri != null && AppState.chartId.startsWith("f_");
    }

    private void stemAction() {
        SeparationJob job = SeparationJob.shared;
        if (job.isRunning()) {
            job.cancel();
            return;
        }
        if (!fileOpen()) {
            pickFile();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMISSIONS + 100);
        long free = getFilesDir().getUsableSpace();
        long need = StemStore.estimateBytes(FilePlayer.shared.durationMs(), 6) + 400L * 1024 * 1024;
        if (FilePlayer.shared.durationMs() > 0 && free < need) {
            toast(String.format(Locale.US, "Falta espaço: a separação precisa de uns %d MB livres.", need / (1024 * 1024)));
            return;
        }
        job.start(this, fileUri, AppState.chartId, fileName.isEmpty() ? FilePlayer.shared.title() : fileName);
        refreshStems();
    }

    private void confirmDeleteStems() {
        final StemStore.StemSet set = FilePlayer.shared.stems();
        if (set == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Apagar as faixas?")
                .setMessage(String.format(Locale.US, "Libera %d MB. A música continua tocando inteira; para usar as barras de novo, é preciso separar outra vez.",
                        set.bytes() / (1024 * 1024)))
                .setPositiveButton("Apagar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        FilePlayer p = FilePlayer.shared;
                        int at = p.positionMs();
                        boolean playing = p.isPlaying();
                        String title = p.title();
                        StemStore.delete(MainActivity.this, set.id);
                        savedDirty = true;
                        if (fileUri != null) {
                            p.open(MainActivity.this, fileUri, title);
                            p.seekTo(at);
                            if (!playing) p.pause();
                        } else {
                            // aberta pela lista: sem o arquivo original, não há o que tocar
                            closeFile();
                        }
                        refreshStems();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /** Chamado a cada atualização da tela: troca para as faixas quando a separação termina e mostra o estado. */
    private void refreshStems() {
        SeparationJob job = SeparationJob.shared;
        FilePlayer player = FilePlayer.shared;
        String id = AppState.chartId;
        int state = job.state();
        if (state == SeparationJob.DONE) {
            if (job.songId().equals(id) && fileOpen() && !player.isStems()) {
                StemStore.StemSet set = StemStore.load(this, id);
                if (set != null) {
                    // troca sem voltar ao começo nem mudar play/pausa
                    StemMixer.shared.reset();
                    player.openStems(this, set, player.title(), player.positionMs(), player.isPlaying());
                    toast("Faixas prontas: use as barras para mudar o volume de cada uma.");
                }
            }
            savedDirty = true;
            job.acknowledge();
        } else if (state == SeparationJob.FAILED || state == SeparationJob.CANCELLED) {
            if (state == SeparationJob.FAILED) toast(job.error());
            job.acknowledge();
        }
        if (!stemPanelShown) {
            stemsButton.setText(job.isRunning() ? String.format(Locale.US, "Separando %d%%", Math.round(job.progress() * 100)) : "Separar faixas");
            return;
        }

        boolean ready = player.isStems();
        boolean running = job.isRunning();
        boolean mine = running && job.songId().equals(id);
        String info;
        String action = "";
        if (modelAvailable == null) modelAvailable = ModelStore.available(this);
        if (!modelAvailable) {
            info = "Esta compilação do app veio sem o modelo de separação. Use o APK gerado pelo GitHub Actions.";
        } else if (ready) {
            info = "“" + player.title() + "” em 6 faixas. Mexa nas barras enquanto a música toca.";
        } else if (mine) {
            info = String.format(Locale.US, "Separando “%s”: %d%%%s\n%s\nPode usar outros apps; o andamento aparece na notificação.",
                    job.title(), Math.round(job.progress() * 100),
                    job.etaSeconds() >= 0 ? " · " + SeparationService.eta(job.etaSeconds()) : "", job.stage());
            action = "Cancelar";
        } else if (running) {
            info = String.format(Locale.US, "Separando outra música (“%s”): %d%%. Espere terminar ou cancele para separar esta.",
                    job.title(), Math.round(job.progress() * 100));
            action = "Cancelar a outra separação";
        } else if (!fileOpen()) {
            info = "A separação funciona com arquivos de música do aparelho (MP3, M4A, FLAC, OGG, WAV). "
                    + "O som do YouTube ou de outros apps não pode ser separado: ele não fica guardado no aparelho.";
            action = "Escolher música";
        } else {
            long mb = StemStore.estimateBytes(player.durationMs(), 6) / (1024 * 1024);
            info = "Separa “" + player.title() + "” em voz, guitarra, baixo, bateria, piano e outros, com uma IA que roda no "
                    + "próprio celular (nada é enviado). Leva alguns minutos, conforme o aparelho; dá para usar outros apps enquanto isso."
                    + (mb > 0 ? String.format(Locale.US, " As faixas ocupam uns %d MB e ficam guardadas na lista abaixo.", mb) : "");
            action = "Separar em 6 faixas";
        }
        if (!info.equals(stemInfo.getText().toString())) stemInfo.setText(info);
        stemAction.setVisibility(action.isEmpty() ? View.GONE : View.VISIBLE);
        if (!action.equals(stemAction.getText().toString())) stemAction.setText(action);
        stemProgress.setVisibility(running ? View.VISIBLE : View.GONE);
        if (running) {
            stemProgress.setIndeterminate(job.progress() <= 0.001f);
            stemProgress.setProgress(Math.round(job.progress() * 1000));
        }

        StemStore.StemSet set = ready ? player.stems() : null;
        String key = set == null ? "" : set.id;
        if (!key.equals(stemRowsFor)) {
            stemRowsFor = key;
            stemRows.removeAllViews();
            rows.clear();
            if (set != null) {
                for (String source : StemMixer.ORDER)
                    if (java.util.Arrays.asList(set.sources).contains(source)) rows.add(new StemRow(stemRows, source));
                for (String source : set.sources) // alguma fonte fora da lista conhecida
                    if (!java.util.Arrays.asList(StemMixer.ORDER).contains(source)) rows.add(new StemRow(stemRows, source));
            }
        }
        int extras = set == null ? View.GONE : View.VISIBLE;
        stemRows.setVisibility(extras);
        stemNote.setVisibility(extras);
        stemReset.setVisibility(extras);
        stemDelete.setVisibility(extras);
        if (set != null) updateRows();
        refreshSaved();
    }

    /** Lista das músicas separadas (lida do disco só quando algo mudou). */
    private void refreshSaved() {
        String playing = FilePlayer.shared.isStems() ? FilePlayer.shared.stems().id : "";
        if (!savedDirty && playing.equals(savedShownFor)) return;
        savedDirty = false;
        savedShownFor = playing;
        final int wrap = ViewGroup.LayoutParams.WRAP_CONTENT, match = ViewGroup.LayoutParams.MATCH_PARENT;
        java.util.List<StemStore.StemSet> sets = StemStore.list(this);
        savedList.removeAllViews();
        if (sets.isEmpty()) {
            savedTitle.setVisibility(View.GONE);
            return;
        }
        savedTitle.setVisibility(View.VISIBLE);
        savedTitle.setText(String.format(Locale.US, "Músicas separadas (%d) · %s", sets.size(), megabytes(StemStore.totalBytes(sets))));
        for (final StemStore.StemSet set : sets) {
            boolean current = set.id.equals(playing);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(2), 0, dp(2));
            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setPadding(0, dp(8), dp(8), dp(8));
            TextView name = label((current ? "▶ " : "") + (set.title.isEmpty() ? "Música" : set.title), 14, current ? ACCENT : 0xFFE8EDF2);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.addView(name, lp(match, wrap, 0));
            text.addView(label(clock((int) (set.frames * 1000 / StemStore.SAMPLE_RATE)) + " · " + megabytes(set.bytes()), 12, MUTED), lp(match, wrap, 0));
            text.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { openSaved(set); }
            });
            row.addView(text, lp(0, wrap, 1));
            Button remove = button("✕", new View.OnClickListener() {
                @Override
                public void onClick(View v) { confirmDeleteSaved(set); }
            });
            remove.setMinWidth(0);
            remove.setMinimumWidth(dp(48));
            remove.setTextColor(MUTED);
            row.addView(remove, lp(wrap, wrap, 0));
            savedList.addView(row, lp(match, wrap, 0));
        }
    }

    private static String megabytes(long bytes) {
        return bytes >= 1024L * 1024 * 1024
                ? String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
                : String.format(Locale.US, "%d MB", bytes / (1024 * 1024));
    }

    /** Toca uma música da lista direto das faixas (o arquivo original nem precisa existir mais). */
    private void openSaved(StemStore.StemSet set) {
        if (FilePlayer.shared.isStems() && set.id.equals(FilePlayer.shared.stems().id)) return;
        StemStore.StemSet fresh = StemStore.load(this, set.id); // marca como usada agora
        if (fresh == null) {
            toast("Essas faixas não estão mais completas no aparelho.");
            savedDirty = true;
            return;
        }
        if (CaptureService.running) startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP));
        FileScanner.shared.cancel();
        resetAnalysis();
        StemMixer.shared.reset();
        FilePlayer.shared.openStems(this, fresh, fresh.title, 0, true);
        fileUri = null;
        fileName = fresh.title;
        switchChart(fresh.id); // os acordes guardados da música voltam junto
        shownFileError = "";
        fileTitle.setText(fresh.title);
        fileRow.setVisibility(View.VISIBLE);
        savedDirty = true;
    }

    private void confirmDeleteSaved(final StemStore.StemSet set) {
        if (FilePlayer.shared.isStems() && set.id.equals(FilePlayer.shared.stems().id)) {
            confirmDeleteStems();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Apagar \u201c" + set.title + "\u201d?")
                .setMessage("Libera " + megabytes(set.bytes()) + ". Para usar as barras nessa música de novo, é preciso separar outra vez.")
                .setPositiveButton("Apagar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        StemStore.delete(MainActivity.this, set.id);
                        savedDirty = true;
                        refreshStems();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ------------------------------------------------------------------ arquivo de música

    private void pickFile() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*");
        try {
            startActivityForResult(pick, REQ_FILE);
        } catch (Exception e) {
            toast("Não encontrei um seletor de arquivos neste aparelho.");
        }
    }

    private void openFile(Uri uri) {
        if (uri == null) return;
        String name = "";
        long size = 0;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                name = c.getString(0);
                if (!c.isNull(1)) size = c.getLong(1);
            }
        } catch (Exception ignored) { }
        if (name == null || name.isEmpty()) name = "Arquivo de música";
        // o mesmo arquivo (nome e tamanho) reaproveita os acordes já analisados
        String chartId = "f_" + Integer.toHexString((name + "|" + size).hashCode()) + "_" + size;
        name = name.replaceAll("\\.[A-Za-z0-9]{2,4}$", "");
        // o arquivo já entrega o áudio direto ao analisador: a escuta de outros apps sairia em dobro
        if (CaptureService.running) startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP));
        resetAnalysis();
        StemMixer.shared.reset();
        // já separada antes: toca direto das faixas, com as barras valendo
        StemStore.StemSet saved = StemStore.load(this, chartId);
        if (saved != null) FilePlayer.shared.openStems(this, saved, name, 0, true);
        else FilePlayer.shared.open(this, uri, name);
        fileUri = uri;
        fileName = name;
        switchChart(chartId);
        // analisa a música inteira agora, em segundo plano: o próximo acorde aparece antes de tocar
        if (!AppState.chart.isComplete()) FileScanner.shared.start(this, uri, AppState.chart, simpleBox.isChecked());
        shownFileError = "";
        fileTitle.setText(name);
        fileRow.setVisibility(View.VISIBLE);
    }

    private void closeFile() {
        if (!FilePlayer.shared.isLoaded() && fileRow.getVisibility() != View.VISIBLE) return;
        FilePlayer.shared.close();
        FileScanner.shared.cancel();
        fileUri = null;
        fileName = "";
        if (AppState.chartId.startsWith("f_")) switchChart("");
        fileRow.setVisibility(View.GONE);
    }

    private static String clock(int ms) {
        int s = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void askOverlayPermission() {
        if (Settings.canDrawOverlays(this)) return;
        new AlertDialog.Builder(this)
                .setTitle("Acorde por cima de outros apps")
                .setMessage("Para o acorde aparecer em um quadrinho enquanto você usa o YouTube ou o player de música, "
                        + "o Android precisa da permissão \"Sobrepor a outros apps\". Sem ela, o acorde aparece só na notificação.")
                .setPositiveButton("Abrir a permissão", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            toast("Abra Configurações > Apps > Timbre Chord > Sobrepor a outros apps.");
                        }
                    }
                })
                .setNegativeButton("Agora não", null)
                .show();
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }

    private SharedPreferences prefs() { return getSharedPreferences("timbrechord", MODE_PRIVATE); }

    // ------------------------------------------------------------------ player

    private void setPlaybackRate(float rate) {
        playbackRate = Math.round(rate * 100) / 100f;
        showPlaybackRate();
        FilePlayer.shared.setSpeed(playbackRate);
    }

    /**
     * Quanto o andamento medido está esticado em relação à música original: o arquivo é analisado no
     * andamento original e o áudio de outros apps não passa pela barra, então é sempre 1.
     */
    private float tempoFactor() {
        return 1f;
    }

    private void showPlaybackRate() {
        boolean normal = Math.abs(playbackRate - 1f) < 0.001f;
        speedLabel.setText(String.format(Locale.US, "Velocidade %.2fx", playbackRate));
        speedLabel.setTextColor(normal ? MUTED : ACCENT);
    }

    /** Posição da música agora, em segundos. */
    private double songPosition() {
        if (FilePlayer.shared.isLoaded()) return Math.max(0, FilePlayer.shared.positionMs() / 1000.0 - FILE_OUT_DELAY);
        return AppState.analyzer.seconds(); // som de outro app: o relógio é o da própria escuta
    }

    /** Arquivo de música aberto ou compartilhado com o app. */
    private boolean handleShare(Intent intent) {
        if (intent == null) return false;
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            openFile(intent.getData());
            return true;
        }
        if (!Intent.ACTION_SEND.equals(intent.getAction())) return false;
        Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (stream == null) return false;
        openFile(stream);
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShare(intent);
    }

    private String songTitle() {
        return FilePlayer.shared.isLoaded() ? FilePlayer.shared.title() : "";
    }

    // ------------------------------------------------------------------ captura

    private void toggleListen() {
        if (CaptureService.running) {
            startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP));
            return;
        }
        if (FilePlayer.shared.isLoaded()) {
            toast("O arquivo aberto já está sendo analisado. Feche o arquivo para ouvir outro app.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            String[] wanted = Build.VERSION.SDK_INT >= 33
                    ? new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}
                    : new String[]{Manifest.permission.RECORD_AUDIO};
            requestPermissions(wanted, REQ_PERMISSIONS);
            return;
        }
        if (sourceSpinner.getSelectedItemPosition() == 1) {
            startCapture(true, 0, null);
            return;
        }
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        Intent ask = Build.VERSION.SDK_INT >= 34
                ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : manager.createScreenCaptureIntent();
        try {
            startActivityForResult(ask, REQ_PROJECTION);
        } catch (Exception e) {
            toast("Este aparelho não liberou a captura do áudio interno. Use a fonte Microfone.");
        }
    }

    private void startCapture(boolean mic, int resultCode, Intent data) {
        Intent i = new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_MIC, mic);
        if (!mic) i.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode).putExtra(CaptureService.EXTRA_RESULT_DATA, data);
        CaptureService.lastError = "";
        shownError = "";
        try {
            startForegroundService(i);
        } catch (Exception e) {
            toast("Não consegui iniciar a escuta: " + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISSIONS) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) toggleListen();
        else toast("Sem a permissão de áudio o app não consegue ouvir a música. O som é só analisado, nunca gravado.");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE) {
            if (resultCode == RESULT_OK && data != null) openFile(data.getData());
            return;
        }
        if (requestCode != REQ_PROJECTION) return;
        if (resultCode == RESULT_OK && data != null) {
            startCapture(false, resultCode, data);
            if (bubbleBox.isChecked()) askOverlayPermission();
        }
        else toast("Captura não autorizada. Para ouvir o áudio interno é preciso aceitar o aviso do Android.");
    }

    // ------------------------------------------------------------------ atualização da tela

    private void refresh() {
        LiveAnalyzer a = AppState.analyzer;
        boolean listening = CaptureService.running;
        listenButton.setText(listening ? "Parar" : "Ouvir");
        sourceSpinner.setEnabled(!listening);

        FilePlayer player = FilePlayer.shared;
        boolean fileMode = player.isLoaded();
        ChordChart chart = AppState.chart;
        double position = songPosition();

        // arquivo: quem preenche o mapa é a análise antecipada; se ela não deu conta, aprende enquanto toca
        // (outro app: é o serviço de escuta)
        if (!AppState.chartId.isEmpty() && fileMode) {
            if (player.isPlaying() && !FileScanner.shared.isRunning() && !chart.isComplete())
                AppState.recorder.update(chart, a.chords, player.positionMs() / 1000.0, 1);
            else AppState.recorder.pause();
        }

        // acorde mostrado: o do mapa, quando o trecho já é conhecido (sem atraso); senão, o que o detector ouve agora
        String shownChord = listening || player.isPlaying() ? a.chords.current() : "";
        String previousChord = "", nextChord = "";
        boolean nextIsGuess = false;
        ChordChart.Grid grid = null;
        if (!Double.isNaN(position)) {
            String known = chart.at(position);
            if (known != null) shownChord = known;
            ChordChart.Seg before = chart.previous(position, shownChord), after = chart.next(position, shownChord);
            if (before != null) previousChord = before.chord;
            if (after != null) {
                nextChord = after.chord;
            } else {
                // trecho ainda não ouvido: palpite pela repetição da sequência
                List<String> played = chart.progressionUntil(position);
                if (!shownChord.isEmpty() && (played.isEmpty() || !played.get(played.size() - 1).equals(shownChord))) played.add(shownChord);
                nextChord = ChordChart.predictNext(played);
                nextIsGuess = !nextChord.isEmpty();
            }
            if (!chart.isEmpty()) grid = chart.grid(position, BeatGridView.COLS, BeatGridView.ROWS);
        }
        stripView.set(previousChord, shownChord, nextChord, nextIsGuess);
        gridView.set(grid);

        if (++tickCount % 3 == 0) {
            StringBuilder info = new StringBuilder();
            String key = a.chords.key();
            // música inteira analisada: o andamento dela, fixo; senão, o medido ao vivo (últimos 30 s)
            float bpm = songBpm();
            if (!key.isEmpty()) {
                info.append("Tom: ").append(key);
                String relative = a.chords.relativeKey();
                if (!relative.isEmpty()) info.append(" · relativo: ").append(relative);
            }
            if (bpm >= 25) info.append(info.length() > 0 ? "  ·  " : "").append(Math.round(bpm)).append(" BPM");
            if (listening) {
                int bars = Math.min(8, Math.round(a.level() * 16));
                info.append(info.length() > 0 ? "  ·  " : "").append("nível ");
                for (int i = 0; i < 8; i++) info.append(i < bars ? '●' : '○');
                if (a.seconds() > 3 && a.level() < 0.002f)
                    info.append("\nSem som chegando: confira se a música está tocando. Alguns apps bloqueiam a captura; nesse caso use a fonte Microfone.");
            } else if (info.length() == 0 && chart.isEmpty()) {
                info.append(IDLE_HINT);
            }
            String status = chartStatus(chart, fileMode, listening);
            if (!status.isEmpty()) info.append(info.length() > 0 ? "\n" : "").append(status);
            infoView.setText(info);

            // a grade precisa do andamento; com arquivo, quem mede é a análise antecipada
            if (!chart.isComplete() && !FileScanner.shared.isRunning() && bpm >= 50) chart.setBpm(bpm / chart.tempoScale());
            if (tickCount % 90 == 0 || (chart.isComplete() && chart.isDirty())) saveChart();

            double sample = a.tone.seconds();
            sampleView.setText(String.format(Locale.US, "Amostra de timbre: %.0f s%s", sample,
                    sample < 8 ? " (deixe tocar pelo menos 8 s; 30 s ou mais dá um resultado melhor)" : ""));
        }

        // a barra do arquivo fica na tela enquanto houver um arquivo escolhido, mesmo se ele falhar ao tocar
        int fileVisibility = player.isLoaded() ? View.VISIBLE : View.GONE;
        if (fileRow.getVisibility() != fileVisibility) fileRow.setVisibility(fileVisibility);
        int speedVisibility = fileVisibility;
        if (speedRow.getVisibility() != speedVisibility) speedRow.setVisibility(speedVisibility);
        if (player.isLoaded()) {
            if (!player.title().equals(fileTitle.getText().toString())) fileTitle.setText(player.title());
            boolean playing = player.isPlaying();
            filePlay.setPlaying(playing);
            String problem = player.error();
            String note = !problem.isEmpty() ? problem + " Toque no botão para tentar de novo."
                    : playing ? "Tocando"
                    : player.isEnded() ? "Fim da música · toque no botão para ouvir de novo"
                    : "Pausado · toque no botão para tocar";
            if (!note.equals(fileNote.getText().toString())) {
                fileNote.setText(note);
                fileNote.setTextColor(problem.isEmpty() ? MUTED : 0xFFFF8A80);
            }
            if (!problem.equals(shownFileError)) {
                shownFileError = problem;
                if (!problem.isEmpty()) toast(problem);
            }
            int duration = player.durationMs(), playedMs = player.positionMs();
            if (fileSeek.getMax() != Math.max(1, duration)) fileSeek.setMax(Math.max(1, duration));
            if (!seeking) fileSeek.setProgress(playedMs);
            fileTime.setText(clock(seeking ? fileSeek.getProgress() : playedMs) + " / " + clock(duration));
        }
        String error = CaptureService.lastError;
        if (!error.isEmpty() && !error.equals(shownError)) {
            shownError = error;
            toast(error);
        }
        refreshStems();
    }

    /** Uma linha dizendo quanto da música o app já conhece ("" quando não há nada a dizer). */
    private String chartStatus(ChordChart chart, boolean fileMode, boolean listening) {
        if (fileMode) {
            FileScanner scanner = FileScanner.shared;
            if (scanner.isRunning()) return String.format(Locale.US, "Analisando a música inteira: %d%%", Math.round(scanner.progress() * 100));
            return chart.isComplete() ? "Música inteira analisada: o próximo acorde aparece adiantado." : "";
        }
        return "";
    }

    // ------------------------------------------------------------------ timbre

    private void generate() {
        if (generating) return;
        final LiveAnalyzer a = AppState.analyzer;
        final ToneAnalyzer.Features features = a.features();
        features.bpm = songBpm(); // o delay é calculado para o andamento da música
        final int style = styleSpinner.getSelectedItemPosition();
        final String title = songTitle();
        final String customName = toneName();
        final String key = a.chords.key();
        final List<String> progression = ChainExporter.progression(a.chords.timeline(), a.seconds());
        final boolean useAi = aiBox.isChecked();
        final String apiKey = prefs().getString("apiKey", "");
        final String model = prefs().getString("model", AiToneEngine.DEFAULT_MODEL);
        final String endpoint = prefs().getString("endpoint", AiToneEngine.DEFAULT_ENDPOINT);
        if (useAi && apiKey.isEmpty()) {
            showAiSettings();
            return;
        }
        generating = true;
        generateButton.setEnabled(false);
        generateButton.setText(useAi ? "Consultando a IA…" : "Gerando…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                ToneChain chain = null;
                String problem = null;
                EffectCatalog catalog = null;
                try {
                    catalog = AppState.catalog(MainActivity.this);
                    if (useAi) {
                        try {
                            chain = AiToneEngine.generate(catalog, endpoint, model, apiKey, features, style, title, key, progression);
                        } catch (Exception e) {
                            problem = "A IA não respondeu (" + e.getMessage() + "). Usei a análise local.";
                        }
                    }
                    if (chain == null) {
                        chain = LocalToneEngine.generate(catalog, features, style, title);
                        if (problem != null) chain.notes.add(0, problem);
                    }
                    if (!customName.isEmpty()) chain.name = customName;
                    chain.songTitle = title;
                    chain.key = key;
                    chain.bpm = features.bpm;
                    chain.progression.clear();
                    chain.progression.addAll(progression.subList(0, Math.min(progression.size(), 160)));
                } catch (Exception e) {
                    chain = null;
                    problem = "Não consegui gerar o timbre: " + e.getMessage();
                }
                final ToneChain result = chain;
                final String message = problem;
                final String text = result == null || catalog == null ? "" : ChainExporter.toText(result, catalog);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        generating = false;
                        generateButton.setEnabled(true);
                        generateButton.setText("Gerar timbre");
                        if (result != null) {
                            lastChain = result;
                            resultView.setText(text);
                            statusView.setText("Pronto. Escolha o arquivo: TXT, JSON e PDF servem para qualquer pedaleira; o .prst é o preset da Valeton GP-150.");
                        }
                        if (message != null) toast(message);
                    }
                });
            }
        }).start();
    }

    /** Andamento mostrado: o da música inteira (arquivo analisado) ou o ao vivo, com a correção de dobro/metade. */
    private float songBpm() {
        ChordChart chart = AppState.chart;
        if (chart.isComplete() && chart.bpm() >= 30) return chart.shownBpm();
        return AppState.analyzer.tempo.bpm() / tempoFactor() * chart.tempoScale();
    }

    /** Tocar no texto do BPM: a pessoa escolhe entre a metade, o medido e o dobro (fica guardado na música). */
    private void chooseTempo() {
        final ChordChart chart = AppState.chart;
        final float shown = songBpm(), scale = chart.tempoScale();
        if (shown < 15) {
            toast("O andamento ainda não foi medido.");
            return;
        }
        final float measured = shown / scale;
        final float[] scales = {0.5f, 1f, 2f};
        String[] items = new String[3];
        String[] names = {"metade", "medido", "dobro"};
        for (int i = 0; i < 3; i++)
            items[i] = Math.round(measured * scales[i]) + " BPM (" + names[i] + ")" + (scales[i] == scale ? "  ✓" : "");
        new AlertDialog.Builder(this)
                .setTitle("Andamento")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        chart.setTempoScale(scales[which]);
                        saveChart();
                        refresh();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /** Nome que a pessoa digitou para o timbre; vazio quando ela deixou o app escolher. */
    private String toneName() {
        return nameField.getText().toString().replaceAll("\\s+", " ").trim();
    }

    private void showAiSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText key = new EditText(this);
        key.setHint("Chave da API (sk-…)");
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setText(prefs().getString("apiKey", ""));
        final EditText model = new EditText(this);
        model.setHint("Modelo");
        model.setSingleLine(true);
        model.setText(prefs().getString("model", AiToneEngine.DEFAULT_MODEL));
        final EditText endpoint = new EditText(this);
        endpoint.setHint("Endereço da API");
        endpoint.setSingleLine(true);
        endpoint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        endpoint.setText(prefs().getString("endpoint", AiToneEngine.DEFAULT_ENDPOINT));
        box.addView(label("A IA é opcional. Com ela, o app envia o nome da música, os acordes e as medidas do som (nunca o áudio) "
                + "para o serviço abaixo, usando a sua chave. A chave fica guardada só neste aparelho.", 13, MUTED));
        box.addView(key);
        box.addView(model);
        box.addView(endpoint);
        new AlertDialog.Builder(this)
                .setTitle("IA online")
                .setView(box)
                .setPositiveButton("Salvar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs().edit()
                                .putString("apiKey", key.getText().toString().trim())
                                .putString("model", model.getText().toString().trim())
                                .putString("endpoint", endpoint.getText().toString().trim())
                                .apply();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ------------------------------------------------------------------ arquivos

    private void export(String kind) {
        final ToneChain chain = lastChain;
        if (chain == null) {
            toast("Gere o timbre primeiro.");
            return;
        }
        try {
            EffectCatalog catalog = AppState.catalog(this);
            // o nome pode ter sido digitado ou trocado depois de gerar: vale o que está no campo agora
            String customName = toneName();
            if (!customName.isEmpty() && !customName.equals(chain.name)) {
                chain.name = customName;
                resultView.setText(ChainExporter.toText(chain, catalog));
            }
            String base = fileBase(chain, customName);
            byte[] bytes;
            String mime, name, extra = "";
            if ("json".equals(kind)) {
                bytes = ChainExporter.toJson(chain, catalog).getBytes(StandardCharsets.UTF_8);
                mime = "application/json";
                name = base + ".json";
            } else if ("pdf".equals(kind)) {
                bytes = PdfExporter.create(ChainExporter.toText(chain, catalog));
                mime = "application/pdf";
                name = base + ".pdf";
            } else if ("prst".equals(kind)) {
                bytes = PrstWriter.create(AppState.prstTemplate(this), catalog, chain);
                mime = "application/octet-stream";
                name = base + ".prst";
                extra = "\n\nPreset \"" + PrstWriter.presetName(chain.name) + "\" no formato da Valeton GP-150. "
                        + "Importe pelo programa ou app da Valeton.";
            } else {
                bytes = ChainExporter.toText(chain, catalog).getBytes(StandardCharsets.UTF_8);
                mime = "text/plain";
                name = base + ".txt";
            }
            final Uri uri = saveToDownloads(name, mime, bytes);
            final String shareMime = mime;
            new AlertDialog.Builder(this)
                    .setTitle("Arquivo salvo")
                    .setMessage("Downloads/TimbreChord/" + name + extra)
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Compartilhar", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Intent send = new Intent(Intent.ACTION_SEND).setType(shareMime)
                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(Intent.createChooser(send, "Compartilhar arquivo"));
                        }
                    })
                    .show();
        } catch (Exception e) {
            toast("Não consegui salvar o arquivo: " + e.getMessage());
        }
    }

    private static String fileBase(ToneChain chain, String customName) {
        String source = !customName.isEmpty() ? customName : chain.songTitle.isEmpty() ? chain.name : chain.songTitle;
        String clean = java.text.Normalizer.normalize(source, java.text.Normalizer.Form.NFD)
                .replaceAll("[^A-Za-z0-9 _-]", "").trim().replaceAll("\\s+", "_");
        if (clean.length() > 40) clean = clean.substring(0, 40);
        if (clean.isEmpty()) clean = "timbre";
        return clean + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    private Uri saveToDownloads(String name, String mime, byte[] bytes) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TimbreChord");
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new java.io.IOException("o Android recusou a criação do arquivo");
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new java.io.IOException("não foi possível abrir o arquivo para escrita");
            out.write(bytes);
        }
        return uri;
    }

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    protected void onStart() {
        super.onStart();
        CaptureService.bubbleWanted = false;
        ui.post(ticker);
    }

    @Override
    protected void onStop() {
        ui.removeCallbacks(ticker);
        saveChart();
        // fora do app, o acorde segue em um quadrinho por cima do que estiver na tela
        CaptureService.bubbleWanted = CaptureService.running && !isFinishing() && prefs().getBoolean("bubble", true);
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("stems", stemPanelShown);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (stemPanelShown) showStemPanel(false);
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(ticker);
        if (isFinishing()) {
            FileScanner.shared.cancel();
            FilePlayer.shared.close();
            stopService(new Intent(this, CaptureService.class));
            AppState.analyzer.reset();
        }
        super.onDestroy();
    }
}
