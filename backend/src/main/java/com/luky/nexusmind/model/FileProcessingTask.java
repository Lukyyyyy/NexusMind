package com.luky.nexusmind.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件处理任务类，用于Kafka消息传递
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class FileProcessingTask {
    private String fileMd5; // 文件的 MD5 校验值
    private String filePath; // 文件存储路径
    private String fileName; // 文件名
    private String userId;   // 上传用户ID
    private String orgTag;   // 文件所属组织标签
    private boolean isPublic; // 文件是否公开
    private ParseEngine parseEngine = ParseEngine.AUTO; // 文档解析引擎
    private Integer chunkSize; // 该文件解析时使用的文本切片大小
    private String traceparent; // OpenTelemetry trace context

    public FileProcessingTask(String fileMd5, String filePath, String fileName,
                              String userId, String orgTag, boolean isPublic) {
        this(fileMd5, filePath, fileName, userId, orgTag, isPublic, ParseEngine.AUTO, null);
    }

    public FileProcessingTask(String fileMd5, String filePath, String fileName,
                              String userId, String orgTag, boolean isPublic, ParseEngine parseEngine) {
        this(fileMd5, filePath, fileName, userId, orgTag, isPublic, parseEngine, null);
    }

    public FileProcessingTask(String fileMd5, String filePath, String fileName,
                              String userId, String orgTag, boolean isPublic, ParseEngine parseEngine, Integer chunkSize) {
        this.fileMd5 = fileMd5;
        this.filePath = filePath;
        this.fileName = fileName;
        this.userId = userId;
        this.orgTag = orgTag;
        this.isPublic = isPublic;
        this.parseEngine = parseEngine == null ? ParseEngine.AUTO : parseEngine;
        this.chunkSize = chunkSize;
    }
    
    /**
     * 向后兼容的构造函数
     */
    public FileProcessingTask(String fileMd5, String filePath, String fileName) {
        this.fileMd5 = fileMd5;
        this.filePath = filePath;
        this.fileName = fileName;
        this.userId = null;
        this.orgTag = "default";
        this.isPublic = false;
        this.parseEngine = ParseEngine.AUTO;
        this.chunkSize = null;
    }
}
