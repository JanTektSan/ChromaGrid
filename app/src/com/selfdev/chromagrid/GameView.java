package com.selfdev.chromagrid;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;

public class GameView extends View {
    private static final int N = 8;
    private static final int[] COLORS = {
            0xFFFF6B6B, 0xFFFFB547, 0xFF8BD45B, 0xFF2EC4B6,
            0xFF4D9DE0, 0xFF9B6BFF, 0xFFFF6FB5
    };
    private static final int GREY = 0xFF5A6080;
    private static final String[] PRAISE = {"", "", "Great!", "Excellent!", "Amazing!", "Unbelievable!"};

    // ---- game state ----
    private final int[][] board = new int[N][N]; // 0 = empty, else color index + 1
    private final int[] trayShape = {-1, -1, -1};
    private final int[] trayColor = new int[3];
    private final boolean[] trayFits = new boolean[3];
    private final long[] traySpawn = new long[3];
    private int score, best, combo, movesSinceClear;
    private boolean gameOver, newBest;
    private long gameOverTime;

    private final Random rng = new Random();
    private final Sfx sfx;
    private final SharedPreferences prefs;

    // ---- layout ----
    private float W, H, cell, boardL, boardT, boardS, trayCY, trayCell, rowY, scoreY;
    private final RectF soundBtn = new RectF(), restartBtn = new RectF(), againBtn = new RectF();
    private Bitmap[] blockBmp = new Bitmap[0];
    private Bitmap greyBmp;
    private LinearGradient bgShader;

    // ---- drag ----
    private int dragSlot = -1;
    private float fingerX, fingerY;
    private long dragStart;
    private int ghostR, ghostC;
    private boolean ghostValid;
    private long previewLines;
    private int returnSlot = -1;
    private float returnCX, returnCY, returnSize;
    private long returnStart;

    // ---- effects ----
    private static final class FadeCell { int r, c, color; long start; }
    private static final class Particle { float x, y, vx, vy, size; int color; long start, life; }
    private static final class FloatText { String text; float x, y, size; int color; long start, life; }
    private final ArrayList<FadeCell> fading = new ArrayList<>();
    private final ArrayList<Particle> particles = new ArrayList<>();
    private final ArrayList<FloatText> texts = new ArrayList<>();
    private final long[][] placedAt = new long[N][N];
    private float shownScore;
    private long lastFrame, shakeStart, restartArmedUntil;
    private float shakeAmp;

    // ---- paints ----
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();

    public GameView(Context ctx) {
        super(ctx);
        sfx = new Sfx(ctx);
        prefs = ctx.getSharedPreferences("chroma_grid", Context.MODE_PRIVATE);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
        stroke.setTypeface(text.getTypeface());
        stroke.setTextAlign(Paint.Align.CENTER);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(0xCC0B0D1F);
        load();
    }

    // =====================================================================
    // State
    // =====================================================================

    private void newGame() {
        for (int[] row : board) java.util.Arrays.fill(row, 0);
        score = 0;
        shownScore = 0;
        combo = 0;
        movesSinceClear = 0;
        gameOver = false;
        newBest = false;
        fading.clear();
        particles.clear();
        texts.clear();
        refill();
        save();
        invalidate();
    }

