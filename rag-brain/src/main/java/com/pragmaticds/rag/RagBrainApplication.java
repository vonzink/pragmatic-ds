package com.pragmaticds.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RagBrainApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagBrainApplication.class, args);
    }
}
