package com.luky.nexusmind.utils;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.UserRepository;
import com.luky.nexusmind.service.TokenCacheService;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class JwtUtils {
    private static final Logger logger = LoggerFactory.getLogger(JwtUtils.class);
    public static final String ACCOUNT_DISABLED_CODE = "ACCOUNT_DISABLED";
    public static final String ACCOUNT_DISABLED_MESSAGE = "你的账户已被禁用，请联系超级管理员";

    @Value("${jwt.secret-key}")
    private String secretKey;

    private static final long EXPIRATION_TIME = 3600000; // 1 hour (调整为1小时)
    private static final long REFRESH_TOKEN_EXPIRATION_TIME = 604800000; // 7 days (refresh token有效期)
    private static final long REFRESH_THRESHOLD = 300000; // 5分钟：当剩余时间少于5分钟时开始刷新
    private static final long REFRESH_WINDOW = 600000; // 10分钟：token过期后的宽限期
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private TokenCacheService tokenCacheService;

    /**
     * Resolve JWT signing key from either Base64 or plain UTF-8 secret text.
     * 生产环境拒绝弱密钥启动，避免默认密钥被全网复现伪造。
     */
    private SecretKey getSigningKey() {
        byte[] keyBytes = resolveSecretKeyBytes();
        return Keys.hmacShaKeyFor(keyBytes);
    }

    private byte[] resolveSecretKeyBytes() {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("JWT_SECRET_KEY 未配置，拒绝启动");
        }
        String candidate = secretKey.trim();
        if ("change-me-in-local-dev-only".equals(candidate)) {
            throw new IllegalStateException("JWT_SECRET_KEY 仍为默认值，拒绝启动");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(candidate);
        } catch (IllegalArgumentException e) {
            keyBytes = candidate.getBytes(StandardCharsets.UTF_8);
        }

        if (keyBytes.length >= 32) {
            return keyBytes;
        }

        throw new IllegalStateException("JWT_SECRET_KEY 长度不足32字节，拒绝启动");
    }

    /**
     * 生成 JWT Token（集成Redis缓存）
     */
    public String generateToken(String username) {
        return generateToken(username, null);
    }

    public String generateToken(String username, String refreshTokenId) {
        SecretKey key = getSigningKey(); // 解析密钥
        
        // 获取用户信息
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
        requireEnabled(user);
        
        // 生成唯一的tokenId
        String tokenId = generateTokenId();
        long expireTime = System.currentTimeMillis() + EXPIRATION_TIME;
        
        // 创建token内容
        Map<String, Object> claims = new HashMap<>();
        claims.put("tokenId", tokenId); // 添加tokenId用于Redis缓存
        claims.put("role", user.getRole().name());
        claims.put("userId", user.getId().toString()); // 添加用户ID到JWT
        claims.put("sessionVersion", user.getSessionVersion());
        if (refreshTokenId != null) claims.put("refreshTokenId", refreshTokenId);
        
        // 添加组织标签信息
        if (user.getOrgTags() != null && !user.getOrgTags().isEmpty()) {
            claims.put("orgTags", user.getOrgTags());
        }
        
        // 添加主组织标签信息
        if (user.getPrimaryOrg() != null && !user.getPrimaryOrg().isEmpty()) {
            claims.put("primaryOrg", user.getPrimaryOrg());
        }

        String token = Jwts.builder()
                .setClaims(claims)
                .setSubject(username)
                .setExpiration(new Date(expireTime))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
        
        // 缓存token信息到Redis
        tokenCacheService.cacheToken(tokenId, user.getId().toString(), username, expireTime);
        
        logger.info("Token generated and cached for user: {}, tokenId: {}", username, tokenId);
        return token;
    }

    /**
     * 验证 JWT Token 是否有效（优先使用Redis缓存）
     */
    public boolean validateToken(String token) {
        try {
            // 首先从JWT中提取tokenId（快速失败）
            String tokenId = extractTokenIdFromToken(token);
            if (tokenId == null) {
                logger.warn("Token does not contain tokenId");
                return false;
            }
            
            // 检查Redis缓存中的token状态
            if (!tokenCacheService.isTokenValid(tokenId)) {
                logger.debug("Token invalid in cache: {}", tokenId);
                return false;
            }
            
            // Redis验证通过，再验证JWT签名（双重验证）
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            User user = userRepository.findByUsername(claims.getSubject()).orElse(null);
            if (user == null || !user.isEnabled() || tokenVersion(claims) != user.getSessionVersion()) return false;

            logger.debug("Token validation successful: {}", tokenId);
            return true;
        } catch (ExpiredJwtException e) {
            logger.warn("Token expired: {}", e.getClaims().get("tokenId", String.class));
        } catch (SignatureException e) {
            logger.warn("Invalid token signature");
        } catch (Exception e) {
            logger.error("Error validating token", e);
        }
        return false;
    }

    /**
     * 从 JWT Token 中提取用户名
     */
    public String extractUsernameFromToken(String token) {
        try {
            Claims claims = extractClaimsIgnoreExpiration(token);
            return claims != null ? claims.getSubject() : null;
        } catch (Exception e) {
            logger.debug("Cannot extract username from token", e);
            return null;
        }
    }
    
    /**
     * 从 JWT Token 中提取用户ID
     */
    public String extractUserIdFromToken(String token) {
        try {
            Claims claims = extractClaimsIgnoreExpiration(token);
            return claims != null ? claims.get("userId", String.class) : null;
        } catch (Exception e) {
            logger.debug("Cannot extract userId from token", e);
            return null;
        }
    }
    
    /**
     * 从 JWT Token 中提取用户角色
     */
    public String extractRoleFromToken(String token) {
        try {
            Claims claims = extractClaimsIgnoreExpiration(token);
            return claims != null ? claims.get("role", String.class) : null;
        } catch (Exception e) {
            logger.debug("Cannot extract role from token", e);
            return null;
        }
    }
    
    /**
     * 从 JWT Token 中提取组织标签
     */
    public String extractOrgTagsFromToken(String token) {
        try {
            Claims claims = extractClaimsIgnoreExpiration(token);
            return claims != null ? claims.get("orgTags", String.class) : null;
        } catch (Exception e) {
            logger.debug("Cannot extract organization tags from token", e);
            return null;
        }
    }
    
    /**
     * 从 JWT Token 中提取主组织标签
     */
    public String extractPrimaryOrgFromToken(String token) {
        try {
            Claims claims = extractClaimsIgnoreExpiration(token);
            return claims != null ? claims.get("primaryOrg", String.class) : null;
        } catch (Exception e) {
            logger.debug("Cannot extract primary organization from token", e);
            return null;
        }
    }
    
    /**
     * 检查token是否应该刷新（剩余时间少于阈值）
     */
    public boolean shouldRefreshToken(String token) {
        try {
            Claims claims = extractClaims(token);
            if (claims == null) return false;
            
            long expirationTime = claims.getExpiration().getTime();
            long currentTime = System.currentTimeMillis();
            long remainingTime = expirationTime - currentTime;
            
            return remainingTime > 0 && remainingTime < REFRESH_THRESHOLD;
        } catch (Exception e) {
            logger.debug("Cannot check if token should refresh: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 检查过期token是否仍可刷新（在宽限期内）
     */
    public boolean canRefreshExpiredToken(String token) {
        try {
            Claims claims = extractClaimsIgnoreExpiration(token);
            if (claims == null) return false;
            
            long expirationTime = claims.getExpiration().getTime();
            long currentTime = System.currentTimeMillis();
            long expiredTime = currentTime - expirationTime;
            
            return expiredTime > 0 && expiredTime < REFRESH_WINDOW;
        } catch (Exception e) {
            logger.debug("Cannot check if expired token can refresh: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 刷新token（生成新的token）
     */
    public String refreshToken(String oldToken) {
        try {
            if (!validateToken(oldToken)) return null;
            Claims claims = extractClaims(oldToken);
            if (claims == null) return null;
            
            String username = claims.getSubject();
            if (username == null || username.isEmpty()) return null;
            User user = userRepository.findByUsername(username).orElse(null);
            if (user == null || !user.isEnabled() || tokenVersion(claims) != user.getSessionVersion()) return null;
            
            // 重新生成token
            String newToken = generateToken(username);
            logger.info("Token refreshed successfully for user: {}", username);
            return newToken;
        } catch (Exception e) {
            logger.error("Error refreshing token: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * 提取Claims，忽略过期异常
     */
    private Claims extractClaimsIgnoreExpiration(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (ExpiredJwtException e) {
            // 忽略过期异常，返回claims
            return e.getClaims();
        } catch (Exception e) {
            logger.debug("Cannot extract claims from token: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * 提取Claims（正常验证）
     */
    private Claims extractClaims(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 生成 Refresh Token（长期有效的刷新令牌，集成Redis缓存）
     */
    public String generateRefreshToken(String username) {
        SecretKey key = getSigningKey();
        
        // 获取用户信息
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
        requireEnabled(user);
        
        // 生成唯一的refreshTokenId
        String refreshTokenId = generateTokenId();
        long expireTime = System.currentTimeMillis() + REFRESH_TOKEN_EXPIRATION_TIME;
        
        // 创建refreshToken内容（相对简单，只包含基本信息）
        Map<String, Object> claims = new HashMap<>();
        claims.put("refreshTokenId", refreshTokenId); // 添加refreshTokenId
        claims.put("userId", user.getId().toString());
        claims.put("sessionVersion", user.getSessionVersion());
        claims.put("type", "refresh"); // 标识这是一个refresh token

        String refreshToken = Jwts.builder()
                .setClaims(claims)
                .setSubject(username)
                .setExpiration(new Date(expireTime))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
        
        // 缓存refresh token信息到Redis
        tokenCacheService.cacheRefreshToken(refreshTokenId, user.getId().toString(), null, expireTime);
        
        logger.info("Refresh token generated and cached for user: {}, refreshTokenId: {}", username, refreshTokenId);
        return refreshToken;
    }
    
    /**
     * 验证 Refresh Token 是否有效（优先使用Redis缓存）
     */
    public boolean validateRefreshToken(String refreshToken) {
        try {
            // 首先从JWT中提取refreshTokenId
            String refreshTokenId = extractRefreshTokenIdFromToken(refreshToken);
            if (refreshTokenId == null) {
                logger.warn("Refresh token does not contain refreshTokenId");
                return false;
            }
            
            // 检查Redis缓存中的refresh token状态
            if (!tokenCacheService.isRefreshTokenValid(refreshTokenId)) {
                logger.debug("Refresh token invalid in cache: {}", refreshTokenId);
                return false;
            }
            
            // Redis验证通过，再验证JWT签名
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(refreshToken)
                    .getBody();
            
            // 验证是否为refresh token类型
            String tokenType = claims.get("type", String.class);
            if (!"refresh".equals(tokenType)) {
                logger.warn("Token is not a refresh token");
                return false;
            }

            User user = userRepository.findByUsername(claims.getSubject()).orElse(null);
            if (user == null || !user.isEnabled() || tokenVersion(claims) != user.getSessionVersion()) return false;

            logger.debug("Refresh token validation successful: {}", refreshTokenId);
            return true;
        } catch (ExpiredJwtException e) {
            logger.warn("Refresh token expired: {}", e.getClaims().get("refreshTokenId", String.class));
        } catch (SignatureException e) {
            logger.warn("Invalid refresh token signature");
        } catch (Exception e) {
            logger.error("Error validating refresh token", e);
        }
        return false;
    }

    public boolean consumeRefreshToken(String refreshToken) {
        if (!validateRefreshToken(refreshToken)) return false;
        Claims claims = extractClaims(refreshToken);
        return claims != null && tokenCacheService.consumeRefreshToken(
                claims.get("refreshTokenId", String.class), claims.get("userId", String.class));
    }
    
    /**
     * 从 JWT Token 中提取refreshTokenId
     */
    public String extractRefreshTokenIdFromToken(String refreshToken) {
        try {
            Claims claims = extractClaimsIgnoreExpiration(refreshToken);
            return claims != null ? claims.get("refreshTokenId", String.class) : null;
        } catch (Exception e) {
            logger.debug("Error extracting refreshTokenId from token", e);
            return null;
        }
    }
    
    /**
     * 生成唯一的tokenId
     */
    private String generateTokenId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
    
    /**
     * 从 JWT Token 中提取tokenId
     */
    public String extractTokenIdFromToken(String token) {
        try {
            Claims claims = extractClaimsIgnoreExpiration(token);
            return claims != null ? claims.get("tokenId", String.class) : null;
        } catch (Exception e) {
            logger.debug("Error extracting tokenId from token", e);
            return null;
        }
    }
    
    /**
     * 使token失效（加入Redis黑名单）
     */
    public void invalidateToken(String token) {
        try {
            String tokenId = extractTokenIdFromToken(token);
            if (tokenId != null) {
                Claims claims = extractClaimsIgnoreExpiration(token);
                if (claims != null) {
                    long expireTime = claims.getExpiration().getTime();
                    String userId = claims.get("userId", String.class);
                    
                    String refreshTokenId = claims.get("refreshTokenId", String.class);
                    if (refreshTokenId != null) {
                        tokenCacheService.removeRefreshToken(refreshTokenId, userId);
                    } else {
                        // 存量 access token 没有关联 ID，只在过渡期回收该用户全部 refresh token。
                        tokenCacheService.removeAllUserRefreshTokens(userId);
                    }
                    // 加入黑名单
                    tokenCacheService.blacklistToken(tokenId, expireTime);
                    // 从缓存中移除
                    tokenCacheService.removeToken(tokenId, userId);
                    
                    logger.info("Token invalidated: {}", tokenId);
                }
            }
        } catch (Exception e) {
            logger.error("Error invalidating token", e);
            throw e;
        }
    }
    
    /**
     * 使用户所有token失效（批量登出）
     */
    public void invalidateAllUserTokens(String userId) {
        try {
            userRepository.findById(Long.valueOf(userId)).ifPresent(user -> {
                user.setSessionVersion(user.getSessionVersion() + 1);
                userRepository.save(user);
            });
            tokenCacheService.removeAllUserTokens(userId);
            logger.info("All tokens invalidated for user: {}", userId);
        } catch (Exception e) {
            logger.error("Error invalidating all user tokens: {}", userId, e);
        }
    }

    private long tokenVersion(Claims claims) {
        Number value = claims.get("sessionVersion", Number.class);
        return value == null ? 0L : value.longValue();
    }

    private void requireEnabled(User user) {
        if (!user.isEnabled()) {
            throw new com.luky.nexusmind.exception.CustomException(
                    ACCOUNT_DISABLED_MESSAGE, org.springframework.http.HttpStatus.FORBIDDEN, ACCOUNT_DISABLED_CODE);
        }
    }
}
