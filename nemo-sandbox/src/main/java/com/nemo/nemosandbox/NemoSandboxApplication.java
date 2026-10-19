package com.nemo.nemosandbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NemoSandboxApplication {

    public static void main(String[] args) {
        SpringApplication.run(NemoSandboxApplication.class, args);
    }

}
