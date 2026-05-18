package webChat.service.login.impl;

import com.google.firebase.auth.FirebaseToken;
import com.google.zxing.WriterException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.entity.SocialUser;
import webChat.model.login.GoogleOAuth;
import webChat.model.login.OauthRedis;
import webChat.model.login.QRSession;
import webChat.model.login.QRSessionStatus;
import webChat.model.response.common.QRLoginResponse;
import webChat.repository.SocialUserRepository;
import webChat.service.login.LoginService;
import webChat.service.redis.RedisService;
import webChat.utils.QRCodeGenerator;
import webChat.utils.StringUtil;
import webChat.utils.TokenUtils;

import java.io.IOException;
import java.util.Calendar;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LoginServiceImpl implements LoginService {
    private final SocialUserRepository socialUserRepository;
    private final RedisService redisService;
    private static final long QR_SESSION_TTL = 300; // 5분

    @Value("${cookie.check.domain:}")
    private String loginDomain;

    /**
     * Google 로그인 정보를 검증하고 사용자 로그인 세션을 저장한다.
     *
     * @param accessToken Google access token
     * @param refreshToken Google refresh token
     * @param name 사용자 이름
     * @param email 사용자 이메일
     * @param emailVerified 이메일 인증 여부
     * @param photo 프로필 이미지 URL
     * @return 로그인된 사용자 정보
     */
    @Transactional
    @Override
    public GoogleOAuth checkSocialUser(@NonNull String accessToken,
                                       @NonNull String refreshToken,
                                       @NonNull String name,
                                       @NonNull String email,
                                       boolean emailVerified,
                                       String photo) {
        if (!emailVerified) {
            throw new ChatForYouException(ErrorCode.UNAUTHORIZED);
        }

        SocialUser socialUser = socialUserRepository.findByEmail(email);
        FirebaseToken decodeToken = null;
        try {
            decodeToken = TokenUtils.checkGoogleOAuthToken(accessToken);
        } catch (ChatForYouException e) {
            log.error("Google OAuth 토큰 인증 실패: email={}", email, e);
        }

        if(decodeToken == null || !decodeToken.isEmailVerified()){
            throw new ChatForYouException(ErrorCode.UNAUTHORIZED);
        }

        // 계정이 없는 경우
        long time = Calendar.getInstance().getTimeInMillis();
        if (socialUser == null) {
            socialUser = SocialUser.builder().build();
            socialUser.setEmail(email);
            socialUser.setNickname(isDuplicateNickName(email.split("@")[0]));
            socialUser.setPhotoUrl(photo);
            socialUser.setType("google"); // 현재는 Google 로그인만 지원
            socialUser.setCreateDate(time);
            socialUser.setUpdateDate(time);
            socialUser.setLastLoginDate(time);

            socialUserRepository.save(socialUser);
        }

        // 레디스 insert
        OauthRedis oauthRedis = new OauthRedis();
        oauthRedis.setIdx(socialUser.getIdx());
        oauthRedis.setEmail(socialUser.getEmail());
        oauthRedis.setAccessToken(accessToken);
        oauthRedis.setRefreshToken(refreshToken);
        oauthRedis.setNickname(socialUser.getNickname());
        oauthRedis.setLastLoginDate(time);
        redisService.insertGoogleOauthToken(oauthRedis, time);

        return GoogleOAuth.of(accessToken, refreshToken, emailVerified, socialUser);
    }

    /**
     * 중복 닉네임이 있으면 임의 suffix를 붙여 재귀적으로 새 닉네임을 만든다.
     *
     * @param nickname 기본 닉네임
     * @return 중복되지 않는 닉네임
     */
    private String isDuplicateNickName(String nickname){
        if (socialUserRepository.existsByNickname(nickname)) {
            nickname = nickname + "_" + UUID.randomUUID().toString().substring(0, 4);
            return isDuplicateNickName(nickname);
        }
        return nickname;
    }

    /**
     * Redis에 저장된 로그인 정보를 제거한다.
     *
     * @param authorization 인증 헤더
     * @param email 사용자 이메일
     */
    @Override
    public void logout(String authorization, String email) {
        SocialUser user = socialUserRepository.findByEmail(email);
        if (user == null) {
            throw new ChatForYouException(ErrorCode.USER_NOT_FOUND);
        }
        // 레디스에서 삭제
        redisService.deleteLoginInfo(user.getIdx());
    }

    /**
     * 신규 QR 로그인 세션과 QR 이미지를 생성한다.
     *
     * @return QR 로그인 세션 정보
     */
    @Override
    public QRLoginResponse createQRSession() {
        String sessionId = UUID.randomUUID().toString();
        if (StringUtil.isNullOrEmpty(loginDomain)) {
            loginDomain = "http://localhost:3000";
        }

        String qrUrl = loginDomain + "/chatforyou/templates/login/qr/qrscan.html?sessionId=" + sessionId;
        long currentTime = System.currentTimeMillis();
        QRSession qrSession = QRSession.of(sessionId, qrUrl, QRSessionStatus.PENDING, currentTime, QR_SESSION_TTL);
        redisService.insertQRSession(qrSession);

        // QR 코드 이미지 생성
        try {
            byte[] qrImage = QRCodeGenerator.generateQRCode(qrUrl);
            return QRLoginResponse.of(qrSession, qrImage);
        } catch (WriterException | IOException e) {
            log.error("QR 세션 생성 실패: sessionId={}", sessionId, e);
            throw new ChatForYouException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * QR 세션을 인증 완료 상태로 변경하고 로그인 정보를 연결한다.
     *
     * @param sessionId QR 세션 ID
     * @param accessToken Google access token
     * @param refreshToken Google refresh token
     * @param name 사용자 이름
     * @param email 사용자 이메일
     * @param emailVerified 이메일 인증 여부
     * @param photo 프로필 이미지 URL
     * @return 인증 완료된 로그인 정보
     */
    @Override
    @Transactional
    public GoogleOAuth authenticateQRSession(String sessionId, @NonNull String accessToken, @NonNull String refreshToken, @NonNull String name, @NonNull String email, boolean emailVerified, String photo) {
        QRSession qrSession = redisService.getQRSession(sessionId);
        long currentTime = System.currentTimeMillis();
        if (qrSession == null) {
            log.warn("QR 세션이 존재하지 않음: sessionId={}", sessionId);
            throw new ChatForYouException(ErrorCode.QR_SESSION_EXPIRED);
        }

        if (currentTime > qrSession.getAuthenticatedAt()) {
            log.warn("QR 세션이 만료됨: sessionId={}, authenticatedAt={}, currentTime={}",
                    sessionId, qrSession.getAuthenticatedAt(), currentTime);
            qrSession = QRSession.ofUpdateStatus(qrSession, QRSessionStatus.EXPIRED);
            redisService.insertQRSession(qrSession);
            throw new ChatForYouException(ErrorCode.QR_SESSION_EXPIRED);
        }

        // 소셜 유저 체크
        GoogleOAuth auth = this.checkSocialUser(accessToken, refreshToken, name, email, emailVerified, photo);

        // 인증 완료 상태를 다음 폴링 요청에서도 조회할 수 있도록 유지한다.
        qrSession = QRSession.ofUpdateStatusAndAuth(qrSession, QRSessionStatus.AUTHENTICATED, auth);
        redisService.insertQRSession(qrSession);

        return auth;
    }

    /**
     * QR 세션 상태를 조회한다.
     *
     * @param sessionId QR 세션 ID
     * @return 현재 QR 세션 상태
     */
    @Override
    public QRLoginResponse getSessionStatus(String sessionId) {
        QRSession qrSession = redisService.getQRSession(sessionId);
        if (Objects.isNull(qrSession)) {
            log.warn("QR 세션 상태 조회 실패 - 세션 없음: sessionId={}", sessionId);
            throw new ChatForYouException(ErrorCode.QR_SESSION_EXPIRED);
        }

        if (QRSessionStatus.EXPIRED.equals(qrSession.getStatus())) {
            log.warn("QR 세션 상태 조회 실패 - 세션 만료: sessionId={}", sessionId);
            throw new ChatForYouException(ErrorCode.QR_SESSION_EXPIRED);
        }

        return QRLoginResponse.of(qrSession);
    }
}
