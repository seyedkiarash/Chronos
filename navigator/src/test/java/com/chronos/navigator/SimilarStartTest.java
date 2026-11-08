package com.chronos.navigator;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.chronos.navigator.estimate.Estimator;
import com.chronos.navigator.estimate.Inputs;
import com.chronos.navigator.estimate.Similarity;
import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.Param;

import org.junit.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** A new activity starts from the ones like it, not only from your overall habit (8 Nov 2026). */
public class SimilarStartTest {

    static final LocalDate TODAY = LocalDate.parse("2026-11-08");

    @Test
    public void namesThatShareWordsAreAlikeAndOthersAreNot() {
        assertTrue(Similarity.score("reading course a", "reading course b", false) > 2.9);
        assertTrue(Similarity.score("reading course a", "gym practice", false) == 0);
        assertTrue(Similarity.score("reading course a", "gym practice", true) == Similarity.SAME_GOAL);
    }

    /** The course: three planned hours a day, two skipped and one that ran to two hours. */
    static Inputs log() {
        Inputs in = new Inputs();
        for (int i = 1; i <= 10; i++) {
            LocalDate d = TODAY.minusDays(i);
            in.sessions.add(new Inputs.Session(d, "reading course a", 60, null, true, true, null, true));
            in.sessions.add(new Inputs.Session(d, "reading course a", 60, null, true, true, null, true));
            in.sessions.add(new Inputs.Session(d, "reading course a", 60, 120, true, false, null, true));
            in.sessions.add(new Inputs.Session(d, "gym practice", 60, 60, true, false, null, true));
        }
        in.nodeActivities.put("N_NEW", List.of("reading course b", "chess openings"));
        return in;
    }

    @Test
    public void aNewCourseOnTheGraphStartsFromTheCourseLikeIt() {
        Graph g = EstimatorTest.ready();
        Map<String, Param> post = EstimatorTest.byKey(Estimator.run(g, log(), TODAY, 0));
        double pooled = post.get("post.adherence").get("mean");
        Param b = post.get("post.adherence.reading course b");
        assertTrue("a start of its own", b != null);
        assertTrue("toward the course worked two thirds: " + b.get("mean") + " vs " + pooled, b.get("mean") < pooled - 0.05);
        assertFalse("nothing like it: the overall habit", post.containsKey("post.adherence.chess openings"));

        Param bias = post.get("post.bias.reading course b");
        Param def = g.params.get("bias.default");
        assertTrue("its speed starts from the course that ran long: " + bias.get("median"),
            bias.get("median") > def.get("median") * 1.1);
        assertFalse(post.containsKey("post.bias.chess openings"));
    }
}
