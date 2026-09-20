package com.agentdesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import io.minio.MinioClient;

@SpringBootApplication
@EnableScheduling
public class AgentDeskApplication {
  public static void main(String[] args) { SpringApplication.run(AgentDeskApplication.class, args); }

  @Bean
  RestClient agentRestClient(org.springframework.core.env.Environment env) {
    org.springframework.web.client.RestClient.Builder builder=RestClient.builder().baseUrl(env.getProperty("agent.base-url", "http://localhost:8000"));
    String token=env.getProperty("agent.service-token",""); if(!token.isBlank()) builder.defaultHeader("X-Agent-Token",token);
    return builder.build();
  }

  @Bean
  MinioClient minioClient(org.springframework.core.env.Environment env) {
    return MinioClient.builder().endpoint(env.getProperty("minio.endpoint", "http://localhost:9000"))
        .credentials(env.getProperty("minio.access-key", "agentdesk"), env.getProperty("minio.secret-key", "agentdesk123")).build();
  }
}
