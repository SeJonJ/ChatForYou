package webChat.service.monitoring.impl;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CityResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.HandlerInterceptor;
import webChat.controller.ExceptionController;
import webChat.model.log.ClientInfo;
import webChat.service.monitoring.ClientCheckService;
import webChat.service.monitoring.MonitoringService;
import webChat.service.monitoring.PrometheusService;
import webChat.utils.ClientUtils;

import java.io.InputStream;
import java.net.InetAddress;
import java.util.Enumeration;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MonitoringServiceImpl implements MonitoringService,HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(MonitoringServiceImpl.class);

    private final ClientCheckService clientCheckService;
    private final PrometheusService prometheusService;

    // 웹 접속 시 HandlerInterceptor 가 먼저 해당 정보를 인터셉트해와서 정보를 저장
    // prometheus 에 전달한다
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String ipAddress = ClientUtils.getRemoteAddr(request);

        this.printRequestInfo(request);

        if (Boolean.TRUE.equals(clientCheckService.checkIsAllowedIp(ipAddress))){
            return true;
        }

        ClientInfo clientInfo = getClientInfoByAddrs(ipAddress);

        if(Objects.isNull(clientInfo)){
            throw new ExceptionController.AccessForbiddenException("no clientinfo");
        }

        Boolean isBlack = clientCheckService.checkBlackList(clientInfo);

        prometheusService.saveCountInfo("access_client_info", clientInfo);

        if(isBlack){
            // black access 정보만 따로 저장
            prometheusService.saveCountInfo("black_access_info", clientInfo);
            throw new ExceptionController.AccessForbiddenException("black ip");
        }
        return !isBlack;
    }

    @Override
    public ClientInfo getClientInfoByAddrs(String ipAddress) {
        try {
            InetAddress inetAddress = InetAddress.getByName(ipAddress);
//            ClassPathResource countryResource = new ClassPathResource("geodata/GeoLite2-Country.mmdb");
//            ClassPathResource asnResource = new ClassPathResource("geodata/GeoLite2-ASN.mmdb");
            ClassPathResource cityResource = new ClassPathResource("geodata/GeoLite2-City.mmdb");
//            DatabaseReader cityDataBaseReader = new DatabaseReader.Builder(cityResource.getFile()).build();
            DatabaseReader cityDataBaseReader = null;
            try (InputStream cityResourceInputStream = cityResource.getInputStream()) {
                cityDataBaseReader = new DatabaseReader.Builder(cityResourceInputStream).build();
            }

            Optional<CityResponse> client = cityDataBaseReader.tryCity(inetAddress);
            if(client.isPresent()){
                CityResponse info = client.get();

                return ClientInfo.builder()
                        .ipAddr(info.getTraits().getIpAddress())
                        .subnet(info.getTraits().getNetwork().toString())
                        .country(info.getCountry().getNames().get("en"))
                        .countryCode(info.getCountry().getIsoCode())
                        .latitude(info.getLocation().getLatitude())
                        .longitude(info.getLocation().getLongitude())
                        .timeZone(info.getLocation().getTimeZone())
                        .continentCode(info.getContinent().getCode())
                        .build();
            }

            return null;

        } catch (Exception e) {
            throw new ExceptionController.AccessForbiddenException("can not find ipAddrs");
        }
    }

    private void printRequestInfo(HttpServletRequest request) {
        log.info("##########################################");
        log.info("========= 접속자 기본 정보");
        log.info("Remote ipAddrs ::: " + ClientUtils.getRemoteAddr(request));
        log.info("Remote Host ipAddrs ::: " + request.getRemoteHost());

        // 🌟 nginx에서 추가한 새로운 디버깅용 헤더들
        log.debug("========== nginx 디버깅 헤더들 ==========");
        log.debug("X-Original-IP: {}", request.getHeader("X-Original-IP"));           // nginx에서 보는 원본 IP
        log.debug("X-Generated-Forwarded: {}", request.getHeader("X-Generated-Forwarded")); // nginx에서 생성한 X-Forwarded-For
        log.debug("X-Client-IP: {}", request.getHeader("X-Client-IP"));               // 계산된 클라이언트 IP

        // 기존 nginx 헤더들
        log.debug("========== 기존 nginx 헤더들 ==========");
        log.debug("X-Real-IP: {}", request.getHeader("X-Real-IP"));
        log.debug("X-Forwarded-For: {}", request.getHeader("X-Forwarded-For"));
        log.debug("X-Forwarded-Proto: {}", request.getHeader("X-Forwarded-Proto"));
        log.debug("Host: {}", request.getHeader("Host"));

        // 🎯 문제 해결 상태 체크
        log.debug("========== 문제 해결 상태 체크 ==========");
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        String xRealIp = request.getHeader("X-Real-IP");
        String remoteAddr = ClientUtils.getRemoteAddr(request);

        if (xForwardedFor != null && !xForwardedFor.equals("null") && !xForwardedFor.startsWith("10.244.")) {
            log.debug("✅ 성공: X-Forwarded-For에 실제 외부 IP가 전달됨!");
        } else {
            log.debug("❌ 실패: X-Forwarded-For가 여전히 문제 있음");
        }

        if (xRealIp != null && !xRealIp.startsWith("10.244.")) {
            log.debug("✅ 성공: X-Real-IP에 실제 외부 IP가 전달됨!");
        } else {
            log.debug("❌ 실패: X-Real-IP가 여전히 클러스터 내부 IP");
        }

        // 🔍 IP 변화 감지
        log.debug("========== 📊 IP 변화 감지 ==========");
        log.debug("Remote Address: {} | X-Real-IP: {} | X-Forwarded-For: {}",
                remoteAddr, xRealIp, xForwardedFor);

        // 모든 HTTP 헤더 출력 (기존 유지)
        log.debug("========== 📋 모든 HTTP Headers ==========");
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            String headerValue = request.getHeader(headerName);
            log.info("Header: {} = {}", headerName, headerValue);
        }

        log.info("##########################################");
    }
}
