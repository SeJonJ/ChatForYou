package webChat.service.login.impl;

import com.google.firebase.auth.FirebaseToken;
import com.google.zxing.WriterException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import webChat.entity.SocialUser;
import webChat.model.login.GoogleOAuth;
import webChat.model.login.OauthRedis;
import webChat.model.login.QRSession;
import webChat.model.login.QRSessionStatus;
import webChat.model.redis.DataType;
import webChat.model.response.common.ChatForYouResponse;
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

    @Transactional
    @Override
    public GoogleOAuth checkSocialUser(GoogleOAuth googleOAuth) {
        if (!googleOAuth.isEmailVerified()) {
            // TODO 예외처리?? :: googleOAuth.isEmailVerified() 값이 정확히 뭔지 모르겠어요ㅠㅠ
        }

        SocialUser socialUser = socialUserRepository.findByEmail(googleOAuth.getEmail());
        FirebaseToken decodeToken = null;
        try {
            decodeToken = new TokenUtils().checkGoogleOAuthToken(googleOAuth.getAccessToken());
//            resultAuth.setEmailVerified(decodeToken.isEmailVerified());
        } catch (Exception e) {
            log.error("google oauth 토큰 인증 실패 !!!");
        }

        if(decodeToken == null || !decodeToken.isEmailVerified()){
            // TODO 예외처리??
            log.error("google decodeToken 토큰 검증 처리 실패 !!!");
            return null;
        }

        // 계정이 없는 경우
        long time = Calendar.getInstance().getTimeInMillis();
        if (socialUser == null) {
            SocialUser user = SocialUser.builder().build();
            user.setEmail(googleOAuth.getEmail());
            user.setNickname(googleOAuth.getName().split("@")[0]);
            user.setPhotoUrl(googleOAuth.getPhoto());
            user.setType("google"); // TODO 추후 다른 로그인 추가 시 enum 으로 변경??
            user.setCreateDate(time);
            user.setUpdateDate(time);
            user.setLastLoginDate(time);

            socialUserRepository.save(user);
        }

        // 레디스 insert
        OauthRedis oauthRedis = new OauthRedis();
        oauthRedis.setEmail(googleOAuth.getEmail());
        oauthRedis.setAccessToken(googleOAuth.getAccessToken());
        oauthRedis.setRefreshToken(googleOAuth.getRefreshToken());
        oauthRedis.setNickname(googleOAuth.getName().split("@")[0]);
        oauthRedis.setLastLoginDate(time);
        redisService.insertGoogleOauthToken(oauthRedis, time);

        return googleOAuth;
    }

    @Override
    public void logout(String authorization, String email) throws Exception{
        FirebaseToken decodeToken = null;

        // 토큰 검증
        try {
            decodeToken = new TokenUtils().checkGoogleOAuthToken(authorization);
        } catch (Exception e) {
            log.error("google oauth 토큰 인증 실패 !!!");
        }

        // 레디스 삭제
        if (!decodeToken.isEmailVerified() || !decodeToken.getEmail().equalsIgnoreCase(email)) {
            throw new BadRequestException("Invalid Logout Info !!! ");
        }
        redisService.deleteLoginInfo(email);
    }

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
        } catch (WriterException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional
    @Override
    public void authenticateSession(String sessionId, GoogleOAuth auth) throws BadRequestException {
        QRSession qrSession = redisService.getQRSession(sessionId);
        long currentTime = System.currentTimeMillis();
        if (qrSession == null) {
            // TODO 예외처리
        }

        if(currentTime > qrSession.getAuthenticatedAt()){
            // TODO 예외처리
            qrSession = QRSession.ofUpdateStatus(qrSession, QRSessionStatus.EXPIRED);
            redisService.insertQRSession(qrSession);
            return;
        }

        this.checkSocialUser(auth);
        qrSession = QRSession.ofUpdateStatusAndAuth(qrSession, QRSessionStatus.AUTHENTICATED, auth);
        redisService.insertQRSession(qrSession);
    }

    @Override
    public QRLoginResponse getSessionStatus(String sessionId) throws BadRequestException {
        QRSession qrSession = redisService.getQRSession(sessionId);
        if (Objects.isNull(qrSession)) {
            // TODO 예외처리
            throw new BadRequestException("Invalid Session ID !!!");
        }

        if(QRSessionStatus.EXPIRED.equals(qrSession.getStatus())){
            // TODO 예외처리
            throw new BadRequestException("Session Expired !!!");
        }

        return QRLoginResponse.of(qrSession);
    }
}
