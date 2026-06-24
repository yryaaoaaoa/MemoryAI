package com.jobai.knowledge.parser;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "jobai.ocr.paddle")
public class JobAiOcrProperties {
    private String serverUrl = "";
    private String accessToken = "";
}
