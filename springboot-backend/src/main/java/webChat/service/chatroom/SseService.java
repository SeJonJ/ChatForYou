package webChat.service.chatroom;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import webChat.entity.SocialUser;
import webChat.model.noti.NotiRedis;
import webChat.model.room.ChatRoom;
import webChat.repository.SocialUserRepository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class SseService {
    @Autowired
    private SocialUserRepository socialUserRepository;

    // 동시성 관련하여 CopyOnWriteArrayList
    private final List<Map<String, SseEmitter>> emitters = new CopyOnWriteArrayList<>();

    // 클라이언트가 연결 요청할 때 Emitter 생성
    public SseEmitter createEmitter(String idx) {
        SseEmitter emitter = new SseEmitter(60_000L * 15);

        Map<String, SseEmitter> emitterMap = new ConcurrentHashMap<>();
        emitterMap.put(idx, emitter);
        // 리스트에 추가
        emitters.add(emitterMap);

        // 연결 종료 시 자동 제거
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));

        return emitter;
    }

    // 로그인 후 해당 유저 Emitter 변경
    public void changeEmitter(String idx, String accountId) {
        SocialUser user = socialUserRepository.findByEmail(accountId);
        String targetIdx = String.valueOf(user.getIdx());

        for (Map<String, SseEmitter> emitterMap : emitters) {
            for (Map.Entry<String, SseEmitter> entry : emitterMap.entrySet()) {
                String id = entry.getKey();
                SseEmitter emitter = entry.getValue();

                // 프론트에서 받은 uuid와 현재 sse의 idx값 비교
                // 비교 후 해당 sse 삭제, user idx sse 추가
                if (idx.equals(id)) {
                    Map<String, SseEmitter> newEmitter = new ConcurrentHashMap<>();
                    newEmitter.put(targetIdx, emitter);
                    emitters.add(newEmitter);
                    emitters.remove(entry);
                    break;
                }
            }
        }
    }

    // 방 생성 시, 연결된 모든 클라이언트에게 이벤트 전송
    public void sendRoomCreatedEvent(ChatRoom room) {
        List<Map<String, SseEmitter>> deadEmitters = new ArrayList<>();

        for (Map<String, SseEmitter> emitterMap : emitters) {
            for (Map.Entry<String, SseEmitter> entry : emitterMap.entrySet()) {
                SseEmitter emitter = entry.getValue();

                try {
                    emitter.send(SseEmitter.event()
                            .name("roomCreated")
                            .data(room));
                } catch (IOException e) {
                    deadEmitters.add(emitterMap);
                }
            }
        }

//        for (SseEmitter emitter : emitters) {
//            try {
//                emitter.send(SseEmitter.event()
//                        .name("roomCreated")
//                        .data(room));
//            } catch (IOException e) {
//                deadEmitters.add(emitter);
//            }
//        }

        emitters.removeAll(deadEmitters);
    }

    // 방 삭제 시 이벤트 전송
    public void sendRoomDeletedEvent(ChatRoom room) {
        List<Map<String, SseEmitter>> deadEmitters = new ArrayList<>();

        for (Map<String, SseEmitter> emitterMap : emitters) {
            for (Map.Entry<String, SseEmitter> entry : emitterMap.entrySet()) {
                SseEmitter emitter = entry.getValue();

                try {
                    emitter.send(SseEmitter.event()
                            .name("roomDeleted")
                            .data(room));
                } catch (IOException e) {
                    deadEmitters.add(emitterMap);
                }
            }
        }
//        for (SseEmitter emitter : emitters) {
//            try {
//                emitter.send(SseEmitter.event()
//                        .name("roomDeleted")
//                        .data(room));
//            } catch (IOException e) {
//                deadEmitters.add(emitter);
//            }
//        }

        emitters.removeAll(deadEmitters);
    }


    // 핑 관련 이벤트 전송
    @Scheduled(fixedDelay = 10_000L * 7) // 7분
    public void sendPingToClients() {
        List<Map<String, SseEmitter>> deadEmitters = new ArrayList<>();

        for (Map<String, SseEmitter> emitterMap : emitters) {
            for (Map.Entry<String, SseEmitter> entry : emitterMap.entrySet()) {
                SseEmitter emitter = entry.getValue();

                try {
                    emitter.send(SseEmitter.event()
                            .name("ping")
                            .data("keep-alive"));
                } catch (IOException e) {
                    deadEmitters.add(emitterMap);
                }
            }
        }

//        for (SseEmitter emitter : emitters) {
//            try {
//                emitter.send(SseEmitter.event()
//                        .name("ping")
//                        .data("keep-alive"));
//            } catch (IOException e) {
//                deadEmitters.add(emitter);
//            }
//        }
        emitters.removeAll(deadEmitters);
    }

    // 방 인원수 변경 시 이벤트 전송
    public void sendRoomUserCntEvent(ChatRoom room) {
        List<Map<String, SseEmitter>> deadEmitters = new ArrayList<>();

        for (Map<String, SseEmitter> emitterMap : emitters) {
            for (Map.Entry<String, SseEmitter> entry : emitterMap.entrySet()) {
                SseEmitter emitter = entry.getValue();

                try {
                    emitter.send(SseEmitter.event()
                            .name("changeUserCnt")
                            .data(room));
                } catch (IOException e) {
                    deadEmitters.add(emitterMap);
                }
            }
        }

//        for (SseEmitter emitter : emitters) {
//            try {
//                emitter.send(SseEmitter.event()
//                        .name("changeUserCnt")
//                        .data(room));
//            } catch (IOException e) {
//                deadEmitters.add(emitter);
//            }
//        }
        emitters.removeAll(deadEmitters);
    }

    public void sendChangeRoomSettingEvent(ChatRoom room) {
        List<Map<String, SseEmitter>> deadEmitters = new ArrayList<>();

        for (Map<String, SseEmitter> emitterMap : emitters) {
            for (Map.Entry<String, SseEmitter> entry : emitterMap.entrySet()) {
                SseEmitter emitter = entry.getValue();

                try {
                    emitter.send(SseEmitter.event()
                            .name("changeRoomSetting")
                            .data(room));
                } catch (IOException e) {
                    deadEmitters.add(emitterMap);
                }
            }
        }

//        for (SseEmitter emitter : emitters) {
//            try {
//                emitter.send(SseEmitter.event()
//                        .name("changeRoomSetting")
//                        .data(room));
//            } catch (IOException e) {
//                deadEmitters.add(emitter);
//            }
//        }
        emitters.removeAll(deadEmitters);
    }

    // 친구 요청 전송 (( redis 요청 목록을 파라미터로 받아서 리턴 필요)
    public void sendFriendRequestEvent(SocialUser user, List<NotiRedis> friend) {
        List<Map<String, SseEmitter>> deadEmitters = new ArrayList<>();

        for (Map<String, SseEmitter> emitterMap : emitters) {
            for (Map.Entry<String, SseEmitter> entry : emitterMap.entrySet()) {
                SseEmitter emitter = entry.getValue();
                String emitterIdx = entry.getKey();

                if (emitterIdx.equals(String.valueOf(friend.getIdx()))) {
                    try {
                        emitter.send(SseEmitter.event()
                                .name("requestFriends")
                                .data(user)); // 친구 요청 보낸 사람의 데이터 리턴
                    } catch (IOException e) {
                        deadEmitters.add(emitterMap);
                    }
                }
            }
        }
        emitters.removeAll(deadEmitters);
    }

    // 친구 요청 승인
    public void sendFriendAcceptEvent() {

    }

    // 친구 요청 거절
    public void sendFriendRejectEvent() {

    }
}
