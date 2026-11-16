package com.jobai.api.controller;

import com.jobai.common.BusinessException;
import com.jobai.common.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "jobai.rag", name = "enabled", havingValue = "false", matchIfMissing = true)
public class RagDisabledController {
    @RequestMapping({"/api/knowledge-bases", "/api/knowledge-bases/**", "/api/documents/**",
            "/api/admin/es/**", "/api/settings/retrieval"})
    public void unavailable() {
        throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "知识库检索未启用");
    }
}
