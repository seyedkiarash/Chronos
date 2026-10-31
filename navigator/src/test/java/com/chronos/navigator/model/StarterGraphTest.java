package com.chronos.navigator.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;
import java.util.List;

/** A new person's first goal graph, made from their own Chronos goals (31 Oct 2026). */
public class StarterGraphTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 31);

    @Test
    public void goalsBecomeAGraphTheImportAccepts() {
        List<StarterGraph.Work> work = List.of(
            new StarterGraph.Work("Thesis", "Read papers", "reading", true, TODAY.plusDays(40), 20.0, 3.0, "papers", 1.5, null, 3.0, true),
            new StarterGraph.Work("Thesis", "Write chapter", "writing", true, TODAY.plusDays(90), 5.0, 0.0, "sections", 6.0, null, 3.0, false),
            new StarterGraph.Work("Language", "Practice", "practice", false, null, null, null, null, 1.0, 4.0, 1.0, false));
        String json = StarterGraph.json(work, TODAY, "UTC");
        GraphImport.Result r = GraphImport.read(json, 0);
        assertTrue(r.invalid().toString(), r.ok());
        Graph g = r.graph();
        assertEquals("two goals and three pieces of work", 5, g.nodes.size());
        Node read = g.node("G1_W1");
        assertNotNull(read);
        assertEquals(TODAY.plusDays(40), read.deadline);
        assertEquals("the goal is due with its last piece", TODAY.plusDays(90), g.node("G1").deadline);
        assertEquals(Kinds.NodeType.STREAM, g.node("G2_W1").type);
    }

    @Test
    public void nothingToStartFromIsNoGraph() {
        assertNull(StarterGraph.json(List.of(), TODAY, "UTC"));
    }
}
