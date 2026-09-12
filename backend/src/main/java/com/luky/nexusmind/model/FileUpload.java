package com.luky.nexusmind.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 文件上传实体类
 * 用于表示文件上传的相关信息
 */
@Data
@Entity
@Table(name = "file_upload")
public class FileUpload {
    /** Database identity for one user's document record. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; // 自增主键

    /** Legacy column name; new rows store a document key, not a content digest. */
    @Column(name = "file_md5", length = 32, nullable = false)
    private String fileMd5;

    /** Upload checksum; also used for owner-and-organization duplicate detection. */
    @Column(name = "content_md5", length = 32)
    private String contentMd5;

    @Column(name = "content_sha256", length = 64)
    private String contentSha256;

    /** Existing content-MD5 key was shared by multiple owners; never expose its pooled index. */
    @Column(name = "legacy_shared", nullable = false)
    private boolean legacyShared;

    /**
     * 文件的原始名称
     * 用于记录上传时文件的名称
     */
    private String fileName;

    /**
     * 文件的总大小
     * 以字节为单位记录文件的大小
     */
    private long totalSize;

    /**
     * 文件上传的状态
     * 0表示文件正在上传中，1表示文件上传已完成
     */
    private int status; // 0-上传中 1-已完成

    /**
     * 上传文件的用户的标识符
     * 用于记录哪个用户上传了文件
     */
    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;
    
    /**
     * 文件所属组织标签
     * 用于标识文件归属的组织，支持基于组织标签的权限控制
     */
    @Column(name = "org_tag")
    private String orgTag;

    /**
     * 文件是否公开
     * true表示所有用户可访问，false表示仅组织内用户可访问
     */
    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;

    /** Whether this document should be extracted into the knowledge graph. */
    @Column(name = "graph_enabled", nullable = false)
    private boolean graphEnabled = false;

    @Enumerated(EnumType.STRING)
    // Nullable for safe schema evolution of existing installations; new rows always receive DISABLED/QUEUED.
    @Column(name = "graph_status", length = 32)
    private KnowledgeGraphStatus graphStatus = KnowledgeGraphStatus.DISABLED;

    @Column(name = "graph_error", length = 1000)
    private String graphError;

    @Column(name = "graph_prompt_template_id")
    private Long graphPromptTemplateId;

    private Integer graphBatchChars = 3072;
    private Integer textChunkSize = 512;
    private String graphRunToken;

    /**
     * 文件上传的创建时间
     * 自动记录文件上传开始的时间
     */
    @CreationTimestamp
    private LocalDateTime createdAt;

    /**
     * 文件合并完成的时间
     * 当文件上传状态为已完成时，自动记录完成的时间
     */
    @UpdateTimestamp
    private LocalDateTime mergedAt;
}
