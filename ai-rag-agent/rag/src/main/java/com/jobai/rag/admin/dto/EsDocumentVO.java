package com.jobai.rag.admin.dto;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder
public class EsDocumentVO {
    String id;
    Map<String, Object> source;
    float score;
}
