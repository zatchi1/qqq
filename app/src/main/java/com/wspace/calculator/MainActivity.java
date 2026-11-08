package com.wspace.calculator;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * W-SPACE CALCULATOR.
 *
 * Screen = SpaceView (full-screen animated backdrop with the floating alien) + calculator panel
 * (DisplayView + glass keypad) + EliminationView overlay (cinematic scene) + audio controls.
 *
 * Input state lives in {@link #expr} using ASCII operators (+ - * /); the display maps them to the
 * proper glyphs. Evaluation is done by {@link Calc}, which is plain Java and unit-tested.
 */
public class MainActivity extends Activity {

    /** Expressions with at least this many binary operations trigger the ELIMINATED scene. */
    private static final int ELIMINATION_OPS = 3;
    private static final int MAX_LEN = 22;

    private SpaceView space;
    private DisplayView display;
    private EliminationView elim;
    private LinearLayout panel;
    private LinearLayout soundPanel;
    private GlassButton soundBtn;
    private SoundEngine sound;

    private String expr = "";
    private String lastExprText = "";
    private boolean justEvaluated = false;
    private boolean blank = false;      // true after C: screen is completely empty
    private boolean locked = false;     // input disabled while a scene plays
    private boolean soundPanelOpen = false;

    // ------------------------------------------------------------------ setup
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sound = new SoundEngine();
        sound.start();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        space = new SpaceView(this);
        root.addView(space, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        // top area: tapping the alien makes it react
        View top = new View(this);
        top.setOnClickListener(v -> { space.bump(); sound.play("equals"); });
        column.addView(top, new LinearLayout.LayoutParams(-1, 0, 38f));

        panel = buildPanel();
        column.addView(panel, new LinearLayout.LayoutParams(-1, 0, 62f));

        buildSoundControls(root);

        elim = new EliminationView(this);
        root.addView(elim, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        refresh(false);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private LinearLayout buildPanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xCC0A1838, 0xE6050818});
        float r = dp(28);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setStroke(dp(2), 0x6618F5C8);
        p.setBackground(bg);

        display = new DisplayView(this);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, 0, 21f);
        dl.setMargins(dp(2), 0, dp(2), dp(8));
        p.addView(display, dl);

        // {label, key, kind}
        Object[][] keys = {
                {"C", "C", GlassButton.FUNCTION}, {GlassButton.ICON_BACK, "B", GlassButton.FUNCTION},
                {"%", "%", GlassButton.FUNCTION}, {"÷", "/", GlassButton.OPERATOR},
                {"7", "7", GlassButton.DIGIT}, {"8", "8", GlassButton.DIGIT},
                {"9", "9", GlassButton.DIGIT}, {"×", "*", GlassButton.OPERATOR},
                {"4", "4", GlassButton.DIGIT}, {"5", "5", GlassButton.DIGIT},
                {"6", "6", GlassButton.DIGIT}, {"−", "-", GlassButton.OPERATOR},
                {"1", "1", GlassButton.DIGIT}, {"2", "2", GlassButton.DIGIT},
                {"3", "3", GlassButton.DIGIT}, {"+", "+", GlassButton.OPERATOR},
                {"±", "N", GlassButton.FUNCTION}, {"0", "0", GlassButton.DIGIT},
                {".", ".", GlassButton.DIGIT}, {"=", "=", GlassButton.EQUALS},
        };
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        p.addView(pad, new LinearLayout.LayoutParams(-1, 0, 79f));
        for (int row = 0; row < 5; row++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            pad.addView(line, new LinearLayout.LayoutParams(-1, 0, 1f));
            for (int col = 0; col < 4; col++) {
                Object[] k = keys[row * 4 + col];
                final String key = (String) k[1];
                GlassButton b = new GlassButton(this, (String) k[0], (Integer) k[2]);
                b.setOnClickListener(v -> onKey(key));
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, -1, 1f);
                bl.setMargins(dp(3), dp(3), dp(3), dp(3));
                line.addView(b, bl);
            }
        }
        return p;
    }

    private void buildSoundControls(FrameLayout root) {
        soundBtn = new GlassButton(this, GlassButton.ICON_SOUND, GlassButton.ICON);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(dp(50), dp(50),
                Gravity.TOP | Gravity.END);
        bl.setMargins(0, dp(26), dp(10), 0);
        root.addView(soundBtn, bl);

        soundPanel = new LinearLayout(this);
        soundPanel.setOrientation(LinearLayout.VERTICAL);
        soundPanel.setPadding(dp(16), dp(12), dp(16), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE071428);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(2), 0x8018F5C8);
        soundPanel.setBackground(bg);
        soundPanel.setVisibility(View.GONE);

        TextView title = label("AUDIO", 13, 0xFF18F5C8);
        soundPanel.addView(title);
        addSlider("MUSIC", sound.getMusicVolume(), new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (user) sound.setMusicVolume(v / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        addSlider("EFFECTS", sound.getSfxVolume(), new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (user) sound.setSfxVolume(v / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { sound.play("click"); }
        });

        Switch mute = new Switch(this);
        mute.setText("MUTE ALL");
        mute.setTextColor(0xFFFFFFFF);
        mute.setTypeface(Typeface.DEFAULT_BOLD);
        mute.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mute.setOnCheckedChangeListener((b, checked) -> {
            sound.setMuted(checked);
            soundBtn.setMuted(checked);
        });
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2);
        ml.topMargin = dp(10);
        soundPanel.addView(mute, ml);

        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(dp(240), -2, Gravity.TOP | Gravity.END);
        pl.setMargins(0, dp(26 + 50 + 8), dp(10), 0);
        root.addView(soundPanel, pl);

        soundBtn.setOnClickListener(v -> {
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
        t.setTypeface(Typeface.DEFAULT_BOLD);
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
        ColorStateList cs = ColorStateList.valueOf(0xFF18F5C8);
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
    }

    @Override protected void onPause() {
        super.onPause();
        sound.pause();
        space.setRunning(false);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        sound.release();
    }

    // ------------------------------------------------------------------ input
    private void onKey(String key) {
        if (locked) return;
        space.bump();
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
    }

    private void playEliminationScene(String e, String res) {
        locked = true;
        sound.duckMusic(true);
        sound.play("elim");
        panel.animate().alpha(0.25f).setDuration(150).start();
        elim.start(() -> {
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
        sound.play("slice");
        expr = "";
        lastExprText = "";
        bigText = "";
        justEvaluated = false;
        blank = true;
        display.startSlice(() -> locked = false);
    }
}
