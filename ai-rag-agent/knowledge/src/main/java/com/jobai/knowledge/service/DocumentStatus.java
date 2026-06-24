package com.jobai.knowledge.service;

public interface DocumentStatus {
    String UPLOADING = "UPLOADING";
    String PARSING = "PARSING";
    String CHUNKING = "CHUNKING";
    String EMBEDDING = "EMBEDDING";
    String READY = "READY";
    String FAILED = "FAILED";
}
