package com.jobai.rag.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NoteTreeTest {
    private String branch(int depth) {
        return "{\"id\":\"n" + depth + "\",\"title\":\"标题\",\"body\":\"正文\",\"children\":["
                + (depth > 1 ? branch(depth - 1) : "") + "]}";
    }

    @Test void deepBranchesKeepContentAndHaveAnExplicitDepthLimit() {
        String tree = "{\"version\":1,\"nodes\":[" + branch(32) + "]}";
        String markdown = NoteTree.parse(tree).markdown();
        assertTrue(markdown.contains("###### 标题"));
        assertTrue(markdown.contains("**标题**"));
        assertEquals(32,markdown.split("正文",-1).length-1);
        assertThrows(IllegalArgumentException.class,()->NoteTree.parse("{\"version\":1,\"nodes\":[" + branch(33) + "]}"));
    }

    @Test void totalNodeLimitIsAcrossBranches() {
        String nodes = java.util.stream.IntStream.range(0,1001).mapToObj(i ->
                "{\"id\":\""+i+"\",\"title\":\"知识点\",\"body\":\"\",\"children\":[]}").collect(java.util.stream.Collectors.joining(","));
        assertThrows(IllegalArgumentException.class,()->NoteTree.parse("{\"version\":1,\"nodes\":["+nodes+"]}"));
    }
}
