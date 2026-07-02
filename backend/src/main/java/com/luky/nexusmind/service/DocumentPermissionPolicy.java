package com.luky.nexusmind.service;

/**
 * Central permission rules for documents stored in user private spaces.
 */
public final class DocumentPermissionPolicy {

    public static final String PRIVATE_TAG_PREFIX = "PRIVATE_";

    private DocumentPermissionPolicy() {
    }

    public static boolean isPrivateOrgTag(String orgTag) {
        return orgTag != null && orgTag.startsWith(PRIVATE_TAG_PREFIX);
    }

    public static boolean resolveUploadVisibility(String orgTag, boolean requestedPublic) {
        return !isPrivateOrgTag(orgTag) && requestedPublic;
    }

    public static boolean canAccessPrivateDocument(String ownerId, String viewerId, String role) {
        return "ADMIN".equals(role) || (ownerId != null && ownerId.equals(viewerId));
    }
}
