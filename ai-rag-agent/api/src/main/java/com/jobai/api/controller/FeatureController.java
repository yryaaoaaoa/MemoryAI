package com.jobai.api.controller;

import com.jobai.common.JobAiProperties;
import com.jobai.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class FeatureController {
    private final JobAiProperties properties;

    @GetMapping("/api/features")
    public R<Features> features() {
        return R.ok(new Features(properties.getRag().isEnabled()));
    }

    public record Features(boolean ragEnabled) {}
}
