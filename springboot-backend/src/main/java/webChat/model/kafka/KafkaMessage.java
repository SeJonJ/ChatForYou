package webChat.model.kafka;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class KafkaMessage {
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Record {
        public String key;
        public Object value;
    }

    public List<Record> records = new ArrayList<>();

    public KafkaMessage addRecord(String key, Object value) {
        records.add(new Record(key, value));
        return this;
    }
}
