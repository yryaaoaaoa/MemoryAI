package com.jobai.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("async-task-");
        // 拒绝策略：由调用线程执行（不会丢任务，只是阻塞提交方）
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean(name = "streamingExecutor")
    public Executor streamingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("stream-");
        executor.initialize();
        return executor;
    }

    /**
     * 专用解析线程池——与业务线程池隔离，即使解析挂了也不影响其他功能。
     * 单线程+无界队列，解析任务依次排队。
     */
    @Bean(name = "parseExecutor")
    public Executor parseExecutor() {
        // 直接用 ScheduledThreadPool，方便 Future 超时控制
        return Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "doc-parse-");
            t.setDaemon(true);
            return t;
        });
    }
}
