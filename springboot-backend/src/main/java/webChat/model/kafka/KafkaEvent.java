package webChat.model.kafka;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public abstract class KafkaEvent {
    private long publishedAt;

//    TODO 공통화 처리 필요
//    @JsonIgnore
//    public static KafkaServerEvent of(KafkaEvent event){
//        return (KafkaServerEvent) event;
//    }
//    @JsonIgnore
//    public static KafkaRoomEvent of(KafkaEvent event){
//        return (KafkaRoomEvent) event;
//    }
}
