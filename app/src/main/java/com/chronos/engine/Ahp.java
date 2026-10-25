package com.chronos.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Weights from pairwise comparisons, by the geometric-mean (logarithmic least
 * squares) method. Preferred over the principal eigenvector: closed form,
 * tolerant of missing comparisons, and exactly reciprocal.
 */
public final class Ahp {
    private Ahp() {}

    public record Weights(Map<String, Double> weights, double consistencyRatio,
                          String worstA, String worstB) {}

    private static final double[] RI = {0, 0, 0, 0.58, 0.90, 1.12, 1.24, 1.32, 1.41, 1.45};

    /** @param comparisons key "a|b" meaning an hour of a is worth this many hours of b */
    public static Weights of(List<String> ids, Map<String, Double> comparisons) {
        int n = ids.size();
        Map<String, Double> out = new LinkedHashMap<>();
        if (n == 0) return new Weights(out, 0, null, null);
        if (n == 1) { out.put(ids.get(0), 1.0); return new Weights(out, 0, null, null); }

        double[][] a = new double[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) {
                if (i == j) { a[i][j] = 1; continue; }
                Double fwd = comparisons.get(ids.get(i) + "|" + ids.get(j));
                Double rev = comparisons.get(ids.get(j) + "|" + ids.get(i));
                a[i][j] = fwd != null ? fwd : rev != null ? 1 / rev : 1;
            }

        double[] gm = new double[n];
        double total = 0;
        for (int i = 0; i < n; i++) {
            double prod = 1;
            for (int j = 0; j < n; j++) prod *= a[i][j];
            gm[i] = Math.pow(prod, 1.0 / n);
            total += gm[i];
        }
        double[] w = new double[n];
        for (int i = 0; i < n; i++) { w[i] = gm[i] / total; out.put(ids.get(i), w[i]); }

        double lambdaMax = 0;
        for (int i = 0; i < n; i++) {
            double row = 0;
            for (int j = 0; j < n; j++) row += a[i][j] * w[j];
            lambdaMax += row / w[i];
        }
        lambdaMax /= n;
        double cr = n > 2 ? (lambdaMax - n) / ((n - 1) * RI[Math.min(n, RI.length - 1)]) : 0;

        String wa = null, wb = null;
        double worst = -1;
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++) {
                double err = Math.abs(Math.log(a[i][j] * w[j] / w[i]));
                if (err > worst) { worst = err; wa = ids.get(i); wb = ids.get(j); }
            }
        return new Weights(out, cr, wa, wb);
    }
}
