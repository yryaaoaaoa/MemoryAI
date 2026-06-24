package com.jobai.api.controller;

import com.jobai.common.JobAiProperties.RetrievalProperties;
import com.jobai.common.R;
import com.jobai.knowledge.config.RetrievalConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "系统设置")
@RestController
@RequiredArgsConstructor
public class SettingsController {

    private final RetrievalConfigService configService;
    private final RetrievalProperties defaultConfig;

    @Operation(summary = "获取检索配置")
    @GetMapping("/api/settings/retrieval")
    public R<RetrievalProperties> getConfig() {
        RetrievalProperties active = configService.getActiveConfig();
        return R.ok(active != null ? active : defaultConfig);
    }

    @Operation(summary = "保存检索配置")
    @PutMapping("/api/settings/retrieval")
    public R<Void> saveConfig(@RequestBody RetrievalProperties config) {
        configService.saveConfig(config);
        return R.ok();
    }

    @Operation(summary = "重置检索配置为默认值")
    @DeleteMapping("/api/settings/retrieval")
    public R<Void> resetConfig() {
        configService.resetConfig();
        return R.ok();
    }
}
