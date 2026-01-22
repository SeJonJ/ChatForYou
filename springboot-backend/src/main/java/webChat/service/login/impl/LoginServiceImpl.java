package webChat.service.login.impl;

import com.google.firebase.auth.FirebaseToken;
import com.google.zxing.WriterException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import webChat.controller.ExceptionController;
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
    public GoogleOAuth checkSocialUser(@NonNull String accessToken,
                                       @NonNull String refreshToken,
                                       @NonNull String name,
                                       @NonNull String email,
                                       boolean emailVerified,
                                       String photo) throws BadRequestException {
        if (!emailVerified) {
            throw new BadRequestException("This email is not verified !!!");
        }

        SocialUser socialUser = socialUserRepository.findByEmail(email);
        FirebaseToken decodeToken = null;
        try {
            decodeToken = new TokenUtils().checkGoogleOAuthToken(accessToken);
//            resultAuth.setEmailVerified(decodeToken.isEmailVerified());
        } catch (Exception e) {
            log.error("google oauth 토큰 인증 실패 !!!");
        }

        if(decodeToken == null || !decodeToken.isEmailVerified()){
            throw new BadRequestException("This account is not verified.");
        }

        // 계정이 없는 경우
        long time = Calendar.getInstance().getTimeInMillis();
        if (socialUser == null) {
            socialUser = SocialUser.builder().build();
            socialUser.setEmail(email);
            socialUser.setNickname(isDuplicateNickName(email.split("@")[0]));
            socialUser.setPhotoUrl(photo);
            socialUser.setType("google"); // TODO 추후 다른 로그인 추가 시 enum 으로 변경??
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

    private String isDuplicateNickName(String nickname){
        if (socialUserRepository.existsByNickname(nickname)) {
            nickname = nickname + "_" + UUID.randomUUID().toString().substring(0, 4);
            return isDuplicateNickName(nickname);
        }
        return nickname;
    }

    @Override
    public void logout(String authorization, String email) throws Exception{
        SocialUser user = socialUserRepository.findByEmail(email);
        if (user == null) {
            throw new BadRequestException("Not exist user !!!");
        }
        // 레디스에서 삭제
        redisService.deleteLoginInfo(user.getIdx());
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

    @Override
    @Transactional
    public GoogleOAuth authenticateQRSession(String sessionId, @NonNull String accessToken, @NonNull String refreshToken, @NonNull String name, @NonNull String email, boolean emailVerified, String photo) throws BadRequestException {
        QRSession qrSession = redisService.getQRSession(sessionId);
        long currentTime = System.currentTimeMillis();
        if (qrSession == null) {
            // TODO 예외처리
        }

        if(currentTime > qrSession.getAuthenticatedAt()){
            // TODO 예외처리
            qrSession = QRSession.ofUpdateStatus(qrSession, QRSessionStatus.EXPIRED);
            redisService.insertQRSession(qrSession);
            throw new ExceptionController.ExpiredQRSession("");
        }

        // 소셜 유저 체크
        GoogleOAuth auth = checkSocialUser(accessToken, refreshToken, name, email, emailVerified, photo);

        // QR session 에 저장
        // TODO 굳이 저장해야하나? 삭제하면 안됨?
        qrSession = QRSession.ofUpdateStatusAndAuth(qrSession, QRSessionStatus.AUTHENTICATED, auth);
        redisService.insertQRSession(qrSession);

        return auth;
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
