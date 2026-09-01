package com.guardline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GuardlineApplication {

    public static void main(String[] args) {
        SpringApplication.run(GuardlineApplication.class, args);
    }
}
