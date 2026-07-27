package com.luky.nexusmind.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class KnowledgeGraphSchemaInitializer implements ApplicationRunner {
    private static final Logger logger = LoggerFactory.getLogger(KnowledgeGraphSchemaInitializer.class);
    private static final Set<String> UNDERSIZED_TEXT_TYPES = Set.of("char", "varchar", "tinytext");

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeGraphSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> columnTypes = jdbcTemplate.queryForList("""
                SELECT DATA_TYPE
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'graph_candidates'
                  AND COLUMN_NAME = 'evidence_text'
                """, String.class);
        if (columnTypes.isEmpty() || !requiresEvidenceTextUpgrade(columnTypes.get(0))) return;

        logger.info("升级 graph_candidates.evidence_text 列类型为 TEXT");
        jdbcTemplate.execute("ALTER TABLE graph_candidates MODIFY COLUMN evidence_text TEXT NOT NULL");
    }

    static boolean requiresEvidenceTextUpgrade(String dataType) {
        return dataType != null && UNDERSIZED_TEXT_TYPES.contains(dataType.toLowerCase(Locale.ROOT));
    }
}
