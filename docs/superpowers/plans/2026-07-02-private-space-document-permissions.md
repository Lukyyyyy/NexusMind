# Private Space Document Permissions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure newly uploaded documents are always private from the UI, and documents in `PRIVATE_` spaces are visible only to their owner and administrators.

**Architecture:** Add a small, dependency-free permission policy as the single definition of private-space behavior. Apply it at upload persistence, document listing, Elasticsearch query construction, and servlet resource authorization; keep existing non-private organization behavior unchanged.

**Tech Stack:** Java 17, Spring Boot, Spring Data JPA, Elasticsearch Java client, JUnit 5, Vue 3, TypeScript, Naive UI, pnpm.

---

## File structure

- Create `backend/src/main/java/com/luky/nexusmind/service/DocumentPermissionPolicy.java`: central private-tag and visibility rules.
- Create `backend/src/test/java/com/luky/nexusmind/service/DocumentPermissionPolicyTest.java`: focused policy tests.
- Modify `backend/src/main/java/com/luky/nexusmind/service/UploadService.java`: normalize visibility before persistence.
- Modify `backend/src/main/java/com/luky/nexusmind/service/DocumentService.java`: role-aware list filtering.
- Modify `backend/src/main/java/com/luky/nexusmind/controller/DocumentController.java`: pass authenticated role into the service.
- Modify `backend/src/main/java/com/luky/nexusmind/service/HybridSearchService.java`: make Elasticsearch filters private-space aware and allow administrator access.
- Modify `backend/src/main/java/com/luky/nexusmind/config/OrgTagAuthorizationFilter.java`: make private-space checks take precedence over stale public flags.
- Modify related backend tests to cover owner, administrator, and unrelated-user cases.
- Modify `frontend/src/views/knowledge-base/modules/upload-dialog.vue`: remove public/private controls while retaining a fixed false payload.

### Task 1: Centralize private-space policy

**Files:**
- Create: `backend/src/test/java/com/luky/nexusmind/service/DocumentPermissionPolicyTest.java`
- Create: `backend/src/main/java/com/luky/nexusmind/service/DocumentPermissionPolicy.java`

- [ ] **Step 1: Write failing policy tests**

```java
class DocumentPermissionPolicyTest {
    @Test void recognizesPrivateOrganizationTags() {
        assertTrue(DocumentPermissionPolicy.isPrivateOrgTag("PRIVATE_alice"));
        assertFalse(DocumentPermissionPolicy.isPrivateOrgTag("engineering"));
        assertFalse(DocumentPermissionPolicy.isPrivateOrgTag(null));
    }

    @Test void privateSpaceUploadsAreNeverPublic() {
        assertFalse(DocumentPermissionPolicy.resolveUploadVisibility("PRIVATE_alice", true));
        assertTrue(DocumentPermissionPolicy.resolveUploadVisibility("engineering", true));
    }

    @Test void privateDocumentsAreLimitedToOwnerAndAdmin() {
        assertTrue(DocumentPermissionPolicy.canAccessPrivateDocument("alice", "alice", "USER"));
        assertTrue(DocumentPermissionPolicy.canAccessPrivateDocument("alice", "root", "ADMIN"));
        assertFalse(DocumentPermissionPolicy.canAccessPrivateDocument("alice", "bob", "USER"));
    }
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `cd backend && mvn -Dtest=DocumentPermissionPolicyTest test`

Expected: compilation failure because `DocumentPermissionPolicy` does not exist.

- [ ] **Step 3: Implement the minimal policy**

```java
public final class DocumentPermissionPolicy {
    public static final String PRIVATE_TAG_PREFIX = "PRIVATE_";

