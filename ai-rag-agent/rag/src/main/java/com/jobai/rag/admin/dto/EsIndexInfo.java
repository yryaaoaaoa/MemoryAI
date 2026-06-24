package com.jobai.rag.admin.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class EsIndexInfo {
    String name;
    String health;
    long docCount;
    long storageSizeBytes;
    int numOfShards;
    int numOfReplicas;
}
