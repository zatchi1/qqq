package com.wspace.calculator;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Washwashni.
 *
 * Pixel-art calculator. Screen = SpaceView (empty night sky with a few far stars) + DisplayView and
 * PixelButton keypad + CharacterView (tiny character walking on the key rows) + EliminationView
 * overlay (ELIMINATED! scene) + audio controls.
 *
 * Input state lives in {@link #expr} using ASCII operators (+ - * /); the display maps them to the
 * proper glyphs. Evaluation is done by {@link Calc}, which is plain Java and unit-tested.
 */
public class MainActivity extends Activity {

    /** Expressions with at least this many binary operations trigger the ELIMINATED scene. */
    private static final int ELIMINATION_OPS = 3;
    private static final int MAX_LEN = 22;

    private SpaceView space;
    private PanelFrame frame;
    private PixelButton gearBtn;
    private Switch muteSwitch;
    private DisplayView display;
    private CinematicView elim;
    private LinearLayout panel;
    private LinearLayout soundPanel;
    private PixelButton soundBtn;
    private SoundEngine sound;

    private String expr = "";
    private String lastExprText = "";
    private boolean justEvaluated = false;
    private boolean blank = false;      // true after C: screen is completely empty
    private boolean locked = false;     // input disabled while a scene plays
    private boolean soundPanelOpen = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Trigger106 trigger106 = new Trigger106();
    private final Runnable play106 = this::fire106;

    // ------------------------------------------------------------------ setup
    private CharacterView character;
    private LinearLayout pad;
    private final LinearLayout[] rows = new LinearLayout[5];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sound = new SoundEngine(this);
        sound.start();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF090914);

        space = new SpaceView(this);
        root.addView(space, new FrameLayout.LayoutParams(-1, -1));

        frame = new PanelFrame(this);
        root.addView(frame, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(10), dp(8), dp(10), dp(10));
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        // header: app name on the left (the sound button floats on the right)
        TitleView title = new TitleView(this);
        column.addView(title, new LinearLayout.LayoutParams(-1, dp(88)));

        panel = buildPanel();
        column.addView(panel, new LinearLayout.LayoutParams(-1, 0, 1f));

        character = new CharacterView(this);
        root.addView(character, new FrameLayout.LayoutParams(-1, -1));

        buildSoundControls(root);

        elim = new CinematicView(this, sound);
        elim.preload();
        root.addView(elim, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        pad.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> placeCharacter());
        panel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> placeCharacter());
        refresh(false);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    /** Tells the overlays where things are: the keypad (floating character), display (peeking character), frame. */
    private void placeCharacter() {
        int[] o = new int[2], q = new int[2];
        character.getLocationOnScreen(o);
        pad.getLocationOnScreen(q);
        float pl = q[0] - o[0], pt = q[1] - o[1];
        character.setFloatArea(pl + dp(26), pt + dp(26), pl + pad.getWidth() - dp(26), pt + pad.getHeight() - dp(26));
        display.getLocationOnScreen(q);
        float w = character.getWidth();
        character.setPeek(w * 0.56f, q[1] - o[1], w * 0.30f);
        int[] fo = new int[2];
        frame.getLocationOnScreen(fo);
        panel.getLocationOnScreen(q);
        frame.setRect(q[0] - fo[0] - dp(8), q[1] - fo[1] - dp(6), q[0] - fo[0] + panel.getWidth() + dp(8),
                q[1] - fo[1] + panel.getHeight() + dp(6));
    }

    private LinearLayout buildPanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);

        display = new DisplayView(this);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, 0, 22f);
        dl.setMargins(0, 0, 0, dp(10));
        p.addView(display, dl);

        // {label, key, kind}
        Object[][] keys = {
                {"C", "C", PixelButton.CLEAR}, {PixelButton.ICON_BACK, "B", PixelButton.FUNCTION},
                {"%", "%", PixelButton.FUNCTION}, {"\u00F7", "/", PixelButton.OPERATOR},
                {"7", "7", PixelButton.DIGIT}, {"8", "8", PixelButton.DIGIT},
                {"9", "9", PixelButton.DIGIT}, {"\u00D7", "*", PixelButton.OPERATOR},
                {"4", "4", PixelButton.DIGIT}, {"5", "5", PixelButton.DIGIT},
                {"6", "6", PixelButton.DIGIT}, {"\u2212", "-", PixelButton.OPERATOR},
                {"1", "1", PixelButton.DIGIT}, {"2", "2", PixelButton.DIGIT},
                {"3", "3", PixelButton.DIGIT}, {"+", "+", PixelButton.OPERATOR},
                {"\u00B1", "N", PixelButton.SIGN}, {"0", "0", PixelButton.DIGIT},
                {".", ".", PixelButton.DIGIT}, {"=", "=", PixelButton.EQUALS},
        };
        pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        p.addView(pad, new LinearLayout.LayoutParams(-1, 0, 78f));
        for (int row = 0; row < 5; row++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            rows[row] = line;
            pad.addView(line, new LinearLayout.LayoutParams(-1, 0, 1f));
            for (int col = 0; col < 4; col++) {
                Object[] k = keys[row * 4 + col];
                final String key = (String) k[1];
                PixelButton b = new PixelButton(this, (String) k[0], (Integer) k[2]);
                b.setOnClickListener(v -> onKey(key));
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, -1, 1f);
                bl.setMargins(dp(2), dp(2), dp(2), dp(2));
                line.addView(b, bl);
            }
        }
        return p;
    }

    private static final String[] CROWN = {
            ".#..#..#.",
            "##.###.##",
            "#########",
            "#########",
            ".#######."};

    /** "W-SPACE CALCULATOR" logo in pixel lettering with a little crown. */
    private static final class TitleView extends View {
        private final Paint p = new Paint();
        private final float density;

        TitleView(Context ctx) {
            super(ctx);
            density = ctx.getResources().getDisplayMetrics().density;
        }

        @Override
        protected void onDraw(Canvas c) {
            String a = "W-SPACE", b = "CALCULATOR";
            float maxW = getWidth() * 0.38f;
            float uB = Math.max(2f, (float) Math.floor(Math.min(maxW / FontData.textWidth(a), density * 4.4f)));
            float uS = Math.max(2f, (float) Math.floor(Math.min(maxW / FontData.textWidth(b), density * 2.8f)));
            float x = Math.round(density * 8f);
            float cu = Math.max(2f, Math.round(uB * 0.8f));
            float crownH = CROWN.length * cu;
            float total = crownH + FontData.HEIGHT * uB + FontData.HEIGHT * uS + uB * 1.2f;
            float y0 = Math.max(0f, (getHeight() - total) / 2f);

            // crown over the right half of "W-SPACE", gently bobbing
            float cx = x + FontData.textWidth(a) * uB * 0.62f;
            float bob = (float) Math.sin(SystemClock.uptimeMillis() / 600.0) * cu * 0.5f;
            p.setStyle(Paint.Style.FILL);
            for (int r = 0; r < CROWN.length; r++) {
                for (int q = 0; q < CROWN[r].length(); q++) {
                    if (CROWN[r].charAt(q) != '#') continue;
                    p.setColor(r < 2 ? 0xFFD6FF6A : 0xFF8DE83C);
                    c.drawRect(Math.round(cx + q * cu), Math.round(y0 + bob + r * cu),
                            Math.round(cx + (q + 1) * cu), Math.round(y0 + bob + (r + 1) * cu), p);
                }
            }
            float y1 = Math.round(y0 + crownH + uB * 0.4f);
            PixelFont.outlined(c, p, a, x, y1, uB, 0xFF55F04A, 0xFF0B3A12);
            float y2 = Math.round(y1 + FontData.HEIGHT * uB + uB * 0.8f);
            PixelFont.outlined(c, p, b, x, y2, uS, 0xFFFFFFFF, 0xFF2A2A78);
            postInvalidateDelayed(60);
        }
    }

    private void buildSoundControls(FrameLayout root) {
        gearBtn = new PixelButton(this, PixelButton.ICON_GEAR, PixelButton.ICON_LIME);
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.TOP | Gravity.END);
        gl.setMargins(0, dp(10), dp(10), 0);
        root.addView(gearBtn, gl);

        soundBtn = new PixelButton(this, PixelButton.ICON_SOUND, PixelButton.ICON);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.TOP | Gravity.END);
        bl.setMargins(0, dp(10), dp(10 + 54 + 8), 0);
        root.addView(soundBtn, bl);

        soundPanel = new LinearLayout(this);
        soundPanel.setOrientation(LinearLayout.VERTICAL);
        soundPanel.setPadding(dp(16), dp(12), dp(16), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2101030);
        bg.setCornerRadius(0f);
        bg.setStroke(dp(3), 0xFF3CC9A6);
        soundPanel.setBackground(bg);
        soundPanel.setVisibility(View.GONE);

        TextView title = label("AUDIO", 13, 0xFF8DFFB0);
        soundPanel.addView(title);
        addSlider("MUSIC", sound.getMusicVolume(), new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (user) sound.setMusicVolume(v / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        addSlider("KEYS", sound.getSfxVolume(), new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (user) sound.setSfxVolume(v / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { sound.play("click"); }
        });

        Switch mute = new Switch(this);
        muteSwitch = mute;
        mute.setText("MUTE ALL");
        mute.setTextColor(0xFFFFFFFF);
        mute.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mute.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mute.setOnCheckedChangeListener((b, checked) -> {
            sound.setMuted(checked);
            soundBtn.setMuted(checked);
        });
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2);
        ml.topMargin = dp(10);
        soundPanel.addView(mute, ml);

        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(dp(240), -2, Gravity.TOP | Gravity.END);
        pl.setMargins(0, dp(10 + 54 + 6), dp(10), 0);
        root.addView(soundPanel, pl);

        soundBtn.setOnClickListener(v -> muteSwitch.setChecked(!muteSwitch.isChecked()));
        gearBtn.setOnClickListener(v -> {
            soundPanelOpen = !soundPanelOpen;
            if (soundPanelOpen) {
                soundPanel.setAlpha(0f);
                soundPanel.setVisibility(View.VISIBLE);
                soundPanel.animate().alpha(1f).setDuration(160).start();
            } else {
                soundPanel.animate().alpha(0f).setDuration(140)
                        .withEndAction(() -> soundPanel.setVisibility(View.GONE)).start();
            }
        });
    }

    private TextView label(String text, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setLetterSpacing(0.12f);
        return t;
    }

    private void addSlider(String name, float value, SeekBar.OnSeekBarChangeListener l) {
        TextView t = label(name, 11, 0xFF9FFFE8);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = dp(8);
        soundPanel.addView(t, tl);
        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(Math.round(value * 100f));
        ColorStateList cs = ColorStateList.valueOf(0xFF3CC9A6);
        sb.setProgressTintList(cs);
        sb.setThumbTintList(cs);
        sb.setOnSeekBarChangeListener(l);
        soundPanel.addView(sb, new LinearLayout.LayoutParams(-1, -2));
    }

    // ------------------------------------------------------------------ lifecycle
    @Override protected void onResume() {
        super.onResume();
        sound.resume();
        space.setRunning(true);
        frame.setRunning(true);
        character.setRunning(true);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(play106);
        trigger106.reset();
        if (elim.isRunning()) elim.abort();
        sound.pause();
        space.setRunning(false);
        frame.setRunning(false);
        character.setRunning(false);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(play106);
        sound.release();
    }

    // ------------------------------------------------------------------ input
    private void onKey(String key) {
        if (locked) return;
        character.hop();
        switch (key) {
            case "C":
                doClear();
                return;
            case "=":
                doEquals();
                return;
            case "B":
                sound.play("func");
                backspace();
                break;
            case "N":
                sound.play("func");
                negate();
                break;
            case "%":
                sound.play("op");
                percent();
                break;
            case "+": case "-": case "*": case "/":
                sound.play("op");
                typeOperator(key.charAt(0));
                break;
            case ".":
                sound.play("click");
                typeDot();
                break;
            default:
                sound.play("click");
                typeDigit(key.charAt(0));
                break;
        }
        blank = false;
        refresh(false);
        sync106();
    }

    // ------------------------------------------------------------------ special 106 sound
    /** Schedules / cancels the 106 sound whenever the expression changes (see Trigger106). */
    private void sync106() {
        switch (trigger106.update(expr)) {
            case ARM:
                handler.removeCallbacks(play106);
                handler.postDelayed(play106, Trigger106.DELAY_MS);
                break;
            case CANCEL:
                handler.removeCallbacks(play106);
                break;
            default:
                break;
        }
    }

    private void fire106() {
        if (!Trigger106.VALUE.equals(expr) || locked) return;     // changed in the meantime: stay silent
        sound.duckMusic(true);
        sound.playScene("s106");
        handler.postDelayed(() -> { if (!locked) sound.duckMusic(false); }, 3000);
    }

    private void startFresh() {
        if (justEvaluated) {
            expr = "";
            lastExprText = "";
            justEvaluated = false;
        }
    }

    /** Result on screen becomes editable text; operators continue from it. */
    private void continueFromResult() {
        if (justEvaluated) {
            justEvaluated = false;
            lastExprText = "";
        }
    }

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }

    private String lastNumber() {
        int i = expr.length();
        while (i > 0 && (isDigit(expr.charAt(i - 1)) || expr.charAt(i - 1) == '.')) i--;
        return expr.substring(i);
    }

    private char lastChar() { return expr.isEmpty() ? 0 : expr.charAt(expr.length() - 1); }

    private void typeDigit(char d) {
        startFresh();
        if (expr.length() >= MAX_LEN) return;
        if (lastChar() == '%') return;
        if (lastNumber().equals("0")) expr = expr.substring(0, expr.length() - 1);
        expr += d;
    }

    private void typeDot() {
        startFresh();
        if (expr.length() >= MAX_LEN - 1 || lastChar() == '%') return;
        String tok = lastNumber();
        if (tok.contains(".")) return;
        if (tok.isEmpty()) expr += "0";
        expr += ".";
    }

    private void typeOperator(char op) {
        continueFromResult();
        if (expr.isEmpty()) {
            if (op == '-') expr = "-";
            return;
        }
        if (expr.equals("-")) return;
        char last = lastChar();
        if (Calc.isOperator(last)) {
            if (op == '-' && (last == '*' || last == '/')) {
                if (expr.length() < MAX_LEN) expr += "-";          // sign after * or /
            } else {
                expr = Calc.stripTrailingOperators(expr) + op;      // replace the operator
            }
            return;
        }
        if (expr.length() < MAX_LEN) expr += op;
    }

    private void percent() {
        continueFromResult();
        char last = lastChar();
        if ((isDigit(last) || last == '%') && expr.length() < MAX_LEN) expr += "%";
    }

    private void backspace() {
        continueFromResult();
        if (!expr.isEmpty()) expr = expr.substring(0, expr.length() - 1);
    }

    private void negate() {
        continueFromResult();
        char last = lastChar();
        if (!(isDigit(last) || last == '.')) return;
        int ns = expr.length();
        while (ns > 0 && (isDigit(expr.charAt(ns - 1)) || expr.charAt(ns - 1) == '.')) ns--;
        if (ns == 0) {
            if (expr.length() < MAX_LEN) expr = "-" + expr;
            return;
        }
        char b = expr.charAt(ns - 1);
        if (b == '-') {
            boolean unary = ns - 1 == 0 || Calc.isOperator(expr.charAt(ns - 2));
            expr = expr.substring(0, ns - 1) + (unary ? "" : "+") + expr.substring(ns);
        } else if (b == '+') {
            expr = expr.substring(0, ns - 1) + "-" + expr.substring(ns);
        } else if (expr.length() < MAX_LEN) {   // after * or /
            expr = expr.substring(0, ns) + "-" + expr.substring(ns);
        }
    }

    // ------------------------------------------------------------------ display
    private String bigText = "0";

    private void refresh(boolean popAll) {
        if (!justEvaluated) {
            bigText = expr.isEmpty() ? (blank ? "" : "0") : Calc.toDisplay(expr);
        }
        display.setTexts(justEvaluated ? lastExprText : "", bigText, popAll);
    }

    // ------------------------------------------------------------------ equals
    private void doEquals() {
        if (justEvaluated) return;
        String e = Calc.stripTrailingOperators(expr);
        if (e.isEmpty() || e.equals("-")) return;

        int ops = Calc.countOperations(e);
        String res;
        boolean error = false;
        try {
            res = Calc.evaluateToString(e);
        } catch (RuntimeException ex) {
            res = "ERROR";
            error = true;
        }

        if (error) {
            sound.play("error");
            applyResult(e, res, true);
            return;
        }
        if (ops >= ELIMINATION_OPS) {
            playEliminationScene(e, res);
            return;
        }
        sound.play("equals");
        applyResult(e, res, false);
    }

    private void applyResult(String e, String res, boolean error) {
        lastExprText = Calc.toDisplay(e);
        bigText = Calc.toDisplay(res);
        expr = error ? "" : res;       // a result can be reused in the next calculation
        justEvaluated = true;
        blank = false;
        refresh(true);
        sync106();
    }

    private void playEliminationScene(String e, String res) {
        locked = true;
        sound.duckMusic(true);
        panel.animate().alpha(0.25f).setDuration(150).start();
        elim.start(Calc.toDisplay(e), Calc.toDisplay(res), () -> {
            applyResult(e, res, false);          // result is revealed behind the fading overlay
            sound.duckMusic(false);
            panel.animate().alpha(1f).setDuration(260).start();
            elim.animate().alpha(0f).setDuration(260)
                    .withEndAction(() -> { elim.setVisibility(View.GONE); elim.setAlpha(1f); locked = false; })
                    .start();
        });
    }

    // ------------------------------------------------------------------ clear (digit massacre)
    private void doClear() {
        if (display.isEmpty()) {
            sound.play("func");
            return;
        }
        locked = true;
        expr = "";
        lastExprText = "";
        bigText = "";
        justEvaluated = false;
        blank = true;
        sync106();                                  // clearing cancels a pending 106
        display.startSlice(() -> sound.playScene("kill"), sound.clock("kill"), () -> locked = false);
    }
}
