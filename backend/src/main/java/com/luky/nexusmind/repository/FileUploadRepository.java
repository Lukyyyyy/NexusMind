package com.luky.nexusmind.repository;

import com.luky.nexusmind.model.FileUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface FileUploadRepository extends JpaRepository<FileUpload, Long> {
    List<FileUpload> findByGraphStatusInAndGraphRunTokenIsNotNull(List<com.luky.nexusmind.model.KnowledgeGraphStatus> statuses);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from FileUpload f where f.fileMd5 = :md5")
    Optional<FileUpload> lockByMd5(@Param("md5") String md5);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from FileUpload f where f.fileMd5 = :md5 and f.userId = :owner")
    Optional<FileUpload> lockByMd5AndOwner(@Param("md5") String md5, @Param("owner") String owner);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from FileUpload f where f.fileMd5 = :md5 order by f.id")
    List<FileUpload> lockAllByMd5(@Param("md5") String md5);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from FileUpload f where f.id = :id")
    Optional<FileUpload> lockById(@Param("id") Long id);

    Optional<FileUpload> findByFileMd5(String fileMd5);
    
    Optional<FileUpload> findByFileMd5AndUserId(String fileMd5, String userId);

    @Query("select f from FileUpload f where f.userId = :owner and f.orgTag = :orgTag "
            + "and (f.contentSha256 = :sha256 or (f.contentSha256 is null and f.contentMd5 = :checksum))")
    Optional<FileUpload> findDuplicate(@Param("owner") String owner,
                                       @Param("orgTag") String orgTag,
                                       @Param("sha256") String sha256,
                                       @Param("checksum") String checksum);
    
    Optional<FileUpload> findByFileNameAndIsPublicTrue(String fileName);
    List<FileUpload> findAllByFileNameAndIsPublicTrue(String fileName);
    
    long countByFileMd5(String fileMd5);
    long countByOrgTag(String orgTag);
    
    void deleteByFileMd5(String fileMd5);
    
    void deleteByFileMd5AndUserId(String fileMd5, String userId);
    
    /**
     * 查询用户自己的文件和公开文件
     */
    List<FileUpload> findByUserIdOrIsPublicTrue(String userId);
    
    /**
     * 查询用户可访问的所有文件（考虑层级标签权限）
     * 包括：1. 用户自己上传的文件
     *      2. 公开的文件
     *      3. 用户所属组织的文件（包含层级关系）
     *
     * @param userId 用户ID
     * @param orgTagList 用户有效的组织标签列表（包含层级结构）
     * @return 用户可访问的文件列表
     */
    @Query("SELECT f FROM FileUpload f WHERE f.userId = :userId OR f.isPublic = true OR (f.orgTag IN :orgTagList AND f.isPublic = false)")
    List<FileUpload> findAccessibleFilesWithTags(@Param("userId") String userId, @Param("orgTagList") List<String> orgTagList);

    @Query("SELECT f FROM FileUpload f WHERE f.userId IN :ownerIds OR f.isPublic = true OR (f.orgTag IN :orgTagList AND f.isPublic = false)")
    List<FileUpload> findAccessibleFilesWithOwnersAndTags(@Param("ownerIds") List<String> ownerIds, @Param("orgTagList") List<String> orgTagList);

    @Query("SELECT f FROM FileUpload f WHERE f.userId IN :ownerIds OR f.isPublic = true")
    List<FileUpload> findByUserIdsOrIsPublicTrue(@Param("ownerIds") List<String> ownerIds);
    
    /**
     * 查询用户可访问的所有文件（原始方法，保留向后兼容性）
     * 
     * @param userId 用户ID
     * @param orgTagList 用户所属的组织标签列表（逗号分隔）
     * @return 用户可访问的文件列表
     */
    @Query("SELECT f FROM FileUpload f WHERE f.userId = :userId OR f.isPublic = true OR (f.orgTag IN :orgTagList AND f.isPublic = false)")
    List<FileUpload> findAccessibleFiles(@Param("userId") String userId, @Param("orgTagList") List<String> orgTagList);
    
    /**
     * 查询用户自己上传的所有文件
     * 
     * @param userId 用户ID
     * @return 用户上传的文件列表
     */
    List<FileUpload> findByUserId(String userId);

    List<FileUpload> findByFileMd5In(List<String> md5List);
}
