package com.luky.nexusmind.service;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.model.*;
import com.luky.nexusmind.repository.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.luky.nexusmind.utils.JwtUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class OrganizationService {
    private static final Set<String> SYSTEM_TAGS = Set.of("default", "admin");
    private final UserRepository userRepository;
    private final OrganizationTagRepository organizationRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final OrganizationMembershipService membershipService;
    private final OrganizationJoinRequestRepository requestRepository;
    private final NotificationService notificationService;
    private final MailService mailService;
    private final AuditService auditService;
    private final JwtUtils jwtUtils;

    public OrganizationService(UserRepository userRepository, OrganizationTagRepository organizationRepository,
                               OrganizationMembershipRepository membershipRepository,
                               OrganizationMembershipService membershipService,
                               OrganizationJoinRequestRepository requestRepository,
                               NotificationService notificationService, MailService mailService, AuditService auditService,
                               JwtUtils jwtUtils) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
        this.membershipService = membershipService;
        this.requestRepository = requestRepository;
        this.notificationService = notificationService;
        this.mailService = mailService;
        this.auditService = auditService;
        this.jwtUtils = jwtUtils;
    }

    public Map<String, Object> overview(String username, String keyword, int page, int size) {
        User user = requireUser(username);
        Set<String> effective = membershipService.effectiveTagIds(user);
        Set<String> direct = membershipService.direct(user).stream()
                .map(value -> value.getOrganization().getTagId()).collect(java.util.stream.Collectors.toSet());
        List<Map<String, Object>> mine = membershipService.direct(user).stream()
                .sorted(Comparator.comparing(value -> specialOrder(value.getOrganization())))
                .map(value -> organizationView(value.getOrganization(), user, "DIRECT", value.getJoinedAt()))
                .toList();
        Map<String, OrganizationJoinRequest> pending = new HashMap<>();
        organizationRepository.findAll().forEach(org -> requestRepository
                .findFirstByUserIdAndOrganizationTagIdAndStatus(user.getId(), org.getTagId(), OrganizationJoinRequest.Status.PENDING)
                .ifPresent(value -> pending.put(org.getTagId(), value)));
        String query = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> discover = organizationRepository.findAll().stream()
                .filter(this::isBusiness)
                .filter(org -> !org.isArchived() && org.isJoinable())
                .filter(org -> query.isEmpty() || path(org).toLowerCase(Locale.ROOT).contains(query))
                .sorted(Comparator.comparing(this::path, String.CASE_INSENSITIVE_ORDER))
                .map(org -> organizationView(org, user, direct.contains(org.getTagId()) ? "DIRECT"
                        : effective.contains(org.getTagId()) ? "INHERITED"
                        : pending.containsKey(org.getTagId()) ? "PENDING" : "AVAILABLE", null))
                .toList();
        int from = Math.min(Math.max(page - 1, 0) * size, discover.size());
        int to = Math.min(from + size, discover.size());
        // ponytail: in-memory slicing is enough for the current organization count; move path search into SQL if it grows materially.
        return Map.of("mine", mine, "discover", discover.subList(from, to), "discoverTotal", discover.size(),
                "primaryOrg", user.getPrimaryOrg());
    }

    public Map<String, Object> myRequests(String username, int page, int size) {
        User user = requireUser(username);
        Page<OrganizationJoinRequest> values = requestRepository.findByUserIdOrderByCreatedAtDesc(
                user.getId(), PageRequest.of(Math.max(page - 1, 0), size));
        return page(values.map(this::requestView), page, size);
    }

    @Transactional
    public void apply(String username, String tagId, String reason, String ip) {
        User user = requireUser(username);
        OrganizationTag org = requireBusiness(tagId);
        String cleanReason = required(reason, "申请理由");
        if (org.isArchived() || !org.isJoinable()) throw new CustomException("该组织暂不接受申请", HttpStatus.CONFLICT);
        if (membershipService.directMember(user, tagId)) throw new CustomException("你已加入该组织", HttpStatus.CONFLICT);
        if (requestRepository.findFirstByUserIdAndOrganizationTagIdAndStatus(user.getId(), tagId,
                OrganizationJoinRequest.Status.PENDING).isPresent()) throw new CustomException("已有待审批申请", HttpStatus.CONFLICT);
        if (requestRepository.countByUserIdAndCreatedAtAfter(user.getId(), LocalDateTime.now().minusHours(1)) >= 10)
            throw new CustomException("申请过于频繁，请稍后再试", HttpStatus.TOO_MANY_REQUESTS);
        enforceCooldown(user, tagId);
        OrganizationJoinRequest value = new OrganizationJoinRequest();
        value.setUser(user);
        value.setOrganization(org);
        value.setReason(cleanReason);
        requestRepository.save(value);
        auditService.record(user, "ORG_JOIN_APPLIED", user.getId(), tagId, cleanReason, ip);
        for (User admin : userRepository.findByRoleIn(List.of(User.Role.ADMIN, User.Role.SUPER_ADMIN))) {
            notificationService.notify(admin, "ORG_JOIN_REQUEST", "新的入组申请",
                    username + " 申请加入「" + path(org) + "」", "/org-tag?tab=applications");
            mailService.enqueueOrganizationApplication(admin, escape(username), escape(path(org)), escape(cleanReason));
        }
    }

    @Transactional
    public void withdraw(String username, Long requestId, String ip) {
        User user = requireUser(username);
        OrganizationJoinRequest value = requireRequest(requestId);
        if (!value.getUser().getId().equals(user.getId()) || value.getStatus() != OrganizationJoinRequest.Status.PENDING)
            throw new CustomException("该申请不能撤回", HttpStatus.CONFLICT);
        value.setStatus(OrganizationJoinRequest.Status.WITHDRAWN);
        value.setHandledAt(LocalDateTime.now());
        requestRepository.save(value);
        auditService.record(user, "ORG_JOIN_WITHDRAWN", user.getId(), value.getOrganization().getTagId(), null, ip);
    }

    @Transactional
    public void exit(String username, String tagId, String ip) {
        User user = requireUser(username);
        OrganizationTag org = requireBusiness(tagId);
        if (!membershipService.directMember(user, tagId)) throw new CustomException("你不是该组织的直接成员", HttpStatus.CONFLICT);
        membershipService.remove(user, tagId);
        auditService.record(user, "ORG_EXITED", user.getId(), tagId, null, ip);
    }

    public Map<String, Object> adminRequests(String status, int page, int size) {
        Page<OrganizationJoinRequest> values = status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)
                ? requestRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(Math.max(page - 1, 0), size))
                : requestRepository.findByStatusOrderByCreatedAtAsc(parseStatus(status), PageRequest.of(Math.max(page - 1, 0), size));
        Map<String, Object> result = new LinkedHashMap<>(page(values.map(this::requestView), page, size));
        result.put("pending", requestRepository.countByStatus(OrganizationJoinRequest.Status.PENDING));
        return result;
    }

    @Transactional
    public void decide(String adminUsername, Long requestId, boolean approve, String reason, String ip) {
        User admin = requireAdmin(adminUsername);
        OrganizationJoinRequest value = requireRequest(requestId);
        if (value.getStatus() != OrganizationJoinRequest.Status.PENDING) throw new CustomException("申请已被处理", HttpStatus.CONFLICT);
        if (!approve) value.setDecisionReason(required(reason, "拒绝原因"));
        else value.setDecisionReason(optional(reason));
        value.setHandledBy(admin);
        value.setHandledAt(LocalDateTime.now());
        value.setStatus(approve ? OrganizationJoinRequest.Status.APPROVED : OrganizationJoinRequest.Status.REJECTED);
        if (approve) membershipService.add(value.getUser(), value.getOrganization(), OrganizationMembership.Source.APPROVED);
        requestRepository.save(value);
        String action = approve ? "批准" : "拒绝";
        String content = "你加入「" + path(value.getOrganization()) + "」的申请已" + action
                + (!approve ? "，原因：" + value.getDecisionReason() : "");
        notificationService.notify(value.getUser(), "ORG_JOIN_RESULT", "入组申请已" + action, content, "/organization?tab=requests");
        String mailReason = value.getDecisionReason() == null || value.getDecisionReason().isBlank()
                ? "无" : escape(value.getDecisionReason());
        mailService.enqueueOrganizationResult(value.getUser(), escape(path(value.getOrganization())), action, mailReason);
        auditService.record(admin, approve ? "ORG_JOIN_APPROVED" : "ORG_JOIN_REJECTED",
                value.getUser().getId(), value.getOrganization().getTagId(), value.getDecisionReason(), ip);
    }

    @Transactional
    public void archive(String username, String tagId, String reason, boolean restore, String ip) {
        User admin = requireAdmin(username);
        OrganizationTag org = requireBusiness(tagId);
        String cleanReason = required(reason, restore ? "恢复原因" : "归档原因");
        if (restore) {
            if (org.getParentTag() != null && organizationRepository.findByTagId(org.getParentTag()).map(OrganizationTag::isArchived).orElse(false))
                throw new CustomException("请先恢复父组织", HttpStatus.CONFLICT);
            org.setArchivedAt(null);
            org.setArchiveReason(null);
        } else {
            if (!organizationRepository.findByParentTag(tagId).stream().allMatch(OrganizationTag::isArchived))
                throw new CustomException("请先归档所有子组织", HttpStatus.CONFLICT);
            org.setArchivedAt(LocalDateTime.now());
            org.setArchiveReason(cleanReason);
            org.setJoinable(false);
            for (OrganizationJoinRequest request : requestRepository.findByOrganizationTagIdAndStatus(tagId, OrganizationJoinRequest.Status.PENDING)) {
                request.setStatus(OrganizationJoinRequest.Status.ARCHIVED);
                request.setDecisionReason("组织已归档");
                request.setHandledBy(admin);
                request.setHandledAt(LocalDateTime.now());
                notificationService.notify(request.getUser(), "ORG_JOIN_RESULT", "入组申请已关闭",
                        "你加入「" + path(org) + "」的申请因组织归档而关闭", "/organization?tab=requests");
            }
            for (OrganizationMembership membership : membershipRepository.findByOrganizationTagId(tagId)) {
                User member = membership.getUser();
                if (tagId.equals(member.getPrimaryOrg())) {
                    member.setPrimaryOrg("PRIVATE_" + member.getUsername());
                    userRepository.save(member);
                }
            }
        }
        organizationRepository.save(org);
        auditService.record(admin, restore ? "ORG_RESTORED" : "ORG_ARCHIVED", null, tagId, cleanReason, ip);
    }

    @Transactional
    public void setJoinable(String username, String tagId, boolean joinable, String ip) {
        User admin = requireAdmin(username);
        OrganizationTag org = requireBusiness(tagId);
        if (org.isArchived() && joinable) throw new CustomException("已归档组织不能开放申请", HttpStatus.CONFLICT);
        org.setJoinable(joinable);
        organizationRepository.save(org);
        auditService.record(admin, joinable ? "ORG_APPLICATION_OPENED" : "ORG_APPLICATION_CLOSED",
                null, tagId, null, ip);
    }

    @Transactional
    public void assign(String adminUsername, Long userId, List<String> requestedTags, String reason, String ip) {
        User admin = requireAdmin(adminUsername);
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));
        String cleanReason = required(reason, "变更原因");
        Set<String> desired = new LinkedHashSet<>(requestedTags == null ? List.of() : requestedTags);
        Set<String> protectedTags = membershipService.direct(target).stream()
                .map(value -> value.getOrganization().getTagId())
                .filter(id -> id.equals("default") || id.startsWith("PRIVATE_"))
                .collect(java.util.stream.Collectors.toSet());
        desired.addAll(protectedTags);
        boolean wantsAdmin = desired.contains("admin");
        boolean hasAdmin = membershipService.directMember(target, "admin");
        if (wantsAdmin != hasAdmin && admin.getRole() != User.Role.SUPER_ADMIN)
            throw new CustomException("只有超级管理员可以授予或撤销管理员权限", HttpStatus.FORBIDDEN);
        if (!wantsAdmin && target.getRole() == User.Role.SUPER_ADMIN)
            throw new CustomException("请先将该超级管理员降级为管理员", HttpStatus.CONFLICT);
        Set<String> current = membershipService.direct(target).stream()
                .map(value -> value.getOrganization().getTagId()).collect(java.util.stream.Collectors.toSet());
        for (String tagId : desired) {
            OrganizationTag org = organizationRepository.findByTagId(tagId)
                    .orElseThrow(() -> new CustomException("组织不存在: " + tagId, HttpStatus.NOT_FOUND));
            if (org.isArchived()) throw new CustomException("不能加入已归档组织", HttpStatus.CONFLICT);
            if (!current.contains(tagId)) {
                membershipService.add(target, org, OrganizationMembership.Source.ADMIN);
                requestRepository.findFirstByUserIdAndOrganizationTagIdAndStatus(target.getId(), tagId,
                        OrganizationJoinRequest.Status.PENDING).ifPresent(pending -> {
                    pending.setStatus(OrganizationJoinRequest.Status.APPROVED);
                    pending.setHandledBy(admin);
                    pending.setHandledAt(LocalDateTime.now());
                    pending.setDecisionReason(cleanReason);
                    requestRepository.save(pending);
                });
            }
        }
        current.stream().filter(id -> !desired.contains(id) && !id.equals("default") && !id.startsWith("PRIVATE_"))
                .forEach(id -> {
                    OrganizationTag removed = organizationRepository.findByTagId(id).orElse(null);
                    membershipService.remove(target, id);
                    if (removed != null && isBusiness(removed)) {
                        OrganizationJoinRequest removal = new OrganizationJoinRequest();
                        removal.setUser(target);
                        removal.setOrganization(removed);
                        removal.setReason(cleanReason);
                        removal.setStatus(OrganizationJoinRequest.Status.REMOVED_BY_ADMIN);
                        removal.setHandledBy(admin);
                        removal.setHandledAt(LocalDateTime.now());
                        removal.setDecisionReason("管理员移除");
                        requestRepository.save(removal);
                    }
                });
        boolean roleChanged = false;
        if (wantsAdmin && target.getRole() == User.Role.USER) { target.setRole(User.Role.ADMIN); roleChanged = true; }
        if (!wantsAdmin && target.getRole() == User.Role.ADMIN) { target.setRole(User.Role.USER); roleChanged = true; }
        userRepository.save(target);
        if (roleChanged) jwtUtils.invalidateAllUserTokens(target.getId().toString());
        notificationService.notify(target, "ORG_MEMBERSHIP_CHANGED", "组织成员关系已变更",
                "管理员已调整你的组织成员关系。原因：" + cleanReason, "/organization");
        mailService.enqueueMembershipChange(target, escape(cleanReason));
        auditService.record(admin, "ORG_MEMBERSHIP_ASSIGNED", target.getId(), null, cleanReason, ip);
    }

    @Transactional
    public void changeSuperRole(String adminUsername, Long userId, boolean promote, String reason, String ip) {
        User admin = requireUser(adminUsername);
        if (admin.getRole() != User.Role.SUPER_ADMIN) throw new CustomException("需要超级管理员权限", HttpStatus.FORBIDDEN);
        if (admin.getId().equals(userId)) throw new CustomException("不能修改自己的超级管理员角色", HttpStatus.CONFLICT);
        User target = userRepository.findById(userId).orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));
        String cleanReason = required(reason, "变更原因");
        if (promote) {
            if (target.getRole() != User.Role.ADMIN) throw new CustomException("只能将管理员提升为超级管理员", HttpStatus.CONFLICT);
            if (!target.isEnabled()) throw new CustomException("请先启用该账户，再提升为超级管理员", HttpStatus.CONFLICT);
            target.setRole(User.Role.SUPER_ADMIN);
        } else {
            if (target.getRole() != User.Role.SUPER_ADMIN) throw new CustomException("目标用户不是超级管理员", HttpStatus.CONFLICT);
            if (userRepository.findByRoleIn(List.of(User.Role.SUPER_ADMIN)).size() <= 1)
                throw new CustomException("系统必须至少保留一名超级管理员", HttpStatus.CONFLICT);
            target.setRole(User.Role.ADMIN);
        }
        userRepository.save(target);
        jwtUtils.invalidateAllUserTokens(target.getId().toString());
        String action = promote ? "提升为超级管理员" : "降级为管理员";
        notificationService.notify(target, "ROLE_CHANGED", "账号角色已变更", "你的账号已" + action, "/personal-center");
        mailService.enqueueRoleChange(target, action);
        auditService.record(admin, promote ? "SUPER_ADMIN_PROMOTED" : "SUPER_ADMIN_DEMOTED",
                target.getId(), "admin", cleanReason, ip);
    }

    public String path(OrganizationTag org) {
        LinkedList<String> names = new LinkedList<>();
        Set<String> visited = new HashSet<>();
        OrganizationTag current = org;
        while (current != null && visited.add(current.getTagId())) {
            names.addFirst(current.getName());
            current = current.getParentTag() == null ? null : organizationRepository.findByTagId(current.getParentTag()).orElse(null);
        }
        return String.join(" / ", names);
    }

    private Map<String, Object> organizationView(OrganizationTag org, User user, String membership, LocalDateTime joinedAt) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("tagId", org.getTagId());
        value.put("name", org.getName());
        value.put("path", isPrivate(org) ? "我的私人空间" : path(org));
        value.put("description", org.getDescription());
        value.put("membership", membership);
        value.put("system", !isBusiness(org));
        value.put("archived", org.isArchived());
        value.put("joinable", org.isJoinable());
        value.put("primary", org.getTagId().equals(user.getPrimaryOrg()));
        value.put("joinedAt", joinedAt);
        return value;
    }

    private Map<String, Object> requestView(OrganizationJoinRequest request) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", request.getId());
        value.put("userId", request.getUser().getId());
        value.put("username", request.getUser().getUsername());
        value.put("displayName", request.getUser().getDisplayName() == null
                ? request.getUser().getUsername() : request.getUser().getDisplayName());
        value.put("orgTag", request.getOrganization().getTagId());
        value.put("organization", path(request.getOrganization()));
        value.put("reason", request.getReason());
        value.put("status", request.getStatus());
        value.put("decisionReason", request.getDecisionReason());
        value.put("handledBy", request.getHandledBy() == null ? null : request.getHandledBy().getUsername());
        value.put("createdAt", request.getCreatedAt());
        value.put("handledAt", request.getHandledAt());
        return value;
    }

    private void enforceCooldown(User user, String tagId) {
        requestRepository.findFirstByUserIdAndOrganizationTagIdOrderByCreatedAtDesc(user.getId(), tagId).ifPresent(previous -> {
            long minutes = Duration.between(previous.getUpdatedAt(), LocalDateTime.now()).toMinutes();
            if (previous.getStatus() == OrganizationJoinRequest.Status.REJECTED && minutes < 24 * 60)
                throw new CustomException("被拒绝后 24 小时内不能重复申请", HttpStatus.TOO_MANY_REQUESTS);
            if (previous.getStatus() == OrganizationJoinRequest.Status.REMOVED_BY_ADMIN && minutes < 24 * 60)
                throw new CustomException("被管理员移除后 24 小时内不能重复申请", HttpStatus.TOO_MANY_REQUESTS);
            if (previous.getStatus() == OrganizationJoinRequest.Status.WITHDRAWN && minutes < 10)
                throw new CustomException("撤回后 10 分钟内不能重复申请", HttpStatus.TOO_MANY_REQUESTS);
        });
    }

    private User requireUser(String username) { return userRepository.findByUsername(username)
            .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND)); }
    private User requireAdmin(String username) {
        User user = requireUser(username);
        if (!user.getRole().isAdministrator()) throw new CustomException("需要管理员权限", HttpStatus.FORBIDDEN);
        return user;
    }
    private OrganizationTag requireBusiness(String tagId) {
        OrganizationTag org = organizationRepository.findByTagId(tagId)
                .orElseThrow(() -> new CustomException("组织不存在", HttpStatus.NOT_FOUND));
        if (!isBusiness(org)) throw new CustomException("系统组织不可操作", HttpStatus.FORBIDDEN);
        return org;
    }
    private OrganizationJoinRequest requireRequest(Long id) { return requestRepository.findById(id)
            .orElseThrow(() -> new CustomException("申请不存在", HttpStatus.NOT_FOUND)); }
    private boolean isBusiness(OrganizationTag org) { return !SYSTEM_TAGS.contains(org.getTagId()) && !isPrivate(org); }
    private boolean isPrivate(OrganizationTag org) { return org.getTagId().startsWith("PRIVATE_"); }
    private int specialOrder(OrganizationTag org) { return isPrivate(org) ? 0 : "default".equals(org.getTagId()) ? 1 : 2; }
    private String required(String value, String label) {
        String clean = optional(value);
        if (clean == null || clean.isEmpty()) throw new CustomException(label + "不能为空", HttpStatus.BAD_REQUEST);
        return clean;
    }
    private String optional(String value) {
        if (value == null) return null;
        String clean = value.trim();
        if (clean.length() > 200) throw new CustomException("内容不能超过 200 字", HttpStatus.BAD_REQUEST);
        return clean;
    }
    private OrganizationJoinRequest.Status parseStatus(String value) {
        try { return OrganizationJoinRequest.Status.valueOf(value.toUpperCase(Locale.ROOT)); }
        catch (Exception e) { throw new CustomException("申请状态无效", HttpStatus.BAD_REQUEST); }
    }
    private Map<String, Object> page(Page<?> values, int page, int size) {
        return Map.of("content", values.getContent(), "page", page, "size", size, "totalElements", values.getTotalElements());
    }
    private String escape(String value) { return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}
