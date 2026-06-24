package com.jobai;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@MapperScan("com.jobai.**.mapper")
public class JobAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobAiApplication.class, args);
    }

}
