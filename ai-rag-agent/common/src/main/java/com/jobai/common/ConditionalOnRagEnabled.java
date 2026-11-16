package com.jobai.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.lang.annotation.*;

/** RAG infrastructure is opt-in; core chat and learning features do not require it. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(prefix = "jobai.rag", name = "enabled", havingValue = "true")
public @interface ConditionalOnRagEnabled {}
