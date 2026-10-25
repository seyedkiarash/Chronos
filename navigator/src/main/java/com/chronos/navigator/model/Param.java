package com.chronos.navigator.model;

import com.chronos.navigator.model.Kinds.Dist;
import com.chronos.navigator.model.Kinds.Source;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One number, or one distribution, the layers read — always with where it
 * came from. Values are named ("a", "b"; "median", "sigma"; "mean", "tau0")
 * so a stored row reads on its own in a database browser.
 */
public class Param {
    public String key;
    public Dist dist;
    public Map<String, Double> values = new LinkedHashMap<>();
    public Source source;
    /** What the number is, or what it was learned from. */
    public String note;
    public long updatedAt;

    public Param() {}

    public Param(String key, Dist dist, Source source, String note, Object... nameValuePairs) {
        this.key = key;
        this.dist = dist;
        this.source = source;
        this.note = note;
        for (int i = 0; i + 1 < nameValuePairs.length; i += 2)
            values.put((String) nameValuePairs[i], ((Number) nameValuePairs[i + 1]).doubleValue());
    }

    public Double get(String name) { return values.get(name); }

    public Param copy() {
        Param p = new Param();
        p.key = key; p.dist = dist; p.values = new LinkedHashMap<>(values);
        p.source = source; p.note = note; p.updatedAt = updatedAt;
        return p;
    }
}
