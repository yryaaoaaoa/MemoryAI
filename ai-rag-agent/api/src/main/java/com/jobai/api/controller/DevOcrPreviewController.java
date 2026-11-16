package com.jobai.api.controller;

import com.jobai.common.BusinessException;
import com.jobai.common.ErrorCode;
import com.jobai.common.R;
import com.jobai.knowledge.parser.DocumentParser;
import com.jobai.knowledge.parser.MdPostProcessor;
import dev.langchain4j.data.document.Document;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Development preview only. Images must be uploaded alongside the document, never read by path or URL. */
@Profile("dev")
@RestController
@RequiredArgsConstructor
public class DevOcrPreviewController {
    private final DocumentParser documentParser;
    private final MdPostProcessor mdPostProcessor;

    @Operation(summary = "开发环境OCR预览，images文件名须与Markdown图片引用完全一致")
    @PostMapping("/api/test/ocr-preview")
    public R<Map<String, Object>> preview(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "images", required = false) List<MultipartFile> images) throws IOException {
        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件名不能为空");
        }
        boolean markdown = fileName.toLowerCase(Locale.ROOT).endsWith(".md")
                || fileName.toLowerCase(Locale.ROOT).endsWith(".markdown");
        String rawText;
        if (markdown) {
            rawText = new String(file.getBytes(), StandardCharsets.UTF_8);
        } else {
            try (var input = new ByteArrayInputStream(file.getBytes())) {
                rawText = documentParser.parse(input, fileName).stream()
                        .map(Document::text).collect(Collectors.joining("\n"));
            }
        }
        Map<String, byte[]> attachments = new HashMap<>();
        if (images != null) {
            for (MultipartFile image : images) {
                String name = image.getOriginalFilename();
                if (name == null || name.isBlank() || attachments.containsKey(name)) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "图片文件名不能为空或重复");
                }
                attachments.put(name, image.getBytes());
            }
        }
        List<Document> sections = markdown ? mdPostProcessor.process(rawText, attachments)
                : List.of(Document.from(rawText));
        Map<String, Object> result = new HashMap<>();
        result.put("fileName", fileName);
        result.put("totalChars", rawText.length());
        result.put("sectionCount", sections.size());
        result.put("sections", sections.stream().map(section -> {
            Map<String, Object> value = new HashMap<>();
            value.put("headingPath", section.metadata().getString("heading_path"));
            value.put("text", section.text());
            value.put("textLength", section.text().length());
            return value;
        }).toList());
        return R.ok(result);
    }
}
