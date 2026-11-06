package com.chronos.navigator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.GraphJson;
import com.chronos.navigator.model.Paths;

import org.junit.Test;

import java.util.List;

/** Main paths with their branches: named in the file, or read from the graph's structure. */
public class PathsTest {
    private static final String NODES =
        "\"nodes\":[" +
        "{\"id\":\"A\",\"type\":\"TASK\",\"title\":\"First\",\"effort\":{\"total_h_p50\":5,\"total_h_p90\":8}}," +
        "{\"id\":\"B1\",\"type\":\"TASK\",\"title\":\"Way one\",\"depends_on\":[\"A\"],\"effort\":{\"total_h_p50\":5,\"total_h_p90\":8}}," +
        "{\"id\":\"B2\",\"type\":\"TASK\",\"title\":\"Way two\",\"effort\":{\"total_h_p50\":5,\"total_h_p90\":8}}," +
        "{\"id\":\"GOAL\",\"type\":\"MILESTONE\",\"title\":\"The goal\",\"any_of\":[[\"B1\"],[\"B2\"]]}," +
        "{\"id\":\"LONE\",\"type\":\"TASK\",\"title\":\"On no path\",\"effort\":{\"total_h_p50\":5,\"total_h_p90\":8}}]";

    private static Graph graph(String extra) {
        return GraphJson.read("{\"schema_version\":\"0.2\",\"meta\":{\"title\":\"t\"}," + extra + NODES + "}").graph();
    }

    @Test
    public void aGraphWithoutNamedPathsGetsThemFromItsStructure() {
        List<Paths.Main> paths = Paths.of(graph(""));
        Paths.Main main = paths.stream().filter(p -> "GOAL".equals(p.goal())).findFirst().orElseThrow();
        assertEquals("each way in is a branch", 2, main.branches().size());
        assertTrue("a branch holds what its way needs", main.all().containsAll(List.of("B1", "A", "B2")));
    }

    @Test
    public void namedPathsAreUsedAndUnknownNodesSkipped() {
        Graph g = graph("\"paths\":[{\"id\":\"P\",\"title\":\"Mine\",\"goal\":\"GOAL\",\"stem\":[\"A\",\"GONE\"]," +
            "\"branches\":[{\"id\":\"X\",\"title\":\"One\",\"nodes\":[\"B1\"]},{\"id\":\"Y\",\"title\":\"Two\",\"nodes\":[\"B2\"]}]}],");
        List<Paths.Main> paths = Paths.of(g);
        assertEquals("Mine", paths.get(0).title());
        assertFalse(paths.get(0).stem().contains("GONE"));
        assertEquals(2, paths.get(0).branches().size());
    }

    @Test
    public void nothingIsHidden() {
        Graph g = graph("\"paths\":[{\"id\":\"P\",\"title\":\"Mine\",\"goal\":\"GOAL\",\"stem\":[\"A\"],\"branches\":[]}],");
        Paths.Main last = Paths.of(g).get(Paths.of(g).size() - 1);
        assertEquals(Paths.OTHER, last.id());
        assertTrue(last.stem().containsAll(List.of("LONE", "B1", "B2")));
    }
}
