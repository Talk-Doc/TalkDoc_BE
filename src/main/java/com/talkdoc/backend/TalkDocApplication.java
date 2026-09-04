package com.talkdoc.backend;

import com.talkdoc.backend.config.TalkDocProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(TalkDocProperties.class)
public class TalkDocApplication {

    public static void main(String[] args) {
        SpringApplication.run(TalkDocApplication.class, args);
    }
}
