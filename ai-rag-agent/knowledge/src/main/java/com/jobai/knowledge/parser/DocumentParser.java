package com.jobai.knowledge.parser;

import dev.langchain4j.data.document.Document;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public interface DocumentParser {

    List<Document> parse(InputStream inputStream, String fileName) throws IOException;
}
