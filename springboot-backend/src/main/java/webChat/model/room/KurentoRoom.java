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

package webChat.model.room;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.SerializedName;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.kurento.client.KurentoClient;
import webChat.model.chat.ChatType;
import webChat.model.game.GameSettingInfo;
import webChat.service.chatroom.participant.KurentoParticipantService;

import javax.annotation.PreDestroy;
import java.io.Closeable;

/**
 * @modifyBy SeJon Jang (wkdtpwhs@gmail.com)
 * @desc 화상채팅을 위한 클래스 ChatRoomDto 를 상속받음
 */
@Getter
@Setter
@NoArgsConstructor
@Slf4j
public class KurentoRoom extends ChatRoom implements Closeable {

  @JsonIgnore
  private KurentoClient kurento;

  @JsonIgnore
  private KurentoParticipantService participantService;

  @SerializedName("game_setting_info")
  @JsonProperty("game_setting_info")
  private GameSettingInfo gameSettingInfo; // 해당 방의 게임 정보 세팅

  @JsonIgnore
  private boolean isKurentoInitialized = false;

  // 룸 정보 set
  public KurentoRoom(String roomId, String roomName, String creator, String roomPwd, boolean secretChk, int userCount, int maxUserCnt, ChatType chatType, String instanceId){
    super(roomId, roomName, creator, userCount, maxUserCnt, roomPwd, secretChk, chatType, RoomState.CREATED, instanceId);
  }

  // Kurento 리소스가 필요한 시점에 초기화
  private void kurentoInitialized() {
    if (!isKurentoInitialized && kurento != null) {
      try {
        this.isKurentoInitialized = true;
        log.info("Kurento 리소스 초기화 완료: {}", this.getRoomId());
      } catch (Exception e) {
        log.error("Kurento 리소스 초기화 실패: {}", this.getRoomId(), e);
      }
    }
  }

  public void activate() {
    if(this.getRoomState() != RoomState.ACTIVE){
      this.setRoomState(RoomState.ACTIVE);
      isKurentoInitialized = true;
    }
  }

  /**
   *  방 상태 변경 : deactive
   */
  public void deactivate() {
    isKurentoInitialized = false;
    this.setRoomState(RoomState.INACTIVE);
  }

  // 유저명 가져오기
  @Override
  public String getRoomId() {
    return super.getRoomId();
  }

  /**
   * @desc 종료시 실행?
   * */
  @JsonIgnore
  @PreDestroy
  private void shutdown() {
    this.close();
  }

  @Override
  public void close() {
    isKurentoInitialized = false;
    this.setRoomState(RoomState.INACTIVE);
    this.kurento = null;
  }
}
