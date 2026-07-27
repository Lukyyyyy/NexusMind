package com.luky.nexusmind;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.neo4j.Neo4jAutoConfiguration;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NexusMindApplicationTest {

    @Test
    void disablesSpringBootAnonymousNeo4jDriver() {
        SpringBootApplication annotation = NexusMindApplication.class.getAnnotation(SpringBootApplication.class);

        assertTrue(Arrays.asList(annotation.exclude()).contains(Neo4jAutoConfiguration.class));
    }
}
