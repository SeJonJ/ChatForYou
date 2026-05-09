package webChat.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.security.jwt.core.JwtCoreProvider;
import webChat.utils.StringUtil;

import java.security.Key;
import java.util.HashMap;
import java.util.Map;

@Component
public class JwtRoomProvider {
    private final long EXPIRE_MS;
    private final JwtCoreProvider jwtCore;

    public JwtRoomProvider(
            @Qualifier("roomJwtKey") Key key,
            @Value("${jwt.room.expire-ms:1800000}") long expireMs
    ) {
        this.jwtCore = new JwtCoreProvider(key);
        this.EXPIRE_MS = expireMs;
    }

    public String create(String roomId, String userId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("roomId", roomId);
        claims.put("type", JwtTokenType.ROOM_ACCESS);

        return jwtCore.create(userId, claims, EXPIRE_MS);
    }

    public void validate(String token, String roomId) {
        validate(token, roomId, null);
    }

    public void validate(String token, String roomId, String userId) {
        try {
            Claims claims = parseRequiredClaims(token);
            validateClaims(claims, roomId, userId);
        } catch (ExpiredJwtException e) {
            throw new ChatForYouException(ErrorCode.ROOM_TOKEN_EXPIRED);
        } catch (ChatForYouException e) {
            throw e;
        } catch (Exception e) {
            throw new ChatForYouException(ErrorCode.INVALID_ROOM_ACCESS);
        }
    }

    public void validateRefreshable(String token, String roomId, String userId) {
        try {
            Claims claims;
            try {
                claims = parseRequiredClaims(token);
            } catch (ExpiredJwtException e) {
                claims = e.getClaims();
            }

            validateClaims(claims, roomId, userId);
        } catch (ChatForYouException e) {
            throw e;
        } catch (Exception e) {
            throw new ChatForYouException(ErrorCode.INVALID_ROOM_ACCESS);
        }
    }

    private Claims parseRequiredClaims(String token) {
        if (StringUtil.isNullOrEmpty(token)) {
            throw new ChatForYouException(ErrorCode.INVALID_ROOM_ACCESS);
        }

        return jwtCore.parse(token);
    }

    private void validateClaims(Claims claims, String roomId, String userId) {
        if (claims == null) {
            throw new ChatForYouException(ErrorCode.INVALID_ROOM_ACCESS);
        }

        if (!JwtTokenType.ROOM_ACCESS.name().equals(claims.get("type"))) {
            throw new ChatForYouException(ErrorCode.INVALID_ROOM_ACCESS);
        }

        String tokenRoomId = claims.get("roomId", String.class);
        if (!roomId.equals(tokenRoomId)) {
            throw new ChatForYouException(ErrorCode.INVALID_ROOM_ACCESS);
        }

        if (!StringUtil.isNullOrEmpty(userId) && !userId.equals(claims.getSubject())) {
            throw new ChatForYouException(ErrorCode.INVALID_ROOM_ACCESS);
        }
    }
}
