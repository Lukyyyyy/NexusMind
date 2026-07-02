package com.luky.nexusmind.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentPermissionPolicyTest {

    @Test
    void recognizesPrivateOrganizationTags() {
        assertTrue(DocumentPermissionPolicy.isPrivateOrgTag("PRIVATE_alice"));
        assertFalse(DocumentPermissionPolicy.isPrivateOrgTag("engineering"));
        assertFalse(DocumentPermissionPolicy.isPrivateOrgTag(null));
    }

    @Test
    void privateSpaceUploadsAreNeverPublic() {
        assertFalse(DocumentPermissionPolicy.resolveUploadVisibility("PRIVATE_alice", true));
        assertTrue(DocumentPermissionPolicy.resolveUploadVisibility("engineering", true));
    }

    @Test
    void privateDocumentsAreLimitedToOwnerAndAdmin() {
        assertTrue(DocumentPermissionPolicy.canAccessPrivateDocument("alice", "alice", "USER"));
        assertTrue(DocumentPermissionPolicy.canAccessPrivateDocument("alice", "root", "ADMIN"));
        assertFalse(DocumentPermissionPolicy.canAccessPrivateDocument("alice", "bob", "USER"));
    }
}
