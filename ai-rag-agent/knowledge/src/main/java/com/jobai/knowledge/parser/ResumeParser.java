package com.jobai.knowledge.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobai.knowledge.llm.LlmService;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Standalone resume parser — not part of the document pipeline.
 * Extracts text via PDFBox, then uses LLM to produce structured JSON.
 */
@Slf4j
@Component
public class ResumeParser {

    private static final String RESUME_JSON_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "sections": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "type": {
                        "type": "string",
                        "enum": ["skills", "experience", "projects", "education", "certificates", "objective", "self", "raw"]
                      },
                      "content": {"type": "string", "description": "章节原文，保持原样不改写"},
                      "tags": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "技术标签，仅 skills/projects 类型填写，其他传空数组"
                      }
                    },
                    "required": ["type", "content", "tags"]
                  }
                }
              },
              "required": ["sections"]
            }""";

    private static final String SYSTEM_PROMPT = """
            你是一个简历解析助手。将以下简历文本解析为JSON格式。

            要求：
            1. 识别简历中的各个章节，按章节切分
            2. 从专业技能等章节中提取技术标签（tags）
            3. 过滤掉包含个人隐私的章节（姓名、电话、邮箱、地址等）
            4. 每个章节保持原始内容不变，不要改写或总结

            严格按以下 JSON Schema 格式输出，不要 markdown 包裹：
            """ + RESUME_JSON_SCHEMA;

    private final ObjectMapper objectMapper;
    private final LlmService llmService;

    public ResumeParser(ObjectMapper objectMapper, LlmService llmService) {
        this.objectMapper = objectMapper;
        this.llmService = llmService;
    }

    /**
     * Extract raw text from resume file.
     */
    public String extractText(InputStream in, String fileName) throws IOException {
        if (fileName.toLowerCase().endsWith(".pdf")) {
            try (var doc = Loader.loadPDF(in.readAllBytes())) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                return stripper.getText(doc).trim();
            }
        }
        return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
    }

    /**
     * Use LLM to parse resume text into structured JSON.
     * Returns JSON string ready to store in resume.structured_json.
     */
    public String parseStructured(String rawText) {
        if (!llmService.isAvailable()) {
            log.warn("LLM not available, skipping structured parsing");
            return fallback(rawText);
        }

        try {
            String json = llmService.chat(SYSTEM_PROMPT, rawText);
            return cleanJsonResponse(json);
        } catch (Exception e) {
            log.error("LLM parsing failed: {}", e.getMessage());
            return fallback(rawText);
        }
    }

    private String cleanJsonResponse(String json) {
        String clean = json.trim();
        if (clean.startsWith("```")) {
            int start = clean.indexOf('\n') + 1;
            int end = clean.lastIndexOf("```");
            if (end > start) {
                clean = clean.substring(start, end).trim();
            }
        }
        // Validate it's parseable
        try {
            objectMapper.readTree(clean);
        } catch (Exception e) {
            log.warn("LLM returned invalid JSON, using fallback");
            return null;
        }
        return clean;
    }

    /**
     * Fallback when LLM is unavailable: wrap full text in a single "raw" section.
     */
    private String fallback(String rawText) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ArrayNode sections = root.putArray("sections");
            ObjectNode sec = sections.addObject();
            sec.put("type", "raw");
            sec.put("content", rawText);
            sec.putArray("tags");
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return null;
        }
    }
}
