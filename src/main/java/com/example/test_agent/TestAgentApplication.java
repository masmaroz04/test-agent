package com.example.test_agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableConfigurationProperties
@EnableCaching  // เปิดใช้งาน Spring Cache — ทำให้ @Cacheable ทำงานได้
public class TestAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestAgentApplication.class, args);
    }

}
