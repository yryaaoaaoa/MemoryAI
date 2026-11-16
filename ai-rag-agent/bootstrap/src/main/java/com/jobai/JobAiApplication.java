package com.jobai;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

// RAG owns its clients explicitly; Boot must not create fallback ES clients when RAG is disabled.
@SpringBootApplication(exclude = {
        org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration.class,
        org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration.class
})
@EnableAsync
@MapperScan("com.jobai.**.mapper")
public class JobAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobAiApplication.class, args);
    }

}