    private DocumentPermissionPolicy() {}

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
```

- [ ] **Step 4: Run tests and verify GREEN**

Run: `cd backend && mvn -Dtest=DocumentPermissionPolicyTest test`

Expected: all policy tests pass.

### Task 2: Enforce private uploads on the server

**Files:**
- Modify: `backend/src/main/java/com/luky/nexusmind/service/UploadService.java`
- Test: `backend/src/test/java/com/luky/nexusmind/service/UploadServiceTest.java`

- [ ] **Step 1: Add a failing upload persistence test**

Add a test using the existing mocked repository/storage fixture that calls `uploadChunk(..., "PRIVATE_alice", true, "alice")` and captures the saved `FileUpload`:

```java
assertFalse(savedUpload.isPublic());
assertEquals("PRIVATE_alice", savedUpload.getOrgTag());
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `cd backend && mvn -Dtest=UploadServiceTest test`

Expected: saved upload remains public.

- [ ] **Step 3: Normalize visibility before creating the upload row**

At the start of `uploadChunk`, compute and consistently use:

```java
boolean effectivePublic = DocumentPermissionPolicy.resolveUploadVisibility(orgTag, isPublic);
```

Persist and log `effectivePublic`, not the untrusted request value.

- [ ] **Step 4: Verify upload tests pass**

Run: `cd backend && mvn -Dtest=UploadServiceTest test`

Expected: private-space coercion test and existing upload tests pass.

### Task 3: Enforce owner/admin rules in document listings

**Files:**
- Modify: `backend/src/main/java/com/luky/nexusmind/service/DocumentService.java`
- Modify: `backend/src/main/java/com/luky/nexusmind/controller/DocumentController.java`
- Test: `backend/src/test/java/com/luky/nexusmind/service/DocumentServiceTest.java`
- Test: `backend/src/test/java/com/luky/nexusmind/controller/DocumentControllerTest.java`

- [ ] **Step 1: Write failing list tests**

Cover these results from `getAccessibleFiles(userId, orgTags, role)`:

```java
assertEquals(List.of("own-private.pdf"), ordinaryUserPrivateFileNames);
assertEquals(Set.of("own-private.pdf", "other-private.pdf"), adminPrivateFileNames);
```

The fixture must include an unrelated user's `PRIVATE_` document marked both public and private to prove the tag rule overrides visibility.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `cd backend && mvn -Dtest=DocumentServiceTest,DocumentControllerTest test`

Expected: current service exposes private documents through public or organization-tag conditions and lacks the role parameter.

- [ ] **Step 3: Add role-aware filtering**

Change the service signature to:

```java
public List<FileUpload> getAccessibleFiles(String userId, String orgTags, String role)
```

For administrators load all files. For ordinary users retain the current repository query, then remove private-space rows unless an owner identifier matches:

```java
return files.stream()
    .filter(file -> !DocumentPermissionPolicy.isPrivateOrgTag(file.getOrgTag())
        || ownerIds.contains(file.getUserId()))
    .toList();
```

Pass `@RequestAttribute("role") String role` from `DocumentController.getAccessibleFiles`.

- [ ] **Step 4: Verify list tests pass**

Run: `cd backend && mvn -Dtest=DocumentServiceTest,DocumentControllerTest test`

Expected: owner and admin cases pass; unrelated users see no private rows.

### Task 4: Enforce private-space rules in knowledge search

**Files:**
- Modify: `backend/src/main/java/com/luky/nexusmind/service/HybridSearchService.java`
- Test: `backend/src/test/java/com/luky/nexusmind/service/HybridSearchPermissionQueryTest.java`

- [ ] **Step 1: Extract permission-query construction and write failing tests**

Expose a package-private method returning the Elasticsearch `Query` permission clause. Serialize it in tests and assert:

```java
assertTrue(userQueryJson.contains("must_not"));
assertTrue(userQueryJson.contains("PRIVATE_"));
assertFalse(adminQueryJson.contains("must_not"));
```

Also assert the user query contains the current database user ID as an owner condition.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `cd backend && mvn -Dtest=HybridSearchPermissionQueryTest test`

Expected: the extracted method or private-tag exclusion does not exist.

- [ ] **Step 3: Build role-aware Elasticsearch permission clauses**

Resolve the current `User` once. Administrators receive `match_all`. Ordinary users receive three `should` branches:

```text
owner == current user
OR (public == true AND orgTag does not have PRIVATE_ prefix)
OR (orgTag in effective tags AND orgTag does not have PRIVATE_ prefix)
```

Use the same clause in hybrid and text-only search paths. Elasticsearch wildcard/prefix exclusion must target the keyword-mapped `orgTag` field used by existing term queries.

- [ ] **Step 4: Verify search permission tests pass**

Run: `cd backend && mvn -Dtest=HybridSearchPermissionQueryTest test`

Expected: ordinary-user JSON excludes private tags from non-owner branches; administrator JSON is unrestricted.

### Task 5: Protect direct resource access

**Files:**
- Modify: `backend/src/main/java/com/luky/nexusmind/config/OrgTagAuthorizationFilter.java`
- Test: `backend/src/test/java/com/luky/nexusmind/config/OrgTagAuthorizationFilterTest.java`

- [ ] **Step 1: Add a failing regression test**

Create a resource with `orgTag="PRIVATE_alice"`, `isPublic=true`, owner `alice`; request it as `bob/USER` and assert:

```java
assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
assertFalse(chain.wasInvoked());
```

Add owner and administrator cases that assert the chain is invoked.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `cd backend && mvn -Dtest=OrgTagAuthorizationFilterTest test`

Expected: unrelated user is currently allowed because the public check runs before the private-space check.

- [ ] **Step 3: Reorder authorization decisions**

Authenticate and resolve owner/admin before general visibility. For private tags, allow only owner/admin and return 403 otherwise. Only after that branch may public/default/organization rules run.

- [ ] **Step 4: Verify filter tests pass**

Run: `cd backend && mvn -Dtest=OrgTagAuthorizationFilterTest test`

Expected: stale public flags cannot bypass private-space authorization.

### Task 6: Remove public selection from uploads

**Files:**
- Modify: `frontend/src/views/knowledge-base/modules/upload-dialog.vue`

- [ ] **Step 1: Remove the visibility form control and validation rule**

Keep `isPublic: false` in `createDefaultModel()` so the API payload stays compatible. Remove `isPublic: defaultRequiredRule` and remove the entire `NFormItem` labeled `是否公开`.

- [ ] **Step 2: Run frontend static verification**

Run: `cd frontend && pnpm typecheck`

Expected: typecheck passes.

- [ ] **Step 3: Build the frontend**

Run: `cd frontend && pnpm build`

Expected: production build succeeds and upload code still submits `isPublic=false`.

### Task 7: Full verification

**Files:**
- Verify all modified backend and frontend files.

- [ ] **Step 1: Run backend tests**

Run: `cd backend && mvn test`

Expected: BUILD SUCCESS with no test failures.

- [ ] **Step 2: Run backend package build**

Run: `cd backend && mvn clean package`

Expected: BUILD SUCCESS.

- [ ] **Step 3: Check the final diff**

Run: `git diff --check && git status --short`

Expected: no whitespace errors; only planned permission, test, frontend, and documentation files are changed.
