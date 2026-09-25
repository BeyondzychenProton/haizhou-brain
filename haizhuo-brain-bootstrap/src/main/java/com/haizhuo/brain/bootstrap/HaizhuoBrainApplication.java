package com.haizhuo.brain.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.haizhuo.brain")
public class HaizhuoBrainApplication {
    public static void main(String[] args) {
        SpringApplication.run(HaizhuoBrainApplication.class, args);
    }
}
