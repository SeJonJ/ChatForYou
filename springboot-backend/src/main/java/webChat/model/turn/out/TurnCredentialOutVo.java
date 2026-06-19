package webChat.model.turn.out;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class TurnCredentialOutVo {

    private List<String> urls;

    // coturn use-auth-secret 포맷: "만료EpochSec:userId". coturn 이 동일 secret 으로 재계산해 검증한다.
    private String username;

    private String credential;

    private long ttl;

    private long peerReconnectTimeoutMs;
}
