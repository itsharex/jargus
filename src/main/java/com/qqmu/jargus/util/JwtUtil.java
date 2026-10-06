package com.qqmu.jargus.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类
 */
@Slf4j
@Component
public class JwtUtil {

    private final SecretKey secretKey;
    private final long expireMs;

    public JwtUtil(@Value("${app.jwt-secret:}") String secret,
                   @Value("${app.jwt-expire-hours:24}") int expireHours) {
        if (secret == null || secret.isBlank()) {
            // 未配置密钥：每次启动用随机签名密钥。重启后旧 token 自然全部失效
            // （启动必重新登录），也避免各安装实例共用仓库里公开的默认密钥被伪造 Cookie。
            this.secretKey = Jwts.SIG.HS256.key().build();
            log.info("未配置 app.jwt-secret：本次启动使用随机签名密钥，重启后所有登录态失效、需重新登录");
        } else {
            byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
            // 确保密钥至少 256 位
            if (keyBytes.length < 32) {
                byte[] padded = new byte[32];
                System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
                keyBytes = padded;
            }
            this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        }
        this.expireMs = expireHours * 3600L * 1000L;
    }

    /**
     * 生成 token
     */
    public String generateToken(Long userId, String username, String role) {
        Date now = new Date();
        Date expire = new Date(now.getTime() + expireMs);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expire)
                .signWith(secretKey)
                .compact();
    }

    /**
     * 解析 token（仅类内使用；外部经 validateToken/getUserId 访问）
     */
    private Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            log.debug("JWT 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 验证 token 是否有效
     */
    public boolean validateToken(String token) {
        Claims claims = parseToken(token);
        return claims != null && claims.getExpiration().after(new Date());
    }

    /**
     * 获取用户ID
     */
    public Long getUserId(String token) {
        Claims claims = parseToken(token);
        if (claims == null) return null;
        try {
            return Long.parseLong(claims.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
