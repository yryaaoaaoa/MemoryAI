package com.jobai.knowledge.llm;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class HistoryMessage {
    private String role;
    private String content;
}
