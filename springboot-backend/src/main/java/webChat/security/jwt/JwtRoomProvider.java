package webChat.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.security.jwt.core.JwtCoreProvider;
import webChat.utils.StringUtil;

import java.nio.charset.StandardCharsets;
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
        try {
            if (StringUtil.isNullOrEmpty(token)) {
                throw new ChatForYouException(ErrorCode.INVALID_ROOM_ACCESS);
            }
            Claims claims = jwtCore.parse(token);

            if (!JwtTokenType.ROOM_ACCESS.name().equals(claims.get("type"))) {
                throw new ChatForYouException(ErrorCode.INVALID_ROOM_ACCESS);
            }

            String tokenRoomId = claims.get("roomId", String.class);
            if (!roomId.equals(tokenRoomId)) {
                throw new ChatForYouException(ErrorCode.INVALID_ROOM_ACCESS);
            }
        } catch (ChatForYouException e) {
            throw e;
        } catch (Exception e) {
            throw new ChatForYouException(ErrorCode.INVALID_ROOM_ACCESS);
        }
    }
}
