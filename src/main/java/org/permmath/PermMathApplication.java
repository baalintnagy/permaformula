package org.permmath;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@EnableConfigurationProperties
@SpringBootApplication
public class PermMathApplication {

    public static void main(String[] args) {
        SpringApplication.run(PermMathApplication.class, args);
    }

}
