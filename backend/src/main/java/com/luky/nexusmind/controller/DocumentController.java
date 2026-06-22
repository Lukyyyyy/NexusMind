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
import com.luky.nexusmind.service.ElasticsearchService;
import com.luky.nexusmind.service.FileProcessingStatusService;
import com.luky.nexusmind.service.ProcessingStatusEventService;
import com.luky.nexusmind.utils.LogUtils;
import com.luky.nexusmind.utils.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
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
    
    @Autowired
    private JwtUtils jwtUtils;

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
            
            // 获取文件信息
            Optional<FileUpload> fileOpt = fileUploadRepository.findByFileMd5AndUserId(fileMd5, userId);
            if (fileOpt.isEmpty()) {
                LogUtils.logUserOperation(userId, "DELETE_DOCUMENT", fileMd5, "FAILED_NOT_FOUND");
                monitor.end("删除失败：文档不存在");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.NOT_FOUND.value());
                response.put("message", "文档不存在");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            
            FileUpload file = fileOpt.get();
            
            // 权限检查：只有文件所有者或管理员可以删除
            if (!file.getUserId().equals(userId) && !"ADMIN".equals(role)) {
                LogUtils.logUserOperation(userId, "DELETE_DOCUMENT", fileMd5, "FAILED_PERMISSION_DENIED");
                LogUtils.logBusiness("DELETE_DOCUMENT", userId, "用户无权删除文档: fileMd5=%s, fileOwner=%s", fileMd5, file.getUserId());
                monitor.end("删除失败：权限不足");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.FORBIDDEN.value());
                response.put("message", "没有权限删除此文档");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
            }
            
            // 执行删除操作
            documentService.deleteDocument(fileMd5, userId);
            
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
            response.put("message", "删除文档失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
    
    /**
     * 获取用户可访问的所有文件列表
     * 
     * @param userId 当前用户ID
     * @param orgTags 用户所属组织标签
     * @return 可访问的文件列表
     */
    @GetMapping("/accessible")
    public ResponseEntity<?> getAccessibleFiles(
            @RequestAttribute("userId") String userId,
            @RequestAttribute("orgTags") String orgTags) {
        
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_ACCESSIBLE_FILES");
        try {
            LogUtils.logBusiness("GET_ACCESSIBLE_FILES", userId, "接收到获取可访问文件请求: orgTags=%s", orgTags);
            
            List<FileUpload> files = documentService.getAccessibleFiles(userId, orgTags);
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
            dto.put("fileMd5", file.getFileMd5());
            dto.put("fileName", file.getFileName());
            dto.put("totalSize", file.getTotalSize());
            dto.put("status", file.getStatus());
            dto.put("userId", file.getUserId());
            dto.put("uploaderName", resolveUploaderName(file.getUserId()));
            dto.put("public", file.isPublic());
            dto.put("createdAt", file.getCreatedAt());
            dto.put("mergedAt", file.getMergedAt());

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
                    .map(user -> user.getUsername())
                    .orElse(userId);
        } catch (NumberFormatException ignored) {
            // Older records may store username directly in file_upload.user_id.
        }
        return userRepository.findByUsername(userId)
                .map(user -> user.getUsername())
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
     * 根据文件名下载文件
     * 
     * @param fileName 文件名
     * @param token JWT token
     * @return 文件资源或错误响应
     */
    @GetMapping("/download")
    public ResponseEntity<?> downloadFileByName(
            @RequestParam String fileName,
            @RequestParam(required = false) String token) {
        
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("DOWNLOAD_FILE_BY_NAME");
        try {
            // 验证token并获取用户信息
            String userId = null;
            String orgTags = null;
            
            if (token != null && !token.trim().isEmpty()) {
                try {
                    // 解析JWT token获取用户信息
                    // 注意：JWT中的sub字段存储用户名，userId字段存储用户ID（但有时可能存储的是用户名）
                    userId = jwtUtils.extractUsernameFromToken(token);
                    orgTags = jwtUtils.extractOrgTagsFromToken(token);
                } catch (Exception e) {
                    LogUtils.logBusiness("DOWNLOAD_FILE_BY_NAME", "anonymous", "Token解析失败: fileName=%s", fileName);
                }
            }
            
            LogUtils.logBusiness("DOWNLOAD_FILE_BY_NAME", userId != null ? userId : "anonymous", "接收到文件下载请求: fileName=%s", fileName);
            
            // 如果没有提供token或token无效，只允许下载公开文件
            if (userId == null) {
                // 查找公开文件
                Optional<FileUpload> publicFile = fileUploadRepository.findByFileNameAndIsPublicTrue(fileName);
                if (publicFile.isEmpty()) {
                    Map<String, Object> response = new HashMap<>();
                    response.put("code", HttpStatus.NOT_FOUND.value());
                    response.put("message", "文件不存在或需要登录访问");
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
                }
                
                FileUpload file = publicFile.get();
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
            
            // 有token的情况，查找用户可访问的文件
            List<FileUpload> accessibleFiles = documentService.getAccessibleFiles(userId, orgTags);
            
            // 根据文件名查找匹配的文件
            Optional<FileUpload> targetFile = accessibleFiles.stream()
                    .filter(file -> file.getFileName().equals(fileName))
                    .findFirst();
                    
            if (targetFile.isEmpty()) {
                LogUtils.logUserOperation(userId, "DOWNLOAD_FILE_BY_NAME", fileName, "FAILED_NOT_FOUND");
                monitor.end("下载失败：文件不存在或无权限访问");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.NOT_FOUND.value());
                response.put("message", "文件不存在或无权限访问");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            
            FileUpload file = targetFile.get();
            
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
            String userId = "unknown";
            try {
                if (token != null && !token.trim().isEmpty()) {
                    userId = jwtUtils.extractUsernameFromToken(token);
                }
            } catch (Exception ignored) {}
            
            LogUtils.logBusinessError("DOWNLOAD_FILE_BY_NAME", userId, "文件下载失败: fileName=%s", e, fileName);
            monitor.end("下载失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "文件下载失败: " + e.getMessage()); 
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
    
    /**
     * 在线预览PDF原文件。
     *
     * @param fileName 文件名
     * @param token JWT token
     * @return PDF文件流或错误响应
     */
    @GetMapping("/preview/pdf")
    public ResponseEntity<?> previewPdfByName(
            @RequestParam String fileName,
            @RequestParam(required = false) String token,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("PREVIEW_PDF_BY_NAME");
        try {
            String userId = null;
            String orgTags = null;
            String resolvedToken = resolveToken(token, authorization);

            if (resolvedToken != null) {
                try {
                    userId = jwtUtils.extractUsernameFromToken(resolvedToken);
                    orgTags = jwtUtils.extractOrgTagsFromToken(resolvedToken);
                } catch (Exception e) {
                    LogUtils.logBusiness("PREVIEW_PDF_BY_NAME", "anonymous", "Token解析失败: fileName=%s", fileName);
                }
            }

            LogUtils.logBusiness("PREVIEW_PDF_BY_NAME", userId != null ? userId : "anonymous", "接收到PDF预览请求: fileName=%s", fileName);

            Optional<FileUpload> targetFile;
            if (userId == null) {
                targetFile = fileUploadRepository.findByFileNameAndIsPublicTrue(fileName);
            } else {
                List<FileUpload> accessibleFiles = documentService.getAccessibleFiles(userId, orgTags);
                targetFile = accessibleFiles.stream()
                        .filter(file -> file.getFileName().equals(fileName))
                        .findFirst();
            }

            if (targetFile.isEmpty()) {
                monitor.end("PDF预览失败：文件不存在或无权限访问");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.NOT_FOUND.value());
                response.put("message", "文件不存在或无权限访问");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            FileUpload file = targetFile.get();
            if (!file.getFileName().toLowerCase().endsWith(".pdf")) {
                monitor.end("PDF预览失败：文件类型不支持");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.BAD_REQUEST.value());
                response.put("message", "仅支持PDF在线预览");
                return ResponseEntity.badRequest().body(response);
            }

            InputStream inputStream = documentService.openFileStream(file.getFileName());
            String encodedFileName = URLEncoder.encode(file.getFileName(), StandardCharsets.UTF_8).replace("+", "%20");

            LogUtils.logFileOperation(userId != null ? userId : "anonymous", "PREVIEW_PDF", file.getFileName(), file.getFileMd5(), "SUCCESS");
            monitor.end("PDF预览文件流打开成功");

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encodedFileName)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(new InputStreamResource(inputStream));
        } catch (Exception e) {
            String userId = "unknown";
            try {
                String resolvedToken = resolveToken(token, authorization);
                if (resolvedToken != null) {
                    userId = jwtUtils.extractUsernameFromToken(resolvedToken);
                }
            } catch (Exception ignored) {}

            LogUtils.logBusinessError("PREVIEW_PDF_BY_NAME", userId, "PDF预览失败: fileName=%s", e, fileName);
            monitor.end("PDF预览失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "PDF预览失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    private String resolveToken(String token, String authorization) {
        if (token != null && !token.trim().isEmpty()) {
            return token;
        }

        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring(7);
        }

        return null;
    }

    /**
     * 预览文件内容
     * 
     * @param fileName 文件名
     * @param token JWT token (URL参数，用于向后兼容)
     * @return 文件预览内容或错误响应
     */
    @GetMapping("/preview")
    public ResponseEntity<?> previewFileByName(
            @RequestParam String fileName,
            @RequestParam(required = false) String token) {
        
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("PREVIEW_FILE_BY_NAME");
        try {
            // 验证token并获取用户信息
            String userId = null;
            String orgTags = null;
            
            // 优先从Spring Security上下文获取已认证的用户信息
            try {
                var authentication = SecurityContextHolder.getContext().getAuthentication();
                if (authentication != null && authentication.isAuthenticated() 
                    && authentication.getPrincipal() instanceof UserDetails) {
                    UserDetails userDetails = (UserDetails) authentication.getPrincipal();
                    userId = userDetails.getUsername();
                    // 从userDetails中获取组织标签信息
                    orgTags = userDetails.getAuthorities().stream()
                        .map(auth -> auth.getAuthority().replace("ROLE_", ""))
                        .findFirst()
                        .orElse(null);
                }
            } catch (Exception e) {
                LogUtils.logBusiness("PREVIEW_FILE_BY_NAME", "anonymous", "Security上下文获取失败: fileName=%s", fileName);
            }
            
            // 如果Security上下文中没有用户信息，尝试从URL参数token中获取
            if (userId == null && token != null && !token.trim().isEmpty()) {
                try {
                    userId = jwtUtils.extractUsernameFromToken(token);
                    orgTags = jwtUtils.extractOrgTagsFromToken(token);
                } catch (Exception e) {
                    LogUtils.logBusiness("PREVIEW_FILE_BY_NAME", "anonymous", "Token解析失败: fileName=%s", fileName);
                }
            }
            
            LogUtils.logBusiness("PREVIEW_FILE_BY_NAME", userId != null ? userId : "anonymous", "接收到文件预览请求: fileName=%s", fileName);
            
            // 如果没有提供token或token无效，只允许预览公开文件
            if (userId == null) {
                Optional<FileUpload> publicFile = fileUploadRepository.findByFileNameAndIsPublicTrue(fileName);
                if (publicFile.isEmpty()) {
                    Map<String, Object> response = new HashMap<>();
                    response.put("code", HttpStatus.NOT_FOUND.value());
                    response.put("message", "文件不存在或需要登录访问");
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
                }
                
                FileUpload file = publicFile.get();
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
            
            // 有token的情况，查找用户可访问的文件
            List<FileUpload> accessibleFiles = documentService.getAccessibleFiles(userId, orgTags);
            
            // 根据文件名查找匹配的文件
            Optional<FileUpload> targetFile = accessibleFiles.stream()
                    .filter(file -> file.getFileName().equals(fileName))
                    .findFirst();
                    
            if (targetFile.isEmpty()) {
                LogUtils.logUserOperation(userId, "PREVIEW_FILE_BY_NAME", fileName, "FAILED_NOT_FOUND");
                monitor.end("预览失败：文件不存在或无权限访问");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.NOT_FOUND.value());
                response.put("message", "文件不存在或无权限访问");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            
            FileUpload file = targetFile.get();
            
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
            String userId = "unknown";
            try {
                if (token != null && !token.trim().isEmpty()) {
                    userId = jwtUtils.extractUsernameFromToken(token);
                }
            } catch (Exception ignored) {}
            
            LogUtils.logBusinessError("PREVIEW_FILE_BY_NAME", userId, "文件预览失败: fileName=%s", e, fileName);
            monitor.end("预览失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "文件预览失败: " + e.getMessage()); 
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
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
