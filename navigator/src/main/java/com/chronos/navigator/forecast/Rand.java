package com.chronos.navigator.forecast;

import java.util.SplittableRandom;

/** The draws the forecast needs, from a seeded generator so a run can be repeated exactly. */
final class Rand {
    private Rand() {}

    /** z of the 90th percentile of the standard normal. */
    static final double Z90 = 1.2815515655446004;

    /** Lognormal through its median and 90th percentile. */
    static double lognormal(SplittableRandom r, double p50, double p90) {
        if (p50 <= 0) return 0;
        double sigma = p90 > p50 ? Math.log(p90 / p50) / Z90 : 0;
        return p50 * Math.exp(sigma * r.nextGaussian());
    }

    /** Marsaglia–Tsang; shape below 1 by the usual boost. */
    static double gamma(SplittableRandom r, double shape) {
        if (shape < 1) return gamma(r, shape + 1) * Math.pow(r.nextDouble(), 1 / shape);
        double d = shape - 1.0 / 3, c = 1 / Math.sqrt(9 * d);
        while (true) {
            double x = r.nextGaussian(), v = 1 + c * x;
            if (v <= 0) continue;
            v = v * v * v;
            double u = r.nextDouble();
            if (u < 1 - 0.0331 * x * x * x * x) return d * v;
            if (Math.log(u) < 0.5 * x * x + d * (1 - v + Math.log(v))) return d * v;
        }
    }

    static double beta(SplittableRandom r, double a, double b) {
        double x = gamma(r, a), y = gamma(r, b);
        return x / (x + y);
    }

    static double[] dirichlet(SplittableRandom r, double[] alpha) {
        double[] out = new double[alpha.length];
        double s = 0;
        for (int i = 0; i < alpha.length; i++) { out[i] = gamma(r, alpha[i]); s += out[i]; }
        for (int i = 0; i < alpha.length; i++) out[i] /= s;
        return out;
    }

    static int categorical(SplittableRandom r, double[] p) {
        double u = r.nextDouble(), acc = 0;
        for (int i = 0; i < p.length; i++) { acc += p[i]; if (u < acc) return i; }
        return p.length - 1;
    }
}
