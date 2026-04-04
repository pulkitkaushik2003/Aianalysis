package com.example.aianalysis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class AianalysisApplication {

	public static void main(String[] args) {
		SpringApplication.run(AianalysisApplication.class, args);
	}

}

