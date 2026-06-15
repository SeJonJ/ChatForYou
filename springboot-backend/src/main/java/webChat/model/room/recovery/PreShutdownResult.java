package webChat.model.room.recovery;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class PreShutdownResult {
    private String instanceId;
    private int markedRoomCount;
    private List<String> roomIds;
}
