/*
 * Copyright 2023 SejonJang (wkdtpwhs@gmail.com)
 *
 * Licensed under the  GNU General Public License v3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package webChat.service.kurento;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kurento.client.Composite;
import org.kurento.client.Continuation;
import org.kurento.client.MediaPipeline;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;
import webChat.model.room.ChatRoom;
import webChat.model.room.KurentoRoom;
import webChat.model.room.in.ChatRoomInVo;
import webChat.repository.kurento.KurentoCompositeMap;
import webChat.repository.kurento.KurentoPipelineMap;
import webChat.service.chatroom.participant.KurentoParticipantService;
import webChat.service.redis.RedisService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Kurento 방(Room) 생명주기 관리 서비스.
 *
 * 참가자 입장·퇴장·교체, 미디어 연결 정리, Composite 녹화 연동, 방 생성·삭제를 담당한다.
 * 각 방은 MediaPipeline 하나를 공유하며 참가자 정보는 KurentoParticipantService를 통해 관리된다.
 *
 * @author SeJon Jang (wkdtpwhs@gmail.com)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KurentoRoomManager {

  private final RedisService redisService;
  private final KurentoParticipantService kurentoParticipantService;
  private final Map<String, MediaPipeline> kurentoPipelineMap = KurentoPipelineMap.getInstance();
  private final KurentoMessageSender kurentoMessageSender;

  /**
   * 사용자를 방에 입장시킨다.
   *
   * 동일 userId가 이미 방에 존재하면 replaceParticipant 경로로 분기하여 기존 세션을 교체한다.
   * 신규 참가자인 경우 참가자 맵에 등록 후 기존 참가자들에게 입장 알림을 전송하고,
   * 녹화 중이면 Composite에 연결한다.
   *
   * @param room     입장할 방 정보
   * @param userId   입장할 사용자 ID
   * @param nickName 입장할 사용자 닉네임
   * @param session  신규 WebSocket 세션
   * @return 생성 또는 교체 결과. replacedExistingParticipant 가 true 면 기존 세션 교체다.
   * @throws IOException WebSocket 메시지 전송 실패 시
   */
  public KurentoJoinResult join(KurentoRoom room, String userId, String nickName, WebSocketSession session) throws IOException {

    log.info("ROOM {}: adding participant {}", room.getRoomId(), userId);

    // UserSession 은 유저명, room명, 유저 세션정보, pipeline 파라미터로 받음
    final KurentoUserSession participant = new KurentoUserSession(userId, nickName, room.getRoomId(), session, kurentoPipelineMap.get(room.getRoomId()));
    final KurentoUserSession existingParticipant = kurentoParticipantService.getParticipant(room.getRoomId(), userId);

    // 동일 userId가 이미 존재하면 교체 경로로 분기한다 (탭 새로고침, 중복 접속 등)
    if (existingParticipant != null) {
      return replaceParticipant(room, existingParticipant, participant);
    }

    // 참여자 map 에 유저명과 유저에 관한 정보를 갖는 userSession 객체를 저장
    kurentoParticipantService.addParticipant(room.getRoomId(), participant);

    this.joinRoom(room, participant);
    connectParticipantToCompositeIfNeeded(room, participant);

    // 참여자 정보를 기존 참여자들에게 알림
    this.sendParticipantNames(room, participant);

    // 참여자 정보 return
    return new KurentoJoinResult(participant, false);
  }

  /**
   * 기존 세션을 새 세션으로 교체하는 8단계 순서를 수행한다.
   *
   * 1단계: 기존 세션에 SESSION_REPLACED 알림 전송 — 구 클라이언트가 스스로 종료하도록 유도.
   * 2단계: 다른 참가자들의 incomingMedia에서 구세션 연결 해제(disconnectPeerIncomingMedia).
   * 3단계: 구세션이 Composite 녹화 중이면 연결 해제(disconnectFromRecordingIfNeeded).
   * 4단계: 참가자 맵을 새 세션으로 교체(addParticipant).
   * 5단계: 구세션 Kurento 자원 해제(closeReplacedParticipant).
   * 6단계: 녹화 중이면 새 세션을 Composite에 연결(connectParticipantToCompositeIfNeeded).
   * 7단계: 다른 참가자들에게 세션 교체 알림 전송(notifyPeerSessionReplaced).
   * 8단계: 신규 참가자에게 기존 참가자 목록 전송(sendParticipantNames).
   *
   * @param room                   대상 방
   * @param existingParticipant    기존(구) 세션
   * @param replacementParticipant 신규 세션
   * @return 교체된 신규 KurentoUserSession 과 교체 여부
   * @throws IOException WebSocket 메시지 전송 실패 시
   */
  private KurentoJoinResult replaceParticipant(KurentoRoom room,
                                               KurentoUserSession existingParticipant,
                                               KurentoUserSession replacementParticipant) throws IOException {
    log.info("ROOM {}: replacing duplicate participant {}", room.getRoomId(), replacementParticipant.getUserId());

    kurentoMessageSender.sendToUser(
        existingParticipant,
        KurentoMessageBuilder.sessionReplaced()
    );

    disconnectPeerIncomingMedia(room.getRoomId(), replacementParticipant.getUserId());
    disconnectFromRecordingIfNeeded(room, existingParticipant);

    kurentoParticipantService.addParticipant(room.getRoomId(), replacementParticipant);
    closeReplacedParticipant(existingParticipant);

    connectParticipantToCompositeIfNeeded(room, replacementParticipant);
    notifyPeerSessionReplaced(room, replacementParticipant);
    this.sendParticipantNames(room, replacementParticipant);

    return new KurentoJoinResult(replacementParticipant, true);
  }

  /**
   * 방에서 사용자를 제거하고 Kurento 자원을 해제한다.
   *
   * 처리 순서: 녹화 중이면 Composite 연결을 먼저 해제한다. 이후 참가자 맵에서 사용자를 제거하고
   * 다른 참가자들에게 퇴장 알림을 전송한다. 마지막으로 KurentoUserSession.close()를 호출하여
   * WebSocket 세션과 미디어 자원을 해제한다.
   *
   * close()는 Kurento RPC(WebSocket flush + MediaPipeline RPC)를 포함하는 블로킹 I/O이므로
   * synchronized 블록 밖(removeParticipant 이후)에서 호출한다. 전역 lock 내부에서 호출하면
   * 다른 방의 참가자 작업이 블록될 수 있다.
   *
   * @param room 퇴장할 방 정보
   * @param user 퇴장할 참가자 세션
   * @throws IOException 참가자 제거 처리 중 오류 발생 시
   */
  public void leave(KurentoRoom room, KurentoUserSession user) throws IOException {
    log.debug("PARTICIPANT {}: Leaving room {}", user.getUserId(), room.getRoomId());

    // 녹화 중이면 Composite 연결 해제
    if (room.isRecordingInProgress() && user.isConnectedToComposite()) {
      try {
        user.disconnectFromComposite();
        log.info("PARTICIPANT {}: Disconnected from Composite before leaving", user.getUserId());
      } catch (Exception e) {
        log.error("PARTICIPANT {}: Failed to disconnect from Composite: {}",
                user.getUserId(), e.getMessage());
      }
    }

    this.removeParticipant(room, user.getUserId());

    // Kurento RPC Blocking I/O를 lock 외부에서 실행하여 다른 스레드 대기 방지
    try {
      user.close();
    } catch (IOException e) {
      log.warn("PARTICIPANT {}: Failed to close Kurento session: {}", user.getUserId(), e.getMessage());
    }
  }

  /**
   * 신규 참가자 입장을 기존 참가자들에게 알리고 기존 참가자 ID 목록을 반환한다.
   *
   * 신규 참가자 자신을 제외한 모든 참가자에게 NEW_PARTICIPANT_ARRIVED 메시지를 전송한다.
   * 알림 실패 시 예외를 기록하되 다른 참가자 알림은 계속 진행한다.
   *
   * @param room           대상 방
   * @param newParticipant 신규 입장한 참가자 세션
   * @return 방에 있는 전체 참가자 ID 컬렉션
   */
  private Collection<String> joinRoom(KurentoRoom room, KurentoUserSession newParticipant) {
    // participants 를 list 형태로 변환 => 이때 list 는 한명의 유저가 새로 들어올 때마다
    // 즉 joinRoom 이 실행될 때마다 새로 생성 && return 됨
    Collection<KurentoUserSession> userSessions = kurentoParticipantService.getParticipantList(room.getRoomId());

    log.debug("ROOM {}: 다른 참여자들에게 새로운 참여자가 들어왔음을 알림 {} :: {}", room.getRoomId(),
            newParticipant.getUserId(), newParticipant.getNickName());

    // participants 의 value 로 for 문 돌림
    for (final KurentoUserSession participant : userSessions) {
      if (participant.getUserId().equals(newParticipant.getUserId())) {
        continue;
      }
      try {
        // 현재 방의 모든 참여자들에게 새로운 참여자가 입장했음을 알림
        kurentoMessageSender.sendToUser(
            participant,
            KurentoMessageBuilder.newParticipantArrived()
                .participantData(newParticipant.getUserId(), newParticipant.getNickName())
        );
      } catch (final Exception e) {
        log.error("ROOM {}: participant {} could not be notified", room.getRoomId(), participant.getUserId(), e);
      }
    }

    // 유저 리스트를 return
    return kurentoParticipantService.getParticipantIds(room.getRoomId());
  }

  /**
   * 참가자 맵 교체 완료 후, 교체 대상 본인을 제외한 모든 peers에게 participantSessionReplaced 알림을 전송한다.
   *
   * 피어들은 이 알림을 받아 구세션으로의 P2P 연결을 종료하고 새 세션과 재협상을 시작해야 한다.
   *
   * @param room                   대상 방
   * @param replacementParticipant 교체된 신규 세션
   */
  private void notifyPeerSessionReplaced(KurentoRoom room, KurentoUserSession replacementParticipant) {
    Collection<KurentoUserSession> userSessions = kurentoParticipantService.getParticipantList(room.getRoomId());

    for (KurentoUserSession participant : userSessions) {
      if (participant.getUserId().equals(replacementParticipant.getUserId())) {
        continue;
      }
      // 교체된 세션의 userId/닉네임을 각 피어에게 전달하여 재연결을 유도한다
      kurentoMessageSender.sendToUser(
          participant,
          KurentoMessageBuilder.participantSessionReplaced()
              .participantData(replacementParticipant.getUserId(), replacementParticipant.getNickName())
      );
    }
  }

  /**
   * 참가자 맵에서 사용자를 제거하고 잔류 참가자들에게 퇴장 알림 및 미디어 연결 정리를 수행한다.
   *
   * 처리 순서: 참가자 맵에서 제거 후 잔류 참가자들의 incomingMedia에서 해당 사용자 엔드포인트를
   * cancelVideoFrom으로 제거하고, PARTICIPANT_LEFT 메시지를 전송한다.
   *
   * @param room 대상 방
   * @param name 제거할 사용자 ID
   * @throws IOException 참가자 맵 조작 중 오류 발생 시
   */
  private void removeParticipant(KurentoRoom room, String name) throws IOException {

    // participants map 에서 제거된 유저 - 방에서 나간 유저 - 를 제거함
    kurentoParticipantService.removeParticipant(room.getRoomId(), name);
    Collection<KurentoUserSession> userSessions = kurentoParticipantService.getParticipantList(room.getRoomId());

    log.debug("ROOM {}: notifying all users that {} is leaving the room", room.getRoomId(), name);

    // String list 생성
    final List<String> unNotifiedParticipants = new ArrayList<>();

    // participants 의 value 로 for 문 돌림
    for (final KurentoUserSession participant : userSessions) {
      try {
        // 나간 유저의 video 를 cancel 하기 위한 메서드
        participant.cancelVideoFrom(name);

        // 다른 유저들에게 현재 유저가 나갔음을 알림
        kurentoMessageSender.sendToUser(
            participant,
            KurentoMessageBuilder.participantLeft()
                .name(name)
        );

      } catch (final Exception e) {
        unNotifiedParticipants.add(participant.getUserId());
      }
    }

    // 만약 unNotifiedParticipants 가 비어있지 않다면
    if (!unNotifiedParticipants.isEmpty()) {
      log.debug("ROOM {}: The users {} could not be notified that {} left the room", room.getRoomId(),
              unNotifiedParticipants, name);
    }

  }

  /**
   * 신규 참가자에게 방에 있는 기존 참가자 목록을 전송한다.
   *
   * 기존 시그널링 규격을 유지하기 위해 참가자 목록을 JsonArray로 구성하여
   * EXISTING_PARTICIPANTS 메시지로 전달한다. 목록에서 자기 자신(user)은 제외된다.
   *
   * @param room 대상 방
   * @param user 기존 참가자 목록을 전달받을 신규 참가자
   * @throws IOException WebSocket 메시지 전송 실패 시
   */
  public void sendParticipantNames(KurentoRoom room, KurentoUserSession user) throws IOException {

    // 기존 시그널링 규격을 유지하기 위해 JsonArray로 참여자 목록을 구성한다.
    final JsonArray participantsArray = new JsonArray();

    // participants 의 value 만 return 받아서 => this.getParticipants() for 문 돌림
    for (final KurentoUserSession participant : kurentoParticipantService.getParticipantList(room.getRoomId())) {
      // 만약 참여자의 정보가 파라미터로 넘어온 user 와 같지 않다면
      if (!participant.getUserId().equals(user.getUserId())) {
        JsonObject exisingUser = new JsonObject();
        exisingUser.addProperty("userId", participant.getUserId());
        exisingUser.addProperty("nickName", participant.getNickName());

        participantsArray.add(exisingUser);
      }
    }

    log.debug("PARTICIPANT {}: sending a list of {} participants", user.getUserId(), participantsArray.size());

    // user 에게 기존 참여자 목록 전달
    kurentoMessageSender.sendToUser(
        user,
        KurentoMessageBuilder.existingParticipants()
            .participantsArray(participantsArray)
    );
  }

  /**
   * 방이 녹화 중인 경우에만 참가자를 Composite에 연결한다.
   *
   * 녹화 중이 아니면 아무 작업도 수행하지 않는다.
   * Composite가 존재하지 않으면 경고 로그를 남기고 반환한다.
   *
   * @param room        대상 방 (녹화 상태 확인용)
   * @param participant Composite에 연결할 참가자 세션
   */
  private void connectParticipantToCompositeIfNeeded(KurentoRoom room, KurentoUserSession participant) {
    if (!room.isRecordingInProgress()) {
      return;
    }

    log.info("ROOM {}: Recording in progress, connecting participant {} to Composite", room.getRoomId(), participant.getUserId());
    try {
      Composite composite = KurentoCompositeMap.getComposite(room.getRoomId());
      if (composite != null) {
        participant.connectToComposite(composite);
        log.info("ROOM {}: Participant {} connected to Composite successfully", room.getRoomId(), participant.getUserId());
      } else {
        log.warn("ROOM {}: Composite not found for recording room", room.getRoomId());
      }
    } catch (Exception e) {
      log.error("ROOM {}: Failed to connect participant {} to Composite: {}", room.getRoomId(), participant.getUserId(), e.getMessage());
    }
  }

  /**
   * 교체 대상 사용자의 incoming media 연결을 모든 피어에서 정리한다.
   *
   * 세션 교체 시 구세션의 WebRtcEndpoint가 이미 무효화될 수 있으므로 각 피어의
   * incomingMedia 맵에서 해당 userId 항목을 cancelVideoFrom으로 제거하고 Kurento 엔드포인트를
   * 비동기 해제한다. 교체 대상 자신은 건너뛴다.
   *
   * @param roomId         대상 방 ID
   * @param replacedUserId 교체될 사용자 ID
   */
  private void disconnectPeerIncomingMedia(String roomId, String replacedUserId) {
    Collection<KurentoUserSession> participants = kurentoParticipantService.getParticipantList(roomId);

    for (KurentoUserSession participant : participants) {
      if (participant.getUserId().equals(replacedUserId)) {
        continue;
      }
      // 피어의 incomingMedia에서 구세션 엔드포인트를 제거하고 비동기 해제한다
      participant.cancelVideoFrom(replacedUserId);
    }
  }

  /**
   * 녹화 중이고 참가자가 Composite에 연결되어 있을 때만 연결을 해제한다.
   *
   * 세션 교체 시 구세션이 Composite에 연결되어 있으면 HubPort를 해제해야
   * Composite 자원 누수를 방지할 수 있다.
   *
   * @param room        대상 방 (녹화 상태 확인용)
   * @param participant Composite 연결을 해제할 참가자 세션
   */
  private void disconnectFromRecordingIfNeeded(KurentoRoom room, KurentoUserSession participant) {
    if (!room.isRecordingInProgress() || !participant.isConnectedToComposite()) {
      return;
    }

    participant.disconnectFromComposite();
  }

  /**
   * 교체된 구세션의 Kurento 미디어 자원을 해제한다.
   *
   * WebSocket 세션, outgoingMedia, incomingMedia, TextOverlay 필터, HubPort 등
   * 구세션에 연결된 모든 Kurento 자원을 해제한다.
   * IOException 발생 시 경고 로그만 남기고 진행을 계속한다.
   *
   * @param participant 자원을 해제할 구세션
   */
  private void closeReplacedParticipant(KurentoUserSession participant) {
    try {
      participant.close();
    } catch (IOException e) {
      log.warn("PARTICIPANT {}: Failed to close replaced Kurento session: {}", participant.getUserId(), e.getMessage());
    }
  }

  /**
   * KurentoRoom 인스턴스를 생성하고 Redis에 저장한다.
   *
   * 방 생성 시점에는 MediaPipeline을 초기화하지 않으며, 첫 번째 참가자 입장 시
   * KurentoHandler.joinRoom에서 파이프라인이 생성된다.
   *
   * @param roomId       생성할 방의 고유 ID
   * @param instanceId   방이 속한 서버 인스턴스 ID
   * @param chatRoomInVo 방 생성 요청 정보 (이름, 비밀번호, 최대 인원 등)
   * @return 생성된 ChatRoom 객체
   */
  public ChatRoom createKurentoRoom(String roomId, String instanceId, ChatRoomInVo chatRoomInVo) {

    KurentoRoom room = new KurentoRoom(roomId, chatRoomInVo.getRoomName(), chatRoomInVo.getCreator(), chatRoomInVo.getRoomPwd(), chatRoomInVo.isSecretChk(), 0, chatRoomInVo.getMaxUserCnt(), chatRoomInVo.getRoomType(), instanceId);

    // redis 에 저장
    redisService.saveChatRoom(room);

    return room;
  }

  /**
   * KurentoRoom을 종료하고 모든 관련 자원을 해제한다.
   *
   * 처리 순서: 방의 모든 참가자에 대해 close()를 호출하여 미디어 자원을 해제한다.
   * MediaPipeline이 유효하면 비동기로 해제한다. KurentoCompositeMap과 kurentoPipelineMap에서
   * 방 항목을 제거한다. 마지막으로 인메모리 참가자 저장소에서 방을 완전히 제거한다.
   *
   * @param kurentoRoom 종료할 방 정보
   */
  public void deleteKurentoRoom(KurentoRoom kurentoRoom) {
    // 방이 close 되었을 때 사용됨
    Collection<KurentoUserSession> userSessions = kurentoParticipantService.getParticipantList(kurentoRoom.getRoomId());

    // participants 의 value 값으로 for 문 시작
    for (final KurentoUserSession user : userSessions) {
      try {
        // 유저 close
        user.close();
      } catch (IOException e) {
        log.debug("ROOM {}: Could not invoke close on participant {}", kurentoRoom.getRoomId(), user.getUserId(), e);
      }
    } // for 문 끝

    MediaPipeline mediaPipeline = kurentoPipelineMap.get(kurentoRoom.getRoomId());

    if(mediaPipeline != null && mediaPipeline.isCommited()) {
      // 미디어 파이프 초기화
      mediaPipeline.release(new Continuation<>() {

          @Override
          public void onSuccess(Void result) {
              log.trace("ROOM {}: Released Pipeline", kurentoRoom.getRoomId());
          }

          @Override
          public void onError(Throwable cause) {
              log.warn("PARTICIPANT {}: Could not release Pipeline", kurentoRoom.getRoomId(), cause);
          }
      });
    }

    // composite 및 pipeline 모두 map 에서 제거
    KurentoCompositeMap.removeComposite(kurentoRoom.getRoomId());
    kurentoPipelineMap.remove(kurentoRoom.getRoomId());

    kurentoParticipantService.removeRoom(kurentoRoom.getRoomId());
    log.debug("Room {} closed", kurentoRoom.getRoomId());

  }

}
