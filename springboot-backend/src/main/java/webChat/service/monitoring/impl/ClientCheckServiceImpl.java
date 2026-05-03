package webChat.service.monitoring.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.model.log.ClientInfo;
import webChat.service.monitoring.ClientCheckService;
import webChat.utils.SubnetUtil;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClientCheckServiceImpl implements ClientCheckService {

    private final String blackListJsonPath = "geodata/firehol_level1.txt";

    @Value("${endpoint.allowed_subnet}")
    private List<String> allowedSubnet;
    @Value("${endpoint.allowed_ip_addresses}")
    private List<String> allowedIpAddresses;

    @PostConstruct
    private void initBlackListJson() {
        this.blackListJson(blackListJsonPath);
    }

    /**
     * 클라이언트 IP 또는 서브넷이 블랙리스트에 포함되는지 확인한다.
     *
     * @param clientInfo 클라이언트 정보
     * @return 블랙리스트 포함 여부
     */
    @Override
    public Boolean checkBlackList(ClientInfo clientInfo) {
        List<String> blackList = blackListJson(blackListJsonPath);
        log.debug("Client blacklist check started: clientInfo={}", clientInfo);
        log.debug("Loaded blacklist entries: count={}", blackList.size());

        boolean isBlack = blackList.stream().anyMatch(black -> {
            return clientInfo.getSubnet().equals(black) || SubnetUtil.isInRange(black, clientInfo.getIpAddr());
        });

        if (isBlack) {
            clientInfo.setBlack(true);
        }
        return isBlack;
    }

    /**
     * 화이트리스트 IP 또는 CIDR 대역 허용 여부를 확인한다.
     *
     * @param ip 확인 대상 IP
     * @return 허용 여부
     */
    @Override
    public Boolean checkIsAllowedIp(String ip) {
        if (allowedIpAddresses.contains(ip)) {
            return true;
        }

        for (String cidr : allowedSubnet) {
            if (SubnetUtil.isInRange(cidr, ip)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 클래스패스의 블랙리스트 파일을 읽어 캐시한다.
     *
     * @param path 블랙리스트 파일 경로
     * @return 블랙리스트 엔트리 목록
     */
    @Cacheable("blackList")
    public List<String> blackListJson(String path) {
        try {
            // classpath 로 blackList txt 파일 가져오기
            ClassPathResource blackList = new ClassPathResource(path);

            log.debug("Loading blacklist resource: uri={}", blackList.getURI());

            try (InputStream inputStream = blackList.getInputStream()) {
                return new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))
                        .lines()
                        .collect(Collectors.toList());
            }

        } catch (Exception e) {
            log.error("블랙리스트 파일 조회 실패: path={}", path, e);
            throw new ChatForYouException(ErrorCode.ROOM_NOT_FOUND, "there is No BlackList file");
        }
    }
}
