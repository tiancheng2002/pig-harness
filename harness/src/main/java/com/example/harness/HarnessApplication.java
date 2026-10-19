package com.example.harness;

import com.example.harness.config.HarnessProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(HarnessProperties.class)
public class HarnessApplication {

    public static void main(String[] args) {
        SpringApplication.run(HarnessApplication.class, args);
    }
}
