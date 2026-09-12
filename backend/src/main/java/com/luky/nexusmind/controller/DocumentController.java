package com.luky.nexusmind.controller;

import com.luky.nexusmind.model.FileUpload;
import com.luky.nexusmind.model.FileProcessingStatus;
import com.luky.nexusmind.model.OrganizationTag;
import com.luky.nexusmind.model.ProcessingStage;
import com.luky.nexusmind.model.ProcessingState;
import com.luky.nexusmind.repository.DocumentVectorRepository;
import com.luky.nexusmind.repository.FileUploadRepository;
import com.luky.nexusmind.repository.OrganizationTagRepository;
import com.luky.nexusmind.repository.UserRepository;
import com.luky.nexusmind.service.DocumentService;
import com.luky.nexusmind.service.DocumentDownloadTicketService;
import com.luky.nexusmind.service.ElasticsearchService;
import com.luky.nexusmind.service.FileProcessingStatusService;
import com.luky.nexusmind.service.ProcessingStatusEventService;
import com.luky.nexusmind.utils.LogUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 文档控制器类，处理文档相关操作请求
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentDownloadTicketService downloadTickets;

    @Autowired
    private FileProcessingStatusService processingStatusService;

    @Autowired
    private ProcessingStatusEventService processingStatusEventService;
    
    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired
    private ElasticsearchService elasticsearchService;
    
    @Autowired
    private OrganizationTagRepository organizationTagRepository;

    @Autowired
    private UserRepository userRepository;
    
    /**
     * 删除文档及其相关数据
     * 
     * @param fileMd5 文件MD5
     * @param userId 当前用户ID
     * @param role 用户角色
     * @return 删除结果
     */
    @DeleteMapping("/{fileMd5}")
    public ResponseEntity<?> deleteDocument(
            @PathVariable String fileMd5,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("role") String role) {
        
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("DELETE_DOCUMENT");
        try {
            LogUtils.logBusiness("DELETE_DOCUMENT", userId, "接收到删除文档请求: fileMd5=%s, role=%s", fileMd5, role);
            
            // 超级管理员可删除任意文档，其他用户仍只查询自己的文档。
            Optional<FileUpload> fileOpt = "SUPER_ADMIN".equals(role)
                    ? fileUploadRepository.findByFileMd5(fileMd5)
                    : fileUploadRepository.findByFileMd5AndUserId(fileMd5, userId);
            if (fileOpt.isEmpty()) {
                // 已被此前请求删除时按成功处理，仍不允许操作其他用户现存的文档。
                if (fileUploadRepository.countByFileMd5(fileMd5) == 0) {
                    documentService.deleteDocument(fileMd5, userId);
                    monitor.end("文档已删除");
                    return ResponseEntity.ok(Map.of("code", 200, "message", "文档已删除"));
                }
                LogUtils.logUserOperation(userId, "DELETE_DOCUMENT", fileMd5, "FAILED_NOT_FOUND");
                monitor.end("删除失败：文档不存在");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.NOT_FOUND.value());
                response.put("message", "文档不存在");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            
            FileUpload file = fileOpt.get();
            
            // 权限检查：只有文件所有者或超级管理员可以删除
            if (!file.getUserId().equals(userId) && !"SUPER_ADMIN".equals(role)) {
                LogUtils.logUserOperation(userId, "DELETE_DOCUMENT", fileMd5, "FAILED_PERMISSION_DENIED");
                LogUtils.logBusiness("DELETE_DOCUMENT", userId, "用户无权删除文档: fileMd5=%s, fileOwner=%s", fileMd5, file.getUserId());
                monitor.end("删除失败：权限不足");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.FORBIDDEN.value());
                response.put("message", "没有权限删除此文档");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
            }
            
            // 使用实际所有者查询文档及清理处理状态，操作日志仍记录当前用户。
            documentService.deleteDocument(fileMd5, file.getUserId());
            
            LogUtils.logFileOperation(userId, "DELETE", file.getFileName(), fileMd5, "SUCCESS");
            monitor.end("文档删除成功");
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "文档删除成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("DELETE_DOCUMENT", userId, "删除文档失败: fileMd5=%s", e, fileMd5);
            monitor.end("删除失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "删除未完成，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
    
    /**
     * 获取用户可访问的所有文件列表
     * 
     * @param userId 当前用户ID
     * @param orgTags 用户所属组织标签
     * @param orgTag 组织标签筛选，兼容旧单选参数
     * @param filterOrgTags 组织标签多选筛选
     * @param isPublic 公开状态筛选
     * @return 可访问的文件列表
     */
    @GetMapping("/accessible")
    public ResponseEntity<?> getAccessibleFiles(
            @RequestAttribute("userId") String userId,
            @RequestAttribute("orgTags") String orgTags,
            @RequestAttribute("role") String role,
            @RequestParam(required = false) String orgTag,
            @RequestParam(required = false) List<String> filterOrgTags,
            @RequestParam(required = false) Boolean isPublic) {
        
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_ACCESSIBLE_FILES");
        try {
            LogUtils.logBusiness(
                    "GET_ACCESSIBLE_FILES",
                    userId,
                    "接收到获取可访问文件请求: orgTags=%s, filterOrgTag=%s, isPublic=%s",
                    orgTags,
                    filterOrgTags == null || filterOrgTags.isEmpty() ? orgTag : filterOrgTags,
                    isPublic
            );
            
            List<FileUpload> files = documentService.getAccessibleFiles(userId, orgTags, role);
            files = filterAccessibleFiles(files, orgTag, filterOrgTags, isPublic);
            List<Map<String, Object>> fileData = toFileDtos(files, userId);
            
            LogUtils.logUserOperation(userId, "GET_ACCESSIBLE_FILES", "file_list", "SUCCESS");
            LogUtils.logBusiness("GET_ACCESSIBLE_FILES", userId, "成功获取可访问文件: fileCount=%d", files.size());
            monitor.end("获取可访问文件成功");
            
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "获取可访问文件列表成功");
            response.put("data", fileData);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_ACCESSIBLE_FILES", userId, "获取可访问文件失败", e);
            monitor.end("获取可访问文件失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "获取可访问文件列表失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    private List<FileUpload> filterAccessibleFiles(List<FileUpload> files, String orgTag, List<String> filterOrgTags, Boolean isPublic) {
        List<String> normalizedOrgTags = normalizeOrgTagFilters(orgTag, filterOrgTags);

        return files.stream()
                .filter(file -> normalizedOrgTags.isEmpty() || normalizedOrgTags.contains(file.getOrgTag()))
                .filter(file -> isPublic == null || file.isPublic() == isPublic)
                .collect(Collectors.toList());
    }

    private List<String> normalizeOrgTagFilters(String orgTag, List<String> filterOrgTags) {
        if (filterOrgTags != null && !filterOrgTags.isEmpty()) {
            return filterOrgTags.stream()
                    .filter(tag -> tag != null && !tag.isBlank())
                    .flatMap(tag -> List.of(tag.split(",")).stream())
                    .filter(tag -> tag != null && !tag.isBlank())
                    .map(String::trim)
                    .distinct()
                    .collect(Collectors.toList());
        }

        if (orgTag == null || orgTag.isBlank()) {
            return List.of();
        }

        return List.of(orgTag.trim());
    }
    
    /**
     * 获取用户上传的所有文件列表
     * 
     * @param userId 当前用户ID
     * @return 用户上传的文件列表
     */
    @GetMapping("/uploads")
    public ResponseEntity<?> getUserUploadedFiles(
            @RequestAttribute("userId") String userId) {
        
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_USER_UPLOADED_FILES");
        try {
            LogUtils.logBusiness("GET_USER_UPLOADED_FILES", userId, "接收到获取用户上传文件请求");
            
            List<FileUpload> files = documentService.getUserUploadedFiles(userId);
            List<Map<String, Object>> fileData = toFileDtos(files, userId);
            
            LogUtils.logUserOperation(userId, "GET_USER_UPLOADED_FILES", "file_list", "SUCCESS");
            LogUtils.logBusiness("GET_USER_UPLOADED_FILES", userId, "成功获取用户上传文件: fileCount=%d", files.size());
            monitor.end("获取用户上传文件成功");
            
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "获取用户上传文件列表成功");
            response.put("data", fileData);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_USER_UPLOADED_FILES", userId, "获取用户上传文件失败", e);
            monitor.end("获取用户上传文件失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "获取用户上传文件列表失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    private List<Map<String, Object>> toFileDtos(List<FileUpload> files, String currentUserId) {
        Map<String, FileProcessingStatus> processingStatusMap = findProcessingStatusesForFiles(files, currentUserId);

        return files.stream().map(file -> {
            Map<String, Object> dto = new HashMap<>();
            dto.put("id", file.getId());
            dto.put("fileMd5", file.getFileMd5());
            dto.put("contentMd5", file.getContentMd5() == null ? file.getFileMd5() : file.getContentMd5());
            dto.put("contentSha256", file.getContentSha256());
            dto.put("legacyShared", file.isLegacyShared());
            dto.put("fileName", file.getFileName());
            dto.put("totalSize", file.getTotalSize());
            dto.put("status", file.getStatus());
            dto.put("userId", file.getUserId());
            dto.put("uploaderName", resolveUploaderName(file.getUserId()));
            dto.put("public", file.isPublic());
            dto.put("orgTag", file.getOrgTag());
            dto.put("createdAt", file.getCreatedAt());
            dto.put("mergedAt", file.getMergedAt());
            dto.put("graphEnabled", file.isGraphEnabled());
            dto.put("graphStatus", file.getGraphStatus() == null
                    ? com.luky.nexusmind.model.KnowledgeGraphStatus.DISABLED
                    : file.getGraphStatus());
            dto.put("graphError", file.getGraphError());

            FileProcessingStatus processingStatus = processingStatusMap.get(file.getFileMd5());
            if (processingStatus != null) {
                dto.putAll(processingStatusEventService.toPayload(processingStatus));
            } else {
                fillLegacyProcessingStatus(dto, file);
            }

            dto.put("orgTagName", getOrgTagName(file.getOrgTag()));
            return dto;
        }).collect(Collectors.toList());
    }

    private Map<String, FileProcessingStatus> findProcessingStatusesForFiles(List<FileUpload> files, String currentUserId) {
        Map<String, List<FileUpload>> filesByOwner = files.stream()
                .collect(Collectors.groupingBy(file -> resolveProcessingStatusOwner(file, currentUserId)));

        Map<String, FileProcessingStatus> processingStatusMap = new HashMap<>();
        filesByOwner.forEach((ownerUserId, ownerFiles) -> processingStatusMap.putAll(
                processingStatusService.findLatestByFileMd5(
                        ownerFiles.stream().map(FileUpload::getFileMd5).collect(Collectors.toSet()),
                        ownerUserId
                )
        ));
        return processingStatusMap;
    }

    private String resolveProcessingStatusOwner(FileUpload file, String currentUserId) {
        if (file.getUserId() != null && !file.getUserId().isBlank()) {
            return file.getUserId();
        }
        return currentUserId;
    }

    private void fillLegacyProcessingStatus(Map<String, Object> dto, FileUpload file) {
        if (file.getStatus() != 1) {
            return;
        }

        long parsedChunkCount = documentVectorRepository.countDistinctChunksByFileMd5(file.getFileMd5());
        if (parsedChunkCount <= 0) {
            dto.put("processingStage", ProcessingStage.QUEUED);
            dto.put("processingState", ProcessingState.PENDING);
            dto.put("processingMessage", "等待处理");
            dto.put("parsedChunkCount", 0);
            dto.put("vectorizedCount", 0);
            dto.put("esDocumentCount", 0L);
            return;
        }

        long esDocumentCount;
        try {
            esDocumentCount = elasticsearchService.countByFileMd5(file.getFileMd5());
        } catch (Exception e) {
            esDocumentCount = 0;
        }

        dto.put("parsedChunkCount", parsedChunkCount);
        dto.put("vectorizedCount", esDocumentCount);
        dto.put("esDocumentCount", esDocumentCount);
        if (esDocumentCount > 0) {
            dto.put("processingStage", ProcessingStage.COMPLETED);
            dto.put("processingState", ProcessingState.SUCCEEDED);
            dto.put("processingMessage", "历史文件已入库");
        } else {
            dto.put("processingStage", ProcessingStage.CHUNKING);
            dto.put("processingState", ProcessingState.PENDING);
            dto.put("processingMessage", "历史文件已切片，未检测到向量索引");
        }
    }

    private String resolveUploaderName(String userId) {
        if (userId == null || userId.isBlank()) {
            return "-";
        }
        try {
            return userRepository.findById(Long.parseLong(userId))
                    .map(user -> user.getDisplayName())
                    .orElse(userId);
        } catch (NumberFormatException ignored) {
            // Older records may store username directly in file_upload.user_id.
        }
        return userRepository.findByUsername(userId)
                .map(user -> user.getDisplayName())
                .orElse(userId);
    }

    /**
     * 分页查看文件解析后的文本切片。
     */
    @GetMapping("/{fileMd5}/chunks")
    public ResponseEntity<?> getDocumentChunks(
            @PathVariable String fileMd5,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("orgTags") String orgTags) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_DOCUMENT_CHUNKS");
        try {
            Map<String, Object> data = documentService.getDocumentChunks(fileMd5, userId, orgTags, page, size, keyword);
            monitor.end("获取文件切片成功");

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "获取文件切片成功");
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_DOCUMENT_CHUNKS", userId, "获取文件切片失败: fileMd5=%s", e, fileMd5);
            monitor.end("获取文件切片失败: " + e.getMessage());

            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "获取文件切片失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 查看文件切片摘要信息。
     */
    @GetMapping("/{fileMd5}/chunks/summary")
    public ResponseEntity<?> getDocumentChunkSummary(
            @PathVariable String fileMd5,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("orgTags") String orgTags) {
        try {
            Map<String, Object> data = documentService.getDocumentChunkSummary(fileMd5, userId, orgTags);

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "获取文件切片摘要成功");
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "获取文件切片摘要失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 查看单个文本切片完整内容。
     */
    @GetMapping("/{fileMd5}/assets/{name}")
    public ResponseEntity<?> getParsedAsset(
            @PathVariable String fileMd5, @PathVariable String name,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("orgTags") String orgTags) {
        try {
            InputStream stream = documentService.openParsedAsset(fileMd5, name, userId, orgTags);
            String type = switch (name.substring(name.lastIndexOf('.') + 1).toLowerCase()) {
                case "png" -> "image/png";
                case "jpg" -> "image/jpeg";
                case "webp" -> "image/webp";
                case "gif" -> "image/gif";
                default -> "application/octet-stream";
            };
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(type))
                    .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                    .header("X-Content-Type-Options", "nosniff")
                    .body(new InputStreamResource(stream));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @GetMapping("/{fileMd5}/chunks/{chunkId}")
    public ResponseEntity<?> getDocumentChunkDetail(
            @PathVariable String fileMd5,
            @PathVariable Integer chunkId,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("orgTags") String orgTags) {
        try {
            Map<String, Object> data = documentService.getDocumentChunkDetail(fileMd5, chunkId, userId, orgTags);

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "获取文件切片详情成功");
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "获取文件切片详情失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
    

    /**
     * 仅信任 Security 过滤器已验证的登录态；URL 查询参数不再接受 JWT。
     * 组织可见性走服务端实时成员关系，不信任 token 内的 orgTags 声明。
     */
    private String authenticatedUsername() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UserDetails userDetails)) {
            return null;
        }
        return userDetails.getUsername();
    }

    private boolean validSession(String username) {
        if (username == null) {
            return false;
        }
        return userRepository.findByUsername(username).map(user -> user.isEnabled()).orElse(false);
    }

    /**
     * 下载票据仅在用户通过原下载接口的文件权限检查后签发。
     * 浏览器无需接触 MinIO 地址或把登录 JWT 放入下载链接。
     */
    @GetMapping("/download/content")
    public ResponseEntity<?> downloadContent(@RequestParam String ticket) {
        Optional<String> fileMd5 = downloadTickets.resolve(ticket);
        if (fileMd5.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Optional<FileUpload> file = fileUploadRepository.findByFileMd5(fileMd5.get());
        if (file.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        try {
            InputStream stream = documentService.openFileStream(fileMd5.get(), file.get().getFileName());
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(file.get().getFileName(), StandardCharsets.UTF_8).build().toString())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header("Referrer-Policy", "no-referrer")
                    .header("X-Content-Type-Options", "nosniff")
                    .body(new InputStreamResource(stream));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "文件下载失败"));
        }
    }

    @GetMapping("/download")
    public ResponseEntity<?> downloadFileByName(
            @RequestParam String fileName,
            @RequestParam(required = false) String fileMd5) {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("DOWNLOAD_FILE_BY_NAME");
        try {
            String userId = authenticatedUsername();

            LogUtils.logBusiness("DOWNLOAD_FILE_BY_NAME", userId != null ? userId : "anonymous", "接收到文件下载请求: fileName=%s", fileName);

            // 如果没有登录态，只允许下载公开文件
            if (userId == null || !validSession(userId)) {
                // 查找公开文件
                List<FileUpload> publicFiles = matchingFiles(null, fileName, fileMd5);
                if (publicFiles.isEmpty()) {
                    Map<String, Object> response = new HashMap<>();
                    response.put("code", HttpStatus.NOT_FOUND.value());
                    response.put("message", "文件不存在或需要登录访问");
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
                }

                if (publicFiles.size() != 1) return ambiguousFileResponse();
                FileUpload file = publicFiles.get(0);
                String downloadUrl = documentService.generateDownloadUrl(file.getFileMd5());

                if (downloadUrl == null) {
                    Map<String, Object> response = new HashMap<>();
                    response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
                    response.put("message", "无法生成下载链接");
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
                }

                Map<String, Object> response = new HashMap<>();
                response.put("code", 200);
                response.put("message", "文件下载链接生成成功");
                response.put("data", Map.of(
                    "fileName", file.getFileName(),
                    "downloadUrl", downloadUrl,
                    "fileSize", file.getTotalSize()
                ));
                return ResponseEntity.ok(response);
            }

            // 登录态组织可见性由服务端实时计算，不再信任 token 声明。
            List<FileUpload> targetFiles = matchingFiles(userId, fileName, fileMd5);
            if (targetFiles.isEmpty()) {
                LogUtils.logUserOperation(userId, "DOWNLOAD_FILE_BY_NAME", fileName, "FAILED_NOT_FOUND");
                monitor.end("下载失败：文件不存在或无权限访问");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.NOT_FOUND.value());
                response.put("message", "文件不存在或无权限访问");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            if (targetFiles.size() != 1) return ambiguousFileResponse();
            FileUpload file = targetFiles.get(0);

            // 生成下载链接或返回预签名URL
            String downloadUrl = documentService.generateDownloadUrl(file.getFileMd5());

            if (downloadUrl == null) {
                LogUtils.logUserOperation(userId, "DOWNLOAD_FILE_BY_NAME", fileName, "FAILED_GENERATE_URL");
                monitor.end("下载失败：无法生成下载链接");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
                response.put("message", "无法生成下载链接");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }

            LogUtils.logFileOperation(userId, "DOWNLOAD", file.getFileName(), file.getFileMd5(), "SUCCESS");
            LogUtils.logUserOperation(userId, "DOWNLOAD_FILE_BY_NAME", fileName, "SUCCESS");
            monitor.end("文件下载链接生成成功");

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "文件下载链接生成成功");
            response.put("data", Map.of(
                "fileName", file.getFileName(),
                "downloadUrl", downloadUrl,
                "fileSize", file.getTotalSize()
            ));
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            LogUtils.logBusinessError("DOWNLOAD_FILE_BY_NAME", "unknown", "文件下载失败: fileName=%s", e, fileName);
            monitor.end("下载失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "文件下载失败");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 在线预览PDF原文件（仅 Authorization 头，不接受 URL token）。
     */
    @GetMapping("/preview/pdf")
    public ResponseEntity<?> previewPdfByName(
            @RequestParam String fileName,
            @RequestParam(required = false) String fileMd5) {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("PREVIEW_PDF_BY_NAME");
        try {
            String userId = authenticatedUsername();
            if (userId != null && !validSession(userId)) {
                userId = null;
            }

            LogUtils.logBusiness("PREVIEW_PDF_BY_NAME", userId != null ? userId : "anonymous", "接收到PDF预览请求: fileName=%s", fileName);

            List<FileUpload> targetFiles = matchingFiles(userId, fileName, fileMd5);

            if (targetFiles.isEmpty()) {
                monitor.end("PDF预览失败：文件不存在或无权限访问");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.NOT_FOUND.value());
                response.put("message", "文件不存在或无权限访问");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            if (targetFiles.size() != 1) return ambiguousFileResponse();
            FileUpload file = targetFiles.get(0);
            if (!file.getFileName().toLowerCase().endsWith(".pdf")) {
                monitor.end("PDF预览失败：文件类型不支持");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.BAD_REQUEST.value());
                response.put("message", "仅支持PDF在线预览");
                return ResponseEntity.badRequest().body(response);
            }

            InputStream inputStream = documentService.openFileStream(file.getFileMd5(), file.getFileName());
            String encodedFileName = URLEncoder.encode(file.getFileName(), StandardCharsets.UTF_8).replace("+", "%20");

            LogUtils.logFileOperation(userId != null ? userId : "anonymous", "PREVIEW_PDF", file.getFileName(), file.getFileMd5(), "SUCCESS");
            monitor.end("PDF预览文件流打开成功");

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encodedFileName)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Referrer-Policy", "no-referrer")
                    .body(new InputStreamResource(inputStream));
        } catch (Exception e) {
            LogUtils.logBusinessError("PREVIEW_PDF_BY_NAME", "unknown", "PDF预览失败: fileName=%s", e, fileName);
            monitor.end("PDF预览失败");
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "PDF 预览失败");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 预览文件内容（仅 Authorization 头，不接受 URL token）。
     */
    @GetMapping("/preview")
    public ResponseEntity<?> previewFileByName(
            @RequestParam String fileName,
            @RequestParam(required = false) String fileMd5) {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("PREVIEW_FILE_BY_NAME");
        try {
            String userId = authenticatedUsername();
            if (userId != null && !validSession(userId)) {
                userId = null;
            }

            LogUtils.logBusiness("PREVIEW_FILE_BY_NAME", userId != null ? userId : "anonymous", "接收到文件预览请求: fileName=%s", fileName);

            // 未登录只允许预览公开文件
            if (userId == null) {
                List<FileUpload> publicFiles = matchingFiles(null, fileName, fileMd5);
                if (publicFiles.isEmpty()) {
                    Map<String, Object> response = new HashMap<>();
                    response.put("code", HttpStatus.NOT_FOUND.value());
                    response.put("message", "文件不存在或需要登录访问");
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
                }

                if (publicFiles.size() != 1) return ambiguousFileResponse();
                FileUpload file = publicFiles.get(0);
                String previewContent = documentService.getFilePreviewContent(file.getFileMd5(), file.getFileName());

                if (previewContent == null) {
                    Map<String, Object> response = new HashMap<>();
                    response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
                    response.put("message", "无法获取文件预览内容");
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
                }

                Map<String, Object> response = new HashMap<>();
                response.put("code", 200);
                response.put("message", "文件预览内容获取成功");
                response.put("data", Map.of(
                    "fileName", file.getFileName(),
                    "content", previewContent,
                    "fileSize", file.getTotalSize()
                ));
                return ResponseEntity.ok(response);
            }

            // 登录态组织可见性由服务端按用户实时计算。
            List<FileUpload> targetFiles = matchingFiles(userId, fileName, fileMd5);
            if (targetFiles.isEmpty()) {
                LogUtils.logUserOperation(userId, "PREVIEW_FILE_BY_NAME", fileName, "FAILED_NOT_FOUND");
                monitor.end("预览失败：文件不存在或无权限访问");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.NOT_FOUND.value());
                response.put("message", "文件不存在或无权限访问");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            if (targetFiles.size() != 1) return ambiguousFileResponse();
            FileUpload file = targetFiles.get(0);

            // 获取文件预览内容
            String previewContent = documentService.getFilePreviewContent(file.getFileMd5(), file.getFileName());

            if (previewContent == null) {
                LogUtils.logUserOperation(userId, "PREVIEW_FILE_BY_NAME", fileName, "FAILED_GET_CONTENT");
                monitor.end("预览失败：无法获取文件内容");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
                response.put("message", "无法获取文件预览内容");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }

            LogUtils.logFileOperation(userId, "PREVIEW", file.getFileName(), file.getFileMd5(), "SUCCESS");
            LogUtils.logUserOperation(userId, "PREVIEW_FILE_BY_NAME", fileName, "SUCCESS");
            monitor.end("文件预览内容获取成功");

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "文件预览内容获取成功");
            response.put("data", Map.of(
                "fileName", file.getFileName(),
                "content", previewContent,
                "fileSize", file.getTotalSize()
            ));
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            LogUtils.logBusinessError("PREVIEW_FILE_BY_NAME", "unknown", "文件预览失败: fileName=%s", e, fileName);
            monitor.end("预览失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "文件预览失败");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    private List<FileUpload> matchingFiles(String userId, String fileName, String fileMd5) {
        List<FileUpload> candidates = userId == null
                ? fileUploadRepository.findAllByFileNameAndIsPublicTrue(fileName)
                : documentService.getAccessibleFiles(userId, null);
        return candidates.stream()
                .filter(file -> fileName.equals(file.getFileName()))
                .filter(file -> fileMd5 == null || fileMd5.equals(file.getFileMd5()))
                .filter(file -> !file.isLegacyShared())
                .toList();
    }

    private ResponseEntity<?> ambiguousFileResponse() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", HttpStatus.CONFLICT.value(),
                "message", "同名文件有多份，请指定文档标识"));
    }

    /**
     * 根据tagId获取tagName
     *
     * @param tagId 组织标签ID
     * @return 组织标签名称，如果找不到则返回原tagId
     */
    private String getOrgTagName(String tagId) {
        if (tagId == null || tagId.isEmpty()) {
            return null;
        }
        
        try {
            Optional<OrganizationTag> tagOpt = organizationTagRepository.findByTagId(tagId);
            if (tagOpt.isPresent()) {
                return tagOpt.get().getName();
            } else {
                LogUtils.logBusiness("GET_ORG_TAG_NAME", "system", "找不到组织标签: tagId=%s", tagId);
                return tagId; // 如果找不到标签名称，返回原tagId
            }
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_ORG_TAG_NAME", "system", "查询组织标签名称失败: tagId=%s", e, tagId);
            return tagId; // 发生错误时返回原tagId
        }
    }
}
