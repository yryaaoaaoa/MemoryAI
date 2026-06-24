package com.jobai.agent.tool;

import lombok.extern.slf4j.Slf4j;

/**
 * Unified Agent tool abstraction with retry and fallback.
 */
@Slf4j
public abstract class AgentTool<T, R> {

    private final String name;
    private final String description;
    private final int maxRetries;
    private final long baseDelayMs;

    protected AgentTool(String name, String description) {
        this(name, description, 3, 500);
    }

    protected AgentTool(String name, String description, int maxRetries, long baseDelayMs) {
        this.name = name;
        this.description = description;
        this.maxRetries = maxRetries;
        this.baseDelayMs = baseDelayMs;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }

    /** Core logic — subclasses implement this. */
    protected abstract R execute(T params);

    /** Execute with retry + fallback. */
    public R executeWithRetry(T params) {
        Exception lastEx = null;
        for (int i = 0; i <= maxRetries; i++) {
            try {
                if (i > 0) {
                    long delay = baseDelayMs * (1L << (i - 1)); // 500, 1000, 2000
                    log.warn("[{}] retry {}/{} after {}ms", name, i, maxRetries, delay);
                    Thread.sleep(delay);
                }
                return execute(params);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return fallback(params);
            } catch (Exception e) {
                lastEx = e;
                log.warn("[{}] attempt {}/{} failed: {}", name, i + 1, maxRetries + 1, e.getMessage());
            }
        }
        log.error("[{}] all {} retries exhausted, falling back", name, maxRetries + 1, lastEx);
        return fallback(params);
    }

    /** Fallback when all retries exhausted. */
    protected R fallback(T params) {
        log.warn("[{}] no fallback defined, returning null", name);
        return null;
    }
}
