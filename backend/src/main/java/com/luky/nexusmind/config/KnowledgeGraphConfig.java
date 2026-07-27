package com.luky.nexusmind.config;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class KnowledgeGraphConfig {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "knowledge-graph.enabled", havingValue = "true")
    Driver knowledgeGraphDriver(
            @Value("${knowledge-graph.neo4j.uri:bolt://localhost:7687}") String uri,
            @Value("${knowledge-graph.neo4j.username:neo4j}") String username,
            @Value("${knowledge-graph.neo4j.password:}") String password) {
        Config config = Config.builder()
                .withConnectionTimeout(2, TimeUnit.SECONDS)
                .withConnectionAcquisitionTimeout(2, TimeUnit.SECONDS)
                .withMaxTransactionRetryTime(3, TimeUnit.SECONDS)
                .build();
        return GraphDatabase.driver(uri, AuthTokens.basic(username, password), config);
    }
}
