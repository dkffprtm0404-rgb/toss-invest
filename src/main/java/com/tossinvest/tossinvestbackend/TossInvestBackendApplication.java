package com.tossinvest.tossinvestbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TossInvestBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(TossInvestBackendApplication.class, args);
    }

}
