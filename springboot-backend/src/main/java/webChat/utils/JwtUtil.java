package webChat.utils;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import java.util.Base64;
import java.util.Date;

@Component
@Slf4j
public class JwtUtil {

    private static final SecretKey SECRET_KEY = Jwts.SIG.HS256.key().build();

    @Value("${jwt.token_secret_key}")
    private String tokenSecretKey;

    /**
     * 토큰 생성
     * @param key Base64로 인코딩된 키
     * @param user 사용자 정보
     * @return JWT 토큰
     */
    public String generateToken(String key, String user) {
        try {
            // Base64로 인코딩된 키들을 디코딩
            String decodedKey = new String(Base64.getDecoder().decode(key));
            String decodedTokenSecretKey = new String(Base64.getDecoder().decode(tokenSecretKey));

            // 디코딩된 값들을 비교
            if (decodedKey.equals(decodedTokenSecretKey)) {
                // 키가 일치하면 토큰 생성
                return Jwts.builder()
                        .subject(user)
                        .setIssuedAt(new Date(System.currentTimeMillis()))
                        .setExpiration(new Date(System.currentTimeMillis() + 1000 * 60 * 60 * 10))
                        .signWith(SECRET_KEY)
                        .compact();
            } else {
                log.error("키가 일치하지 않습니다. 입력된 키: {}, 설정된 키: {}", decodedKey, decodedTokenSecretKey);
                throw new ChatForYouException(ErrorCode.INTERNAL_SERVER_ERROR);
            }
        } catch (IllegalArgumentException e) {
            log.error("Base64 디코딩 실패: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid Base64 encoded key", e);
        }
    }

    /**
     * 토큰 검증
     * @param token JWT 토큰
     * @return 검증 결과 (true/false)
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .setSigningKey(SECRET_KEY)
                    .build()
                    .parseClaimsJws(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.error("JWT 토큰 검증 실패: {}", e.getMessage());
            return false;
        }
    }
}
