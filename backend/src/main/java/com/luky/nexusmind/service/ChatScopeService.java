package com.luky.nexusmind.service;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.model.ChatScopeType;
import com.luky.nexusmind.model.ChatSession;
import com.luky.nexusmind.model.FileUpload;
import com.luky.nexusmind.model.OrganizationTag;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.DocumentVectorRepository;
import com.luky.nexusmind.repository.OrganizationTagRepository;
import com.luky.nexusmind.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class ChatScopeService {
    private static final int MAX_DOCUMENTS = 10;

    private final DocumentService documentService;
    private final DocumentVectorRepository documentVectorRepository;
    private final OrganizationTagRepository organizationTagRepository;
    private final UserRepository userRepository;

    public ChatScopeService(DocumentService documentService,
                            DocumentVectorRepository documentVectorRepository,
                            OrganizationTagRepository organizationTagRepository,
                            UserRepository userRepository) {
        this.documentService = documentService;
        this.documentVectorRepository = documentVectorRepository;
        this.organizationTagRepository = organizationTagRepository;
        this.userRepository = userRepository;
    }

    public ScopeSelection select(String username, ChatScopeType type, String orgTag, List<Long> documentIds) {
        ChatScopeType effectiveType = type == null ? ChatScopeType.ALL : type;
        List<FileUpload> ready = readyAccessibleFiles(username);
        return switch (effectiveType) {
            case ALL -> new ScopeSelection(ChatScopeType.ALL, null, "全部知识", null);
            case PRIVATE -> selectPrivate(username, ready);
            case ORGANIZATION -> selectOrganization(orgTag, ready);
            case DOCUMENTS -> selectDocuments(documentIds, ready);
        };
    }

    public List<FileUpload> resolveFiles(String username, ChatSession session) {
        List<FileUpload> ready = readyAccessibleFiles(username);
        ChatScopeType type = session.getScopeType() == null ? ChatScopeType.ALL : session.getScopeType();
        List<FileUpload> scoped = switch (type) {
            case ALL -> ready;
            case PRIVATE -> ready.stream().filter(file -> isOwnedPrivate(username, file)).toList();
            case ORGANIZATION -> ready.stream()
                    .filter(file -> Objects.equals(session.getScopeValue(), file.getOrgTag()))
                    .toList();
            case DOCUMENTS -> {
                Set<Long> ids = parseDocumentIds(session.getScopeValue());
                List<FileUpload> matches = ready.stream().filter(file -> ids.contains(file.getId())).toList();
                if (matches.size() != ids.size()) {
                    throw new CustomException("问答范围中的文档已删除或无权访问，请重新选择范围", HttpStatus.CONFLICT);
                }
                yield matches;
            }
        };
        if (type != ChatScopeType.ALL && scoped.isEmpty()) {
            throw new CustomException("当前问答范围已无可检索文档，请重新选择范围", HttpStatus.CONFLICT);
        }
        return scoped;
    }

    public ScopeOptions options(String username) {
        List<FileUpload> ready = readyAccessibleFiles(username);
        Map<String, String> organizationNames = organizationTagRepository.findAllById(
                        ready.stream().map(FileUpload::getOrgTag)
                                .filter(Objects::nonNull)
                                .filter(tag -> !DocumentPermissionPolicy.isPrivateOrgTag(tag))
                                .collect(java.util.stream.Collectors.toSet()))
                .stream().collect(java.util.stream.Collectors.toMap(OrganizationTag::getTagId, OrganizationTag::getName));

        Map<String, OrganizationOption> organizations = new LinkedHashMap<>();
        for (FileUpload file : ready) {
            String tag = file.getOrgTag();
            if (tag == null || DocumentPermissionPolicy.isPrivateOrgTag(tag)) continue;
            OrganizationOption previous = organizations.get(tag);
            organizations.put(tag, new OrganizationOption(tag,
                    organizationNames.getOrDefault(tag, "default".equalsIgnoreCase(tag) ? "默认组织" : tag),
                    previous == null ? 1 : previous.documentCount() + 1));
        }

        List<DocumentOption> documents = ready.stream()
                .map(file -> new DocumentOption(file.getId(), file.getFileMd5(), file.getFileName(), file.getOrgTag()))
                .sorted(java.util.Comparator.comparing(DocumentOption::fileName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new ScopeOptions(ready.stream().anyMatch(file -> isOwnedPrivate(username, file)),
                List.copyOf(organizations.values()), documents);
    }

    public static Map<String, Object> view(ChatSession session) {
        Map<String, Object> view = new LinkedHashMap<>();
        ChatScopeType type = session.getScopeType() == null ? ChatScopeType.ALL : session.getScopeType();
        view.put("type", type.name());
        view.put("label", type == ChatScopeType.ALL ? "全部知识"
                : hasText(session.getScopeLabel()) ? session.getScopeLabel() : "全部知识");
        view.put("orgTag", session.getScopeType() == ChatScopeType.ORGANIZATION ? session.getScopeValue() : null);
        view.put("documentIds", session.getScopeType() == ChatScopeType.DOCUMENTS
                ? parseStoredDocumentIds(session.getScopeValue()) : Set.of());
        view.put("details", hasText(session.getScopeDetails()) ? session.getScopeDetails().lines().toList() : List.of());
        return view;
    }

    private ScopeSelection selectPrivate(String username, List<FileUpload> ready) {
        if (ready.stream().noneMatch(file -> isOwnedPrivate(username, file))) {
            throw new CustomException("我的私人空间暂无可检索文档", HttpStatus.BAD_REQUEST);
        }
        return new ScopeSelection(ChatScopeType.PRIVATE, null, "我的私人空间", null);
    }

    private ScopeSelection selectOrganization(String orgTag, List<FileUpload> ready) {
        String tag = Objects.toString(orgTag, "").trim();
        if (tag.isEmpty() || DocumentPermissionPolicy.isPrivateOrgTag(tag)) {
            throw new CustomException("请选择有效的组织", HttpStatus.BAD_REQUEST);
        }
        if (ready.stream().noneMatch(file -> tag.equals(file.getOrgTag()))) {
            throw new CustomException("该组织暂无可检索文档或无权访问", HttpStatus.BAD_REQUEST);
        }
        String label = organizationTagRepository.findById(tag).map(OrganizationTag::getName)
                .orElse("default".equalsIgnoreCase(tag) ? "默认组织" : tag);
        return new ScopeSelection(ChatScopeType.ORGANIZATION, tag, label, null);
    }

    private ScopeSelection selectDocuments(List<Long> documentIds, List<FileUpload> ready) {
        Set<Long> ids = new LinkedHashSet<>(documentIds == null ? List.of() : documentIds);
        if (ids.isEmpty() || ids.size() > MAX_DOCUMENTS || ids.contains(null)) {
            throw new CustomException("请选择 1 至 10 份文档", HttpStatus.BAD_REQUEST);
        }
        List<FileUpload> selected = ready.stream().filter(file -> ids.contains(file.getId())).toList();
        if (selected.size() != ids.size()) {
            throw new CustomException("部分文档未完成索引或无权访问", HttpStatus.BAD_REQUEST);
        }
        String details = selected.stream().map(FileUpload::getFileName).collect(java.util.stream.Collectors.joining("\n"));
        return new ScopeSelection(ChatScopeType.DOCUMENTS,
                ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")),
                selected.size() + " 份文档", details);
    }

    private List<FileUpload> readyAccessibleFiles(String username) {
        Set<String> indexedMd5s = documentVectorRepository.findIndexedFileMd5s();
        return documentService.getAccessibleFiles(username, "").stream()
                .filter(file -> !file.isLegacyShared() && file.getStatus() == 1
                        && indexedMd5s.contains(file.getFileMd5()))
                .toList();
    }

    private boolean isOwnedPrivate(String username, FileUpload file) {
        if (!DocumentPermissionPolicy.isPrivateOrgTag(file.getOrgTag())) return false;
        User user = userRepository.findByUsername(username).orElse(null);
        return Objects.equals(file.getUserId(), username)
                || user != null && Objects.equals(file.getUserId(), String.valueOf(user.getId()));
    }

    private Set<Long> parseDocumentIds(String value) {
        return parseStoredDocumentIds(value);
    }

    private static Set<Long> parseStoredDocumentIds(String value) {
        if (!hasText(value)) return Set.of();
        try {
            return Arrays.stream(value.split(","))
                    .map(String::trim).filter(part -> !part.isEmpty()).map(Long::valueOf)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        } catch (NumberFormatException exception) {
            throw new CustomException("会话问答范围配置无效，请重新选择范围", HttpStatus.CONFLICT);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record ScopeSelection(ChatScopeType type, String value, String label, String details) {}
    public record ScopeOptions(boolean privateAvailable, List<OrganizationOption> organizations,
                               List<DocumentOption> documents) {}
    public record OrganizationOption(String tagId, String name, int documentCount) {}
    public record DocumentOption(Long id, String fileMd5, String fileName, String orgTag) {}
}