    void save() {
        StringBuilder b = new StringBuilder(64);
        for (int r = 0; r < N; r++)
            for (int c = 0; c < N; c++) b.append((char) ('0' + board[r][c]));
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < 3; i++) t.append(trayShape[i]).append(',').append(trayColor[i]).append(';');
        prefs.edit()
                .putString("board", b.toString())
                .putString("tray", t.toString())
                .putInt("score", score)
                .putInt("best", best)
                .putInt("combo", combo)
                .putInt("msc", movesSinceClear)
                .putBoolean("sound", sfx.enabled)
                .apply();
    }

    private void load() {
        best = prefs.getInt("best", 0);
        sfx.enabled = prefs.getBoolean("sound", true);
        String b = prefs.getString("board", null);
        String t = prefs.getString("tray", null);
        boolean ok = b != null && b.length() == 64 && t != null;
        if (ok) {
            try {
                for (int i = 0; i < 64; i++) {
                    int v = b.charAt(i) - '0';
                    if (v < 0 || v > COLORS.length) throw new IllegalStateException();
                    board[i / 8][i % 8] = v;
                }
                String[] parts = t.split(";");
                for (int i = 0; i < 3; i++) {
                    String[] p = parts[i].split(",");
                    int s = Integer.parseInt(p[0]);
                    if (s >= Shapes.ALL.size()) throw new IllegalStateException();
                    trayShape[i] = s;
                    trayColor[i] = Math.floorMod(Integer.parseInt(p[1]), COLORS.length);
                }
            } catch (RuntimeException e) {
                ok = false;
            }
        }
        if (!ok) { newGame(); return; }
        score = prefs.getInt("score", 0);
        shownScore = score;
        combo = prefs.getInt("combo", 0);
        movesSinceClear = prefs.getInt("msc", 0);
        if (trayShape[0] < 0 && trayShape[1] < 0 && trayShape[2] < 0) refill();
        updateFits();
        checkGameOver(SystemClock.uptimeMillis());
    }

    void release() { sfx.release(); }

    private long occupancy() {
        long occ = 0;
        for (int r = 0; r < N; r++)
            for (int c = 0; c < N; c++)
                if (board[r][c] != 0) occ |= 1L << (r * 8 + c);
        return occ;
    }

    private void refill() {
        long occ = occupancy();
        int[] set = new int[3];
        boolean found = false;
        // Prefer a set that can be fully placed; fall back to one where at least one fits.
        for (int attempt = 0; attempt < 25 && !found; attempt++) {
            for (int i = 0; i < 3; i++) set[i] = Shapes.randomIndex(rng);
            found = Shapes.solvable(occ, set);
        }
        if (!found) {
            for (int attempt = 0; attempt < 40 && !found; attempt++) {
                for (int i = 0; i < 3; i++) set[i] = Shapes.randomIndex(rng);
                for (int s : set) if (Shapes.fitsAnywhere(occ, Shapes.ALL.get(s))) { found = true; break; }
            }
        }
        if (!found) {
            for (int s = 0; s < Shapes.ALL.size(); s++)
                if (Shapes.fitsAnywhere(occ, Shapes.ALL.get(s))) { set[0] = s; break; }
        }
        long now = SystemClock.uptimeMillis();
        int lastColor = -1;
        for (int i = 0; i < 3; i++) {
            trayShape[i] = set[i];
            int col;
            do { col = rng.nextInt(COLORS.length); } while (col == lastColor);
            trayColor[i] = lastColor = col;
            traySpawn[i] = now + i * 70L;
        }
        updateFits();
    }

    private void updateFits() {
        long occ = occupancy();
        for (int i = 0; i < 3; i++)
            trayFits[i] = trayShape[i] >= 0 && Shapes.fitsAnywhere(occ, Shapes.ALL.get(trayShape[i]));
    }

    private void checkGameOver(long now) {
        boolean any = false;
        for (int i = 0; i < 3; i++) if (trayShape[i] >= 0 && trayFits[i]) any = true;
        if (!any && !gameOver) {
            gameOver = true;
            gameOverTime = now + 450;
            postDelayed(sfx::over, 450);
        }
    }

    private void place(int slot, int r0, int c0) {
        Shapes.Shape s = Shapes.ALL.get(trayShape[slot]);
        int color = trayColor[slot] + 1;
        long now = SystemClock.uptimeMillis();
        for (int r = 0; r < s.h; r++)
            for (int c = 0; c < s.w; c++)
                if (s.cell[r][c]) {
                    board[r0 + r][c0 + c] = color;
                    placedAt[r0 + r][c0 + c] = now;
                }
        trayShape[slot] = -1;
        score += s.count;

        long full = Shapes.fullLines(occupancy());
        int lines = 0;
        for (int i = 0; i < 8; i++) {
            long row = 0xFFL << (i * 8);
            if ((full & row) == row) lines++;
            long col = Shapes.COL0 << i;
            if ((full & col) == col) lines++;
        }
        float cx = boardL + (c0 + s.w / 2f) * cell;
        float cy = boardT + (r0 + s.h / 2f) * cell;

        if (lines > 0) {
            combo++;
            movesSinceClear = 0;
            int gain = 10 * lines * lines * combo;
            float pr = r0 + s.h / 2f, pc = c0 + s.w / 2f;
            for (int r = 0; r < N; r++)
                for (int c = 0; c < N; c++) {
                    if ((full & (1L << (r * 8 + c))) == 0) continue;
                    FadeCell f = new FadeCell();
                    f.r = r;
                    f.c = c;
                    f.color = board[r][c] - 1;
                    f.start = now + (long) ((Math.abs(r + 0.5f - pr) + Math.abs(c + 0.5f - pc)) * 28);
                    fading.add(f);
                    spawnParticles(boardL + (c + 0.5f) * cell, boardT + (r + 0.5f) * cell, COLORS[f.color], f.start);
                    board[r][c] = 0;
                }
            boolean empty = occupancy() == 0;
            if (empty) gain += 300;
            score += gain;
            addText("+" + gain, cx, cy, cell * 0.62f, Color.WHITE, 0, 900);
            if (combo >= 2) addText("Combo " + combo, W / 2, boardT + boardS * 0.38f, cell * 0.72f, 0xFFFFD166, 80, 1100);
            if (empty) addText("Board Clear!", W / 2, boardT + boardS * 0.55f, cell * 0.7f, 0xFF7CF0C8, 160, 1300);
            else if (lines >= 2) addText(PRAISE[Math.min(lines, PRAISE.length - 1)], W / 2, boardT + boardS * 0.55f,
                    cell * 0.7f, 0xFF9FD8FF, 160, 1100);
            if (lines >= 3 || empty) { shakeAmp = cell * 0.12f; shakeStart = now; }
            sfx.clear(lines, combo);
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        } else {
            if (++movesSinceClear >= 3) combo = 0;
            sfx.place();
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }

        if (score > best) {
            if (best > 0 && !newBest) addText("New Best!", W / 2, scoreY + cell * 0.9f, cell * 0.45f, 0xFFFFD166, 0, 1400);
            newBest = best > 0 || newBest;
            best = score;
        }
        if (trayShape[0] < 0 && trayShape[1] < 0 && trayShape[2] < 0) refill();
        updateFits();
        checkGameOver(now);
        save();
    }

    private void spawnParticles(float x, float y, int color, long start) {
        for (int i = 0; i < 5; i++) {
            Particle p = new Particle();
            p.x = x;
            p.y = y;
            double a = rng.nextDouble() * Math.PI * 2;
            float sp = cell * (2.5f + rng.nextFloat() * 4f);
            p.vx = (float) Math.cos(a) * sp;
            p.vy = (float) Math.sin(a) * sp - cell * 3f;
            p.size = cell * (0.12f + rng.nextFloat() * 0.14f);
            p.color = color;
            p.start = start;
            p.life = 550 + rng.nextInt(300);
            particles.add(p);
        }
    }

    private void addText(String s, float x, float y, float size, int color, long delay, long life) {
        FloatText t = new FloatText();
        t.text = s;
        t.x = x;
        t.y = y;
        t.size = size;
        t.color = color;
        t.start = SystemClock.uptimeMillis() + delay;
        t.life = life;
        texts.add(t);
    }

    // =====================================================================
    // Layout
    // =====================================================================

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        W = w;
        H = h;
        boardS = Math.min(W * 0.92f, H * 0.52f);
        cell = boardS / N;
        boardL = (W - boardS) / 2;
        boardT = Math.max(H * 0.19f, cell * 2.6f);
        rowY = boardT * 0.28f;
        scoreY = boardT * 0.72f;
        float boardB = boardT + boardS;
        trayCY = boardB + (H - boardB) * 0.5f;
        trayCell = Math.min(cell * 0.55f, Math.min(W / 3f / 5.6f, (H - boardB) / 6.5f));
        float br = cell * 0.42f;
        restartBtn.set(W - boardL - br * 2, rowY - br, W - boardL, rowY + br);
        soundBtn.set(restartBtn.left - br * 2.5f, rowY - br, restartBtn.left - br * 0.5f, rowY + br);
        bgShader = new LinearGradient(0, 0, 0, H, 0xFF232854, 0xFF141730, Shader.TileMode.CLAMP);

        int px = Math.max(8, (int) cell);
        blockBmp = new Bitmap[COLORS.length];
        for (int i = 0; i < COLORS.length; i++) blockBmp[i] = makeBlock(COLORS[i], px);
        greyBmp = makeBlock(GREY, px);
    }

    private static Bitmap makeBlock(int color, int s) {
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float in = s * 0.045f, rad = s * 0.2f;
        RectF r = new RectF(in, in, s - in, s - in);
        p.setColor(shade(color, 0.62f));
        c.drawRoundRect(r, rad, rad, p);
        RectF body = new RectF(r.left, r.top, r.right, r.bottom - s * 0.09f);
        p.setShader(new LinearGradient(0, body.top, 0, body.bottom,
                tint(color, 0.28f), color, Shader.TileMode.CLAMP));
        c.drawRoundRect(body, rad, rad, p);
        p.setShader(null);
        p.setColor(0x55FFFFFF);
        RectF gloss = new RectF(body.left + s * 0.14f, body.top + s * 0.09f,
                body.right - s * 0.14f, body.top + s * 0.25f);
        c.drawRoundRect(gloss, s * 0.08f, s * 0.08f, p);
        return b;
    }

    private static int shade(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    private static int tint(int c, float f) {
        return Color.rgb((int) (Color.red(c) + (255 - Color.red(c)) * f),
                (int) (Color.green(c) + (255 - Color.green(c)) * f),
                (int) (Color.blue(c) + (255 - Color.blue(c)) * f));
    }

    private float slotCX(int i) { return W * (i + 0.5f) / 3f; }

    // =====================================================================
    // Input
    // =====================================================================

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        long now = SystemClock.uptimeMillis();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (gameOver) {
                    if (overlayAlpha(now) > 0.8f && againBtn.contains(x, y)) newGame();
                    return true;
                }
                if (grow(soundBtn).contains(x, y)) {
                    sfx.enabled = !sfx.enabled;
                    save();
                    invalidate();
                    return true;
                }
                if (grow(restartBtn).contains(x, y)) {
                    if (now < restartArmedUntil) {
                        restartArmedUntil = 0;
                        newGame();
                    } else {
                        restartArmedUntil = now + 2500;
                        invalidate();
                    }
                    return true;
                }
                if (y > boardT + boardS + cell * 0.1f) {
                    int slot = Math.max(0, Math.min(2, (int) (x / (W / 3f))));
                    if (trayShape[slot] >= 0 && returnSlot != slot) {
                        dragSlot = slot;
                        dragStart = now;
                        fingerX = x;
                        fingerY = y;
                        updateGhost();
                        sfx.pick();
                        invalidate();
                    }
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragSlot >= 0) {
                    fingerX = x;
                    fingerY = y;
                    updateGhost();
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragSlot >= 0) {
                    int slot = dragSlot;
                    if (ghostValid && e.getActionMasked() == MotionEvent.ACTION_UP) {
                        place(slot, ghostR, ghostC);
                    } else {
                        float t = dragT(now);
                        Shapes.Shape s = Shapes.ALL.get(trayShape[slot]);
                        returnSize = lerp(trayCell, cell, t);
                        returnCX = fingerX;
                        returnCY = fingerY - cell * t - s.h * returnSize / 2;
                        returnSlot = slot;
                        returnStart = now;
                    }
                    dragSlot = -1;
                    ghostValid = false;
                    previewLines = 0;
                    invalidate();
                }
                return true;
        }
        return true;
    }

    private RectF grow(RectF r) {
        tmp.set(r);
        tmp.inset(-cell * 0.15f, -cell * 0.15f);
        return tmp;
    }

    private void updateGhost() {
        Shapes.Shape s = Shapes.ALL.get(trayShape[dragSlot]);
        float x0 = fingerX - s.w * cell / 2;
        float y0 = fingerY - cell - s.h * cell;
        ghostC = Math.round((x0 - boardL) / cell);
        ghostR = Math.round((y0 - boardT) / cell);
        long occ = occupancy();
        ghostValid = Shapes.fitsAt(occ, s, ghostR, ghostC);
        previewLines = ghostValid ? Shapes.fullLines(occ | (s.mask << (ghostR * 8 + ghostC))) : 0;
    }

    private float dragT(long now) { return Math.min(1f, (now - dragStart) / 110f); }

    // =====================================================================
    // Drawing
    // =====================================================================

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        boolean animating = false;

        fill.setShader(bgShader);
        canvas.drawRect(0, 0, W, H, fill);
        fill.setShader(null);

        // score counter
        if (shownScore != score) {
            shownScore += (score - shownScore) * Math.min(1f, dt * 9f);
            if (Math.abs(score - shownScore) < 0.5f) shownScore = score;
            animating = true;
        }
        drawTopBar(canvas, now);

        canvas.save();
        if (shakeAmp > 0) {
            float k = 1f - (now - shakeStart) / 320f;
            if (k <= 0) shakeAmp = 0;
            else {
                canvas.translate((float) Math.sin(now * 0.09) * shakeAmp * k, (float) Math.cos(now * 0.11) * shakeAmp * k);
                animating = true;
            }
        }
        animating |= drawBoard(canvas, now);
        animating |= drawEffects(canvas, now, dt);
        canvas.restore();

        animating |= drawTray(canvas, now);
        if (gameOver) {
            drawGameOver(canvas, now);
            if (overlayAlpha(now) < 1f) animating = true;
        }
        if (now < restartArmedUntil) animating = true;
        if (animating || dragSlot >= 0) postInvalidateOnAnimation();
        else lastFrame = 0;
    }

    private void drawTopBar(Canvas c, long now) {
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(cell * 0.4f);
        text.setColor(0xFFFFD166);
        c.drawText("👑 " + best, boardL, rowY + cell * 0.14f, text);
        text.setTextAlign(Paint.Align.CENTER);

        drawRoundButton(c, soundBtn, sfx.enabled ? "♪" : "♪", !sfx.enabled);
        drawRoundButton(c, restartBtn, "↻", false);

        text.setColor(Color.WHITE);
        text.setTextSize(cell * 0.95f);
        c.drawText(String.valueOf(Math.round(shownScore)), W / 2, scoreY + cell * 0.33f, text);

        if (now < restartArmedUntil) {
            text.setTextSize(cell * 0.3f);
            text.setColor(0xFFFFB0B0);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText("Tap again to restart", W - boardL, restartBtn.bottom + cell * 0.45f, text);
            text.setTextAlign(Paint.Align.CENTER);
        }
    }

    private void drawRoundButton(Canvas c, RectF r, String glyph, boolean crossed) {
        fill.setColor(0x33FFFFFF);
        c.drawOval(r, fill);
        text.setColor(crossed ? 0x77FFFFFF : Color.WHITE);
        text.setTextSize(r.height() * 0.55f);
        c.drawText(glyph, r.centerX(), r.centerY() + r.height() * 0.19f, text);
        if (crossed) {
            stroke.setStrokeWidth(r.height() * 0.07f);
            stroke.setColor(0xFFFF6B6B);
            c.drawLine(r.left + r.width() * 0.25f, r.top + r.height() * 0.25f,
                    r.right - r.width() * 0.25f, r.bottom - r.height() * 0.25f, stroke);
            stroke.setColor(0xCC0B0D1F);
        }
    }

    private boolean drawBoard(Canvas c, long now) {
        boolean anim = false;
        float pad = cell * 0.12f;
        fill.setColor(0xFF0F1226);
        tmp.set(boardL - pad, boardT - pad, boardL + boardS + pad, boardT + boardS + pad);
        c.drawRoundRect(tmp, cell * 0.28f, cell * 0.28f, fill);

        fill.setColor(0xFF1C2145);
        for (int r = 0; r < N; r++)
            for (int col = 0; col < N; col++) {
                float x = boardL + col * cell, y = boardT + r * cell;
                tmp.set(x + cell * 0.05f, y + cell * 0.05f, x + cell * 0.95f, y + cell * 0.95f);
                c.drawRoundRect(tmp, cell * 0.16f, cell * 0.16f, fill);
            }

        int dragColor = dragSlot >= 0 ? trayColor[dragSlot] : 0;
        float pulse = 0.75f + 0.25f * (float) Math.sin(now * 0.012);
        for (int r = 0; r < N; r++)
            for (int col = 0; col < N; col++) {
                int v = board[r][col];
                if (v == 0) continue;
                int idx = v - 1;
                float x = boardL + col * cell, y = boardT + r * cell;
                float scale = 1f;
                long age = now - placedAt[r][col];
                if (age < 160) {
                    scale = 1f + 0.12f * (1f - age / 160f);
                    anim = true;
                }
                Bitmap bmp = blockBmp[idx];
                if (gameOver && now > gameOverTime + r * 55L) bmp = greyBmp;
                else if (gameOver) anim = true;
                boolean lit = (previewLines & (1L << (r * 8 + col))) != 0;
                if (lit) {
                    bmp = blockBmp[dragColor];
                    anim = true;
                }
                drawBmp(c, bmp, x, y, cell, scale, 255);
                if (lit) {
                    fill.setColor(Color.argb((int) (70 * pulse), 255, 255, 255));
                    tmp.set(x + cell * 0.05f, y + cell * 0.05f, x + cell * 0.95f, y + cell * 0.95f);
                    c.drawRoundRect(tmp, cell * 0.2f, cell * 0.2f, fill);
                }
            }

        // ghost
        if (dragSlot >= 0 && ghostValid) {
            Shapes.Shape s = Shapes.ALL.get(trayShape[dragSlot]);
            for (int r = 0; r < s.h; r++)
                for (int col = 0; col < s.w; col++)
                    if (s.cell[r][col])
                        drawBmp(c, blockBmp[dragColor], boardL + (ghostC + col) * cell,
                                boardT + (ghostR + r) * cell, cell, 1f, previewLines != 0 ? 200 : 110);
        }
        return anim;
    }

    private void drawBmp(Canvas c, Bitmap b, float x, float y, float size, float scale, int alpha) {
        float s = size * scale, off = (size - s) / 2;
        tmp.set(x + off, y + off, x + off + s, y + off + s);
        bmpPaint.setAlpha(alpha);
        c.drawBitmap(b, null, tmp, bmpPaint);
    }

    private boolean drawEffects(Canvas c, long now, float dt) {
        boolean anim = false;
        for (Iterator<FadeCell> it = fading.iterator(); it.hasNext(); ) {
            FadeCell f = it.next();
            float t = (now - f.start) / 300f;
            if (t >= 1f) { it.remove(); continue; }
            anim = true;
            float x = boardL + f.c * cell, y = boardT + f.r * cell;
            if (t < 0) { drawBmp(c, blockBmp[f.color], x, y, cell, 1f, 255); continue; }
            drawBmp(c, blockBmp[f.color], x, y, cell, 1f + 0.25f * t, (int) (255 * (1 - t)));
            fill.setColor(Color.argb((int) (180 * (1 - t)), 255, 255, 255));
            tmp.set(x + cell * 0.05f, y + cell * 0.05f, x + cell * 0.95f, y + cell * 0.95f);
            c.drawRoundRect(tmp, cell * 0.2f, cell * 0.2f, fill);
        }
        for (Iterator<Particle> it = particles.iterator(); it.hasNext(); ) {
            Particle p = it.next();
            if (now < p.start) { anim = true; continue; }
            float t = (now - p.start) / (float) p.life;
            if (t >= 1f) { it.remove(); continue; }
            anim = true;
            p.vy += cell * 14f * dt;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            fill.setColor(p.color);
            fill.setAlpha((int) (255 * (1 - t)));
            float s = p.size * (1 - t * 0.5f);
            tmp.set(p.x - s, p.y - s, p.x + s, p.y + s);
            c.drawRoundRect(tmp, s * 0.4f, s * 0.4f, fill);
        }
        fill.setAlpha(255);
        for (Iterator<FloatText> it = texts.iterator(); it.hasNext(); ) {
            FloatText f = it.next();
            if (now < f.start) { anim = true; continue; }
            float t = (now - f.start) / (float) f.life;
            if (t >= 1f) { it.remove(); continue; }
            anim = true;
            float pop = t < 0.15f ? 0.6f + 0.4f * easeOutBack(t / 0.15f) : 1f;
            float a = t > 0.7f ? 1f - (t - 0.7f) / 0.3f : 1f;
            float y = f.y - cell * 0.9f * t;
            text.setTextSize(f.size * pop);
            stroke.setTextSize(f.size * pop);
            stroke.setStrokeWidth(f.size * 0.16f);
            stroke.setAlpha((int) (200 * a));
            c.drawText(f.text, f.x, y, stroke);
            text.setColor(f.color);
            text.setAlpha((int) (255 * a));
            c.drawText(f.text, f.x, y, text);
        }
        stroke.setAlpha(255);
        return anim;
    }

    private boolean drawTray(Canvas c, long now) {
        boolean anim = false;
        for (int i = 0; i < 3; i++) {
            if (trayShape[i] < 0 || i == dragSlot) continue;
            Shapes.Shape s = Shapes.ALL.get(trayShape[i]);
            float cx = slotCX(i), cy = trayCY, size = trayCell;
            if (i == returnSlot) {
                float t = (now - returnStart) / 170f;
                if (t >= 1f) returnSlot = -1;
                else {
                    float e = easeOut(t);
                    cx = lerp(returnCX, cx, e);
                    cy = lerp(returnCY, cy, e);
                    size = lerp(returnSize, trayCell, e);
                    anim = true;
                }
            }
            float scale = 1f;
            if (now < traySpawn[i] + 280) {
                float t = Math.max(0f, (now - traySpawn[i]) / 280f);
                scale = easeOutBack(t);
                anim = true;
            }
            int alpha = trayFits[i] ? 255 : 90;
            if (gameOver) alpha = 90;
            drawPiece(c, s, trayColor[i], cx, cy, size * scale, alpha);
        }
        if (dragSlot >= 0) {
            Shapes.Shape s = Shapes.ALL.get(trayShape[dragSlot]);
            float t = dragT(now);
            if (t < 1f) anim = true;
            float size = lerp(trayCell, cell, easeOut(t));
            float cy = fingerY - cell * t - s.h * size / 2;
            drawPiece(c, s, trayColor[dragSlot], fingerX, cy, size, 255);
        }
        return anim;
    }

    private void drawPiece(Canvas c, Shapes.Shape s, int color, float cx, float cy, float size, int alpha) {
        if (size <= 0) return;
        float x0 = cx - s.w * size / 2, y0 = cy - s.h * size / 2;
        for (int r = 0; r < s.h; r++)
            for (int col = 0; col < s.w; col++)
                if (s.cell[r][col]) drawBmp(c, blockBmp[color], x0 + col * size, y0 + r * size, size, 1f, alpha);
    }

    private float overlayAlpha(long now) {
        long start = gameOverTime + N * 55L + 250;
        return Math.max(0f, Math.min(1f, (now - start) / 300f));
    }

    private void drawGameOver(Canvas c, long now) {
        float a = overlayAlpha(now);
        if (a <= 0) return;
        fill.setColor(Color.argb((int) (170 * a), 8, 10, 24));
        c.drawRect(0, 0, W, H, fill);

        float cw = W * 0.78f, ch = cell * 5.2f;
        float lift = (1 - easeOut(a)) * cell;
        float l = (W - cw) / 2, t = boardT + boardS / 2 - ch / 2 + lift;
        fill.setColor(Color.argb((int) (255 * a), 0x2A, 0x2F, 0x5E));
        tmp.set(l, t, l + cw, t + ch);
        c.drawRoundRect(tmp, cell * 0.4f, cell * 0.4f, fill);

        int ia = (int) (255 * a);
        text.setColor(0xFFFF8E8E);
        text.setAlpha(ia);
        text.setTextSize(cell * 0.5f);
        c.drawText("Out of space!", W / 2, t + cell * 0.95f, text);
        text.setColor(0xFFB8BEE8);
        text.setAlpha(ia);
        text.setTextSize(cell * 0.32f);
        c.drawText("SCORE", W / 2, t + cell * 1.65f, text);
        text.setColor(Color.WHITE);
        text.setAlpha(ia);
        text.setTextSize(cell * 0.95f);
        c.drawText(String.valueOf(score), W / 2, t + cell * 2.6f, text);
        text.setTextSize(cell * 0.36f);
        text.setColor(0xFFFFD166);
        text.setAlpha(ia);
        c.drawText(newBest ? "👑 New best score!" : "👑 Best " + best, W / 2, t + cell * 3.25f, text);

        float bw = cw * 0.6f, bh = cell * 0.95f;
        againBtn.set(W / 2 - bw / 2, t + ch - bh - cell * 0.45f, W / 2 + bw / 2, t + ch - cell * 0.45f);
        fill.setShader(new LinearGradient(0, againBtn.top, 0, againBtn.bottom, 0xFF5FE3A1, 0xFF22A86B, Shader.TileMode.CLAMP));
        fill.setAlpha(ia);
        c.drawRoundRect(againBtn, bh / 2, bh / 2, fill);
        fill.setShader(null);
        text.setColor(Color.WHITE);
        text.setAlpha(ia);
        text.setTextSize(cell * 0.42f);
        c.drawText("Play Again", W / 2, againBtn.centerY() + cell * 0.15f, text);
    }

    // ---- easing ----
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private static float easeOut(float t) { return 1f - (1f - t) * (1f - t); }

    private static float easeOutBack(float t) {
        float c1 = 1.70158f, c3 = c1 + 1;
        float u = t - 1;
        return 1 + c3 * u * u * u + c1 * u * u;
    }
}
