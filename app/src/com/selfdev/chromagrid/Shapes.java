package com.selfdev.chromagrid;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** All piece shapes. Order is deterministic so indices can be persisted. */
final class Shapes {
    static final class Shape {
        final int w, h, count, weight;
        final boolean[][] cell; // [row][col]
        final long mask;        // bitboard at origin, bit = row * 8 + col

        Shape(boolean[][] cell, int weight) {
            this.cell = cell;
            this.h = cell.length;
            this.w = cell[0].length;
            this.weight = weight;
            long m = 0;
            int n = 0;
            for (int r = 0; r < h; r++)
                for (int c = 0; c < w; c++)
                    if (cell[r][c]) { m |= 1L << (r * 8 + c); n++; }
            this.mask = m;
            this.count = n;
        }
    }

    static final List<Shape> ALL = new ArrayList<>();
    private static int totalWeight;

    static {
        add("X", 2, false, false);
        add("XX", 3, true, false);
        add("XXX", 3, true, false);
        add("XXXX", 2, true, false);
        add("XXXXX", 1, true, false);
        add("XX|XX", 4, false, false);
        add("XXX|XXX|XXX", 1, false, false);
        add("XXX|XXX", 1, true, false);
        add("XX|X.", 2, true, false);
        add("X..|XXX", 1, true, true);
        add("XXX|.X.", 1, true, false);
        add("XX.|.XX", 1, true, true);
        add("X..|X..|XXX", 1, true, false);
        add("X.|.X", 1, true, false);
        for (Shape s : ALL) totalWeight += s.weight;
    }

    private static void add(String pattern, int weight, boolean rotate, boolean mirror) {
        String[] rows = pattern.split("\\|");
        boolean[][] base = new boolean[rows.length][rows[0].length()];
        for (int r = 0; r < rows.length; r++)
            for (int c = 0; c < rows[r].length(); c++)
                base[r][c] = rows[r].charAt(c) == 'X';
        List<boolean[][]> variants = new ArrayList<>();
        boolean[][] cur = base;
        for (int i = 0; i < (rotate ? 4 : 1); i++) {
            variants.add(cur);
            if (mirror) variants.add(mirror(cur));
            cur = rotate(cur);
        }
        for (boolean[][] v : variants) {
            Shape s = new Shape(v, weight);
            boolean dup = false;
            for (Shape o : ALL) if (o.mask == s.mask && o.w == s.w && o.h == s.h) { dup = true; break; }
            if (!dup) ALL.add(s);
        }
    }

    private static boolean[][] rotate(boolean[][] a) {
        int h = a.length, w = a[0].length;
        boolean[][] b = new boolean[w][h];
        for (int r = 0; r < h; r++)
            for (int c = 0; c < w; c++)
                b[c][h - 1 - r] = a[r][c];
        return b;
    }

    private static boolean[][] mirror(boolean[][] a) {
        int h = a.length, w = a[0].length;
        boolean[][] b = new boolean[h][w];
        for (int r = 0; r < h; r++)
            for (int c = 0; c < w; c++)
                b[r][w - 1 - c] = a[r][c];
        return b;
    }

    static int randomIndex(Random rng) {
        int x = rng.nextInt(totalWeight);
        for (int i = 0; i < ALL.size(); i++) {
            x -= ALL.get(i).weight;
            if (x < 0) return i;
        }
        return 0;
    }

    // ---- bitboard helpers ----

    static final long COL0 = 0x0101010101010101L;

    static boolean fitsAt(long occ, Shape s, int r, int c) {
        if (r < 0 || c < 0 || r + s.h > 8 || c + s.w > 8) return false;
        return (occ & (s.mask << (r * 8 + c))) == 0;
    }

    static boolean fitsAnywhere(long occ, Shape s) {
        for (int r = 0; r + s.h <= 8; r++)
            for (int c = 0; c + s.w <= 8; c++)
                if ((occ & (s.mask << (r * 8 + c))) == 0) return true;
        return false;
    }

    /** Returns mask of all cells in full rows/cols of occ. */
    static long fullLines(long occ) {
        long out = 0;
        for (int i = 0; i < 8; i++) {
            long row = 0xFFL << (i * 8);
            if ((occ & row) == row) out |= row;
            long col = COL0 << i;
            if ((occ & col) == col) out |= col;
        }
        return out;
    }

    private static int budget;

    /** True if all given shapes can be placed one after another in some order. */
    static boolean solvable(long occ, int[] shapes) {
        budget = 60000;
        return solve(occ, shapes, 0);
    }

    private static boolean solve(long occ, int[] shapes, int used) {
        if (used == (1 << shapes.length) - 1) return true;
        for (int i = 0; i < shapes.length; i++) {
            if ((used & (1 << i)) != 0) continue;
            Shape s = ALL.get(shapes[i]);
            for (int r = 0; r + s.h <= 8; r++)
                for (int c = 0; c + s.w <= 8; c++) {
                    if (--budget < 0) return false;
                    long m = s.mask << (r * 8 + c);
                    if ((occ & m) != 0) continue;
                    long o = occ | m;
                    o &= ~fullLines(o);
                    if (solve(o, shapes, used | (1 << i))) return true;
                }
        }
        return false;
    }
}
