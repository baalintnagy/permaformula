package org.permmath;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.boava.exception.LocalExceptionHandler;

@EnableConfigurationProperties({PermMathProperties.class, org.permmath.rendering.RenderingProperties.class})
@SpringBootApplication
@Import(LocalExceptionHandler.class)
public class PermMathApplication {

    public static void main(String[] args) {
        SpringApplication.run(PermMathApplication.class, args);
    }

}
