package com.sgr.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SgrGenAIApplication {

    public static void main(String[] args) {
        System.setProperty(
                "langchain4j.http-client.factory",
                "dev.langchain4j.http.client.spring.restclient.SpringRestClientBuilderFactory");
        SpringApplication.run(SgrGenAIApplication.class, args);
    }
}
