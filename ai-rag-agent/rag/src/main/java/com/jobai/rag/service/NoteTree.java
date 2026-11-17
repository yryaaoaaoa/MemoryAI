package com.jobai.rag.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Parsed once at the write boundary; content is derived from this same tree. */
record NoteTree(int version, List<Node> nodes) {
    private static final ObjectMapper JSON = new ObjectMapper();
    record Node(String id, String title, String body, List<Node> children,
                @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
                List<Long> sourceMessageIds) {
        Node(String id, String title, String body, List<Node> children) {
            this(id, title, body, children, null);
        }
    }

    static NoteTree parse(String value) {
        if (value.length() > 300000) throw new IllegalArgumentException("知识树数据过大，请减少节点或正文");
        JsonNode root;
        try { root = JSON.readTree(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("知识树格式无效"); }
        if (root == null || !root.isObject() || !root.path("version").isIntegralNumber() || !root.path("version").canConvertToInt()
                || root.path("version").asInt() != 1 || !root.path("nodes").isArray() || root.path("nodes").isEmpty())
            throw new IllegalArgumentException("知识树格式无效，至少需要一个知识点");
        return new NoteTree(1, readNodes(root.get("nodes"), 1, new HashSet<>()));
    }

    private static List<Node> readNodes(JsonNode entries, int depth, Set<String> ids) {
        if (depth > 32 && !entries.isEmpty()) throw new IllegalArgumentException("知识树最多支持 32 层");
        List<Node> result = new ArrayList<>();
        for (JsonNode item : entries) {
            if (!item.isObject() || !item.path("id").isTextual() || !item.path("title").isTextual()
                    || !item.path("body").isTextual() || !item.path("children").isArray())
                throw new IllegalArgumentException("知识点必须包含编号、标题、正文和子节点列表");
            String id = item.get("id").textValue(), title = item.get("title").textValue(), body = item.get("body").textValue();
            if (id.isBlank() || id.length() > 128 || !ids.add(id))
                throw new IllegalArgumentException("知识点编号无效或重复");
            if (title.isBlank() || title.length() > 120 || title.contains("\n") || title.contains("\r"))
                throw new IllegalArgumentException("知识点标题不能为空，最多 120 字且不能换行");
            if (ids.size() > 1000) throw new IllegalArgumentException("一篇笔记最多支持 1000 个知识点");
            if (body.length() > 40000) throw new IllegalArgumentException("笔记正文不能超过 40000 字");
            List<Long> sourceIds = null;
            if (item.hasNonNull("sourceMessageIds")) {
                JsonNode sources = item.get("sourceMessageIds");
                if (!sources.isArray()) throw new IllegalArgumentException("原问答编号必须为数组");
                sourceIds = new ArrayList<>();
                Set<Long> seen = new HashSet<>();
                for (JsonNode source : sources) {
                    if (!source.isIntegralNumber() || !source.canConvertToLong() || source.asLong() <= 0 || !seen.add(source.asLong()))
                        throw new IllegalArgumentException("原问答编号无效或重复");
                    sourceIds.add(source.asLong());
                }
            }
            result.add(new Node(id, title, body, readNodes(item.get("children"), depth + 1, ids), sourceIds));
        }
        return result;
    }

    String markdown() {
        StringBuilder body = new StringBuilder();
        render(nodes, 2, body);
        return body.toString().stripTrailing() + "\n";
    }

    static List<Map<String, Object>> outline(List<Node> nodes) {
        return nodes.stream().map(node -> Map.<String, Object>of("id", node.id(), "title", node.title(),
                "children", outline(node.children()))).toList();
    }

    private static void render(List<Node> nodes, int depth, StringBuilder body) {
        for (Node node : nodes) {
            body.append(depth <= 6 ? "#".repeat(depth) + " " + node.title() : "**" + node.title() + "**").append("\n\n");
            if (!node.body().isEmpty()) body.append(node.body()).append("\n\n");
            render(node.children(), depth + 1, body);
        }
    }
}
