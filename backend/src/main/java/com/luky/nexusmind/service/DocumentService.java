package com.luky.nexusmind.service;

import com.luky.nexusmind.model.FileUpload;
import com.luky.nexusmind.model.DocumentVector;
import com.luky.nexusmind.model.FileProcessingStatus;
import com.luky.nexusmind.model.ParseEngine;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.DocumentVectorRepository;
import com.luky.nexusmind.repository.FileUploadRepository;
import com.luky.nexusmind.repository.UserRepository;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 文档管理服务类
 * 负责文档的删除等管理操作
 */
@Service
public class DocumentService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentService.class);
    private static final int MAX_PREVIEW_CHARS = 20000;
    private static final String DEFAULT_ORG_TAG = "default";
    private static final String LEGACY_DEFAULT_ORG_TAG = "DEFAULT";
    private static final String DEFAULT_ORG_NAME = "默认组织";

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FileProcessingStatusService processingStatusService;

    @Autowired
    private KnowledgeGraphService knowledgeGraphService;

    @Value("${file.parsing.chunk-size}")
    private int configuredChunkSize;

    /**
     * 删除文档及其相关数据
     * 该方法将删除:
     * 1. FileUpload记录
     * 2. DocumentVector记录
     * 3. MinIO中的文件
     * 4. Elasticsearch中的向量数据
     *
     * @param fileMd5 文件MD5
     */
    @Transactional
    public void deleteDocument(String fileMd5, String userId) {
        logger.info("开始删除文档: {}", fileMd5);
        
        try {
            // 获取文件信息以获取文件名
            FileUpload fileUpload = fileUploadRepository.findByFileMd5AndUserId(fileMd5, userId)
                    .orElseThrow(() -> new RuntimeException("文件不存在"));

            // 先清除图谱关系和审核候选，避免留下无法追溯到原文的孤儿事实。
            knowledgeGraphService.removeDocument(fileUpload);
            
            // 1. 删除Elasticsearch中的数据
            try {
                elasticsearchService.deleteByFileMd5(fileMd5);
                logger.info("成功从Elasticsearch删除文档: {}", fileMd5);
            } catch (Exception e) {
                logger.error("从Elasticsearch删除文档时出错: {}", fileMd5, e);
                // 继续删除其他数据
            }
            
            // 2. 删除MinIO中的文件
            try {
                String objectName = "merged/" + fileUpload.getFileName();
                minioClient.removeObject(
                        RemoveObjectArgs.builder()
                                .bucket("uploads")
                                .object(objectName)
                                .build()
                );
                logger.info("成功从MinIO删除文件: {}", objectName);
            } catch (Exception e) {
                logger.error("从MinIO删除文件时出错: {}", fileMd5, e);
                // 继续删除其他数据
            }
            
            // 3. 删除DocumentVector记录
            try {
                documentVectorRepository.deleteByFileMd5(fileMd5);
                logger.info("成功删除文档向量记录: {}", fileMd5);
            } catch (Exception e) {
                logger.error("删除文档向量记录时出错: {}", fileMd5, e);
                // 继续删除其他数据
            }
            
            // 4. 删除FileUpload记录
            processingStatusService.delete(fileMd5, userId);
            fileUploadRepository.deleteByFileMd5(fileMd5);
            logger.info("成功删除文件上传记录: {}", fileMd5);
            
            logger.info("文档删除完成: {}", fileMd5);
        } catch (Exception e) {
            logger.error("删除文档过程中发生错误: {}", fileMd5, e);
            throw new RuntimeException("删除文档失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取用户可访问的所有文件列表
     * 包括用户自己的文件、公开文件和用户所属组织的文件（支持层级权限）
     *
     * @param userId 用户ID
     * @param orgTags 用户所属的组织标签（逗号分隔的字符串，仅供兼容性使用）
     * @return 用户可访问的文件列表
     */
    public List<FileUpload> getAccessibleFiles(String userId, String orgTags) {
        String role = findUserByIdentifier(userId)
                .map(User::getRole)
                .map(Enum::name)
                .orElse("USER");
        return getAccessibleFiles(userId, orgTags, role);
    }

    public List<FileUpload> getAccessibleFiles(String userId, String orgTags, String role) {
        logger.info("获取用户可访问文件列表: userId={}", userId);
        
        try {
            User user = findUserByIdentifier(userId)
                    .orElseThrow(() -> new RuntimeException("用户不存在: " + userId));
            List<String> ownerIds = buildOwnerIdentifiers(user, userId);
            
            List<String> userEffectiveTags = withDefaultOrgAliases(orgTagCacheService.getUserEffectiveOrgTags(user.getUsername()));
            logger.debug("用户有效组织标签: {}", userEffectiveTags);
            
            // 使用有效标签查询文件
            List<FileUpload> files;
            if ("ADMIN".equals(role)) {
                files = fileUploadRepository.findAll();
                logger.debug("管理员返回全部文件");
            } else if (userEffectiveTags.isEmpty()) {
                // 如果用户没有任何组织标签，只返回自己的文件和公开文件
                files = fileUploadRepository.findByUserIdsOrIsPublicTrue(ownerIds);
                logger.debug("用户无组织标签，仅返回个人和公开文件");
            } else {
                // 查询用户可访问的所有文件（考虑层级标签）
                files = fileUploadRepository.findAccessibleFilesWithOwnersAndTags(ownerIds, userEffectiveTags);
                logger.debug("使用有效组织标签查询文件");
            }
            
            List<FileUpload> accessibleFiles = files.stream()
                    .filter(file -> !DocumentPermissionPolicy.isPrivateOrgTag(file.getOrgTag())
                            || "ADMIN".equals(role)
                            || ownerIds.contains(file.getUserId()))
                    .toList();

            logger.info("成功获取用户可访问文件列表: userId={}, fileCount={}", userId, accessibleFiles.size());
            return accessibleFiles;
        } catch (Exception e) {
            logger.error("获取用户可访问文件列表失败: userId={}", userId, e);
            throw new RuntimeException("获取可访问文件列表失败: " + e.getMessage(), e);
        }
    }

    private Optional<User> findUserByIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Optional.empty();
        }

        Optional<User> userByUsername = userRepository.findByUsername(identifier);
        if (userByUsername.isPresent()) {
            return userByUsername;
        }

        try {
            return userRepository.findById(Long.parseLong(identifier));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private List<String> buildOwnerIdentifiers(User user, String currentIdentifier) {
        Set<String> ownerIds = new LinkedHashSet<>();
        if (currentIdentifier != null && !currentIdentifier.isBlank()) {
            ownerIds.add(currentIdentifier);
        }
        ownerIds.add(user.getId().toString());
        ownerIds.add(user.getUsername());
        return new ArrayList<>(ownerIds);
    }

    private List<String> withDefaultOrgAliases(List<String> orgTags) {
        Set<String> normalizedTags = new LinkedHashSet<>();
        if (orgTags != null) {
            orgTags.stream()
                    .map(String::trim)
                    .filter(tag -> !tag.isEmpty())
                    .forEach(normalizedTags::add);
        }
        normalizedTags.add(DEFAULT_ORG_TAG);
        normalizedTags.add(LEGACY_DEFAULT_ORG_TAG);
        normalizedTags.add(DEFAULT_ORG_NAME);
        return new ArrayList<>(normalizedTags);
    }
    
    /**
     * 获取用户上传的所有文件列表
     *
     * @param userId 用户ID
     * @return 用户上传的文件列表
     */
    public List<FileUpload> getUserUploadedFiles(String userId) {
        logger.info("获取用户上传的文件列表: userId={}", userId);
        
        try {
            List<FileUpload> files = fileUploadRepository.findByUserId(userId);
            logger.info("成功获取用户上传的文件列表: userId={}, fileCount={}", userId, files.size());
            return files;
        } catch (Exception e) {
            logger.error("获取用户上传的文件列表失败: userId={}", userId, e);
            throw new RuntimeException("获取用户上传的文件列表失败: " + e.getMessage(), e);
        }
    }

    /**
     * 分页获取文件解析后的文本切片。
     */
    public Map<String, Object> getDocumentChunks(String fileMd5, String userId, String orgTags,
                                                 int page, int size, String keyword) {
        FileUpload file = getAccessibleFileOrThrow(fileMd5, userId, orgTags);
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        Page<DocumentVector> chunkPage = keyword == null || keyword.isBlank()
                ? documentVectorRepository.findDistinctChunksByFileMd5(fileMd5, pageable)
                : documentVectorRepository.findDistinctChunksByFileMd5AndKeyword(fileMd5, keyword.trim(), pageable);
        int actualChunkSize = resolveActualChunkSize(fileMd5, file.getUserId());

        List<Map<String, Object>> chunks = chunkPage.getContent().stream()
                .map(vector -> toChunkSummary(vector, actualChunkSize))
                .toList();

        Map<String, Object> data = new HashMap<>();
        data.put("fileMd5", file.getFileMd5());
        data.put("fileName", file.getFileName());
        data.put("configuredChunkSize", configuredChunkSize);
        data.put("actualChunkSize", actualChunkSize);
        data.put("actualParseEngine", resolveActualParseEngine(fileMd5, file.getUserId()));
        data.put("totalChunks", chunkPage.getTotalElements());
        data.put("page", chunkPage.getNumber());
        data.put("size", chunkPage.getSize());
        data.put("totalPages", chunkPage.getTotalPages());
        data.put("chunks", chunks);
        return data;
    }

    /**
     * 获取单个文本切片完整内容。
     */
    public Map<String, Object> getDocumentChunkDetail(String fileMd5, Integer chunkId, String userId, String orgTags) {
        FileUpload file = getAccessibleFileOrThrow(fileMd5, userId, orgTags);
        DocumentVector vector = documentVectorRepository.findByFileMd5AndChunkIdOrderByVectorIdAsc(fileMd5, chunkId).stream()
                .findFirst()
                .orElseThrow(() -> new RuntimeException("切片不存在"));

        String content = safeText(vector.getTextContent());
        Map<String, Object> data = toChunkSummary(vector, resolveActualChunkSize(fileMd5, file.getUserId()));
        data.put("fileName", file.getFileName());
        data.put("actualParseEngine", resolveActualParseEngine(fileMd5, file.getUserId()));
        data.put("content", content);
        return data;
    }

    public Map<String, Object> getDocumentChunkSummary(String fileMd5, String userId, String orgTags) {
        FileUpload file = getAccessibleFileOrThrow(fileMd5, userId, orgTags);
        long totalChunks = documentVectorRepository.countDistinctChunksByFileMd5(fileMd5);

        Map<String, Object> data = new HashMap<>();
        data.put("fileMd5", file.getFileMd5());
        data.put("fileName", file.getFileName());
        data.put("configuredChunkSize", configuredChunkSize);
        data.put("actualChunkSize", resolveActualChunkSize(fileMd5, file.getUserId()));
        data.put("actualParseEngine", resolveActualParseEngine(fileMd5, file.getUserId()));
        data.put("totalChunks", totalChunks);
        data.put("processed", file.getStatus() == 1 && totalChunks > 0);
        return data;
    }

    private FileUpload getAccessibleFileOrThrow(String fileMd5, String userId, String orgTags) {
        return getAccessibleFiles(userId, orgTags).stream()
                .filter(file -> file.getFileMd5().equals(fileMd5))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("文件不存在或无权限访问"));
    }

    private Map<String, Object> toChunkSummary(DocumentVector vector, int actualChunkSize) {
        String content = safeText(vector.getTextContent());
        Map<String, Object> dto = new HashMap<>();
        dto.put("fileMd5", vector.getFileMd5());
        dto.put("chunkId", vector.getChunkId());
        dto.put("contentPreview", preview(content));
        dto.put("contentLength", content.length());
        dto.put("byteSize", content.getBytes(StandardCharsets.UTF_8).length);
        dto.put("configuredChunkSize", configuredChunkSize);
        dto.put("actualChunkSize", actualChunkSize);
        dto.put("contentFormat", vector.getContentFormat() == null ? "PLAIN_TEXT" : vector.getContentFormat().name());
        dto.put("modelVersion", vector.getModelVersion());
        return dto;
    }

    private int resolveActualChunkSize(String fileMd5, String userId) {
        return processingStatusService.findByFileMd5AndUserId(fileMd5, userId)
                .map(FileProcessingStatus::getChunkSize)
                .filter(size -> size != null && size > 0)
                .orElse(configuredChunkSize);
    }

    private ParseEngine resolveActualParseEngine(String fileMd5, String userId) {
        return processingStatusService.findByFileMd5AndUserId(fileMd5, userId)
                .map(this::resolveActualParseEngine)
                .orElse(null);
    }

    private ParseEngine resolveActualParseEngine(FileProcessingStatus status) {
        return status.getActualParseEngine() != null ? status.getActualParseEngine() : status.getParseEngine();
    }

    private String safeText(String text) {
        return text == null ? "" : text;
    }

    private String preview(String content) {
        int previewLength = Math.min(content.length(), 200);
        String text = content.substring(0, previewLength);
        return content.length() > previewLength ? text + "..." : text;
    }
    
    /**
     * 生成文件下载链接
     * 
     * @param fileMd5 文件MD5
     * @return 预签名下载URL
     */
    public String generateDownloadUrl(String fileMd5) {
        logger.info("生成文件下载链接: fileMd5={}", fileMd5);
        
        try {
            // 从数据库获取文件信息
            FileUpload fileUpload = fileUploadRepository.findByFileMd5(fileMd5)
                    .orElseThrow(() -> new RuntimeException("文件不存在: " + fileMd5));
            
            // MinIO中的对象路径格式: merged/文件名
            String objectName = "merged/" + fileUpload.getFileName();
            
            // 生成预签名URL，有效期1小时
            String presignedUrl = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket("uploads")
                            .object(objectName)
                            .expiry(3600) // 1小时有效期
                            .build()
            );
            
            logger.info("成功生成文件下载链接: fileMd5={}, fileName={}, objectName={}", 
                    fileMd5, fileUpload.getFileName(), objectName);
            return presignedUrl;
        } catch (Exception e) {
            logger.error("生成文件下载链接失败: fileMd5={}", fileMd5, e);
            return null;
        }
    }

    /**
     * 打开原始文件流，用于浏览器内联预览。
     *
     * @param fileName 文件名
     * @return MinIO对象流
     */
    public InputStream openFileStream(String fileName) {
        logger.info("打开文件流: fileName={}", fileName);

        try {
            String objectName = "merged/" + fileName;
            return minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket("uploads")
                            .object(objectName)
                            .build());
        } catch (Exception e) {
            logger.error("打开文件流失败: fileName={}", fileName, e);
            throw new RuntimeException("打开文件流失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取文件预览内容
     * 
     * @param fileMd5 文件MD5
     * @param fileName 文件名
     * @return 文件预览内容，对于可解析文件返回提取文本，非支持文件返回文件信息
     */
    public String getFilePreviewContent(String fileMd5, String fileName) {
        logger.info("获取文件预览内容: fileMd5={}, fileName={}", fileMd5, fileName);
        
        try {
            // MinIO中的对象路径格式: merged/文件名
            String objectName = "merged/" + fileName;
            
            // 判断文件类型
            String fileExtension = getFileExtension(fileName).toLowerCase();
            boolean isPreviewableFile = isPreviewableFile(fileExtension);
            
            if (isPreviewableFile) {
                try (InputStream inputStream = minioClient.getObject(
                        GetObjectArgs.builder()
                                .bucket("uploads")
                                .object(objectName)
                                .build())) {

                    String result = extractPreviewText(inputStream);
                    if (result.isBlank()) {
                        return "未提取到可预览文本内容，请下载后查看。";
                    }

                    if (result.length() >= MAX_PREVIEW_CHARS) {
                        result += "\n... (内容已截断，仅显示前" + MAX_PREVIEW_CHARS + "字符)";
                    }

                    logger.info("成功获取文件预览内容: fileMd5={}, contentLength={}", fileMd5, result.length());
                    return result;
                }
            } else {
                // 对于非文本文件，返回文件信息
                FileUpload fileUpload = fileUploadRepository.findByFileMd5(fileMd5)
                        .orElseThrow(() -> new RuntimeException("文件不存在: " + fileMd5));
                
                String fileInfo = String.format(
                    "文件名: %s\n" +
                    "文件大小: %s\n" +
                    "文件类型: %s\n" +
                    "上传时间: %s\n\n" +
                    "此文件类型不支持预览，请下载后查看。",
                    fileName,
                    formatFileSize(fileUpload.getTotalSize()),
                    fileExtension.toUpperCase(),
                    fileUpload.getCreatedAt()
                );
                
                logger.info("返回非文本文件信息: fileMd5={}", fileMd5);
                return fileInfo;
            }
            
        } catch (Exception e) {
            logger.error("获取文件预览内容失败: fileMd5={}, fileName={}", fileMd5, fileName, e);
            return "预览失败: " + e.getMessage();
        }
    }

    private String extractPreviewText(InputStream inputStream) throws Exception {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler(MAX_PREVIEW_CHARS);
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();

        try {
            parser.parse(inputStream, handler, metadata, context);
        } catch (Exception e) {
            if (!isWriteLimitReached(e)) {
                throw e;
            }
            logger.debug("文件预览内容超过字符上限，已截断");
        }

        return handler.toString().trim();
    }

    private boolean isWriteLimitReached(Exception e) {
        Throwable current = e;
        while (current != null) {
            if (current.getClass().getName().contains("WriteLimitReachedException")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
    
    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return "";
        }
        return fileName.substring(lastDotIndex + 1);
    }
    
    /**
     * 判断是否为可提取预览文本的文件
     */
    private boolean isPreviewableFile(String extension) {
        String[] previewableExtensions = {
            "txt", "md", "doc", "docx", "pdf", "html", "htm", "xml", "json", 
            "csv", "log", "java", "js", "ts", "py", "cpp", "c", "h", "css", 
            "scss", "less", "sql", "yml", "yaml", "properties", "conf", "config",
            "rtf", "odt", "ppt", "pptx", "xls", "xlsx"
        };
        
        return Arrays.stream(previewableExtensions)
                .anyMatch(ext -> ext.equalsIgnoreCase(extension));
    }
    
    /**
     * 格式化文件大小
     */
    private String formatFileSize(Long size) {
        if (size == null) return "未知";
        
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.1f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", size / (1024.0 * 1024.0));
        } else {
            return String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
        }
    }
}
