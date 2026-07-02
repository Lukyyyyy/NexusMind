package com.luky.nexusmind.service;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridSearchPermissionQueryTest {

    @Test
    void ordinaryUserQueryKeepsOwnerAccessAndExcludesPrivateTagsFromSharedBranches() {
        HybridSearchService service = new HybridSearchService();

        Query query = service.buildPermissionQuery("7", List.of("engineering", "PRIVATE_alice"), false);

        assertTrue(query.isBool());
        assertEquals(3, query.bool().should().size());
        assertEquals("7", query.bool().should().get(0).term().value().stringValue());
        assertTrue(query.bool().should().get(1).bool().mustNot().get(0).isPrefix());
        assertEquals(DocumentPermissionPolicy.PRIVATE_TAG_PREFIX,
                query.bool().should().get(1).bool().mustNot().get(0).prefix().value());
        assertTrue(query.bool().should().get(2).bool().mustNot().get(0).isPrefix());
    }

    @Test
    void administratorQueryIsUnrestricted() {
        HybridSearchService service = new HybridSearchService();

        Query query = service.buildPermissionQuery("1", List.of(), true);

        assertTrue(query.isMatchAll());
    }

    @Test
    void anonymousQueryAllowsOnlyPublicNonPrivateDocuments() {
        HybridSearchService service = new HybridSearchService();

        Query query = service.buildPublicPermissionQuery();

        assertTrue(query.isBool());
        assertEquals(true, query.bool().must().get(0).term().value().booleanValue());
        assertEquals(DocumentPermissionPolicy.PRIVATE_TAG_PREFIX,
                query.bool().mustNot().get(0).prefix().value());
    }
}
