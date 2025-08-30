package webChat.model.kafka;

import lombok.Getter;

// 키는 파티셔닝 전략에 맞게 명명
public class KafkaSendKey {
    public static final String EVENT_TYPE = "event-type";
}
