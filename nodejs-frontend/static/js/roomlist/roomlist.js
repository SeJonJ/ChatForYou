const roomList = {
  roomId: null,
  originPwd: '',
  init: function() {
    const self = this;
    self.loadRoomList();
    self.initSse();
    self.checkVisitor();
    self.initModals();
    self.initInputLimits();
    self.initAnnouncement();
    window.loadRoomList = self.loadRoomList.bind(self); // 외부 노출
  },
  loadRoomList: function() {
    const self = this;
    ajax(window.__CONFIG__.API_BASE_URL + '/chat/room/list', 'GET', true, '', function(response) {
      const { data } = response || {};
      const $tbody = $('#roomTableBody');
      $tbody.empty();
      self.renderRoomList(data || []);
    }, function(err) {
      $('#roomTableBody').html('<tr><td colspan="5">방 목록을 불러오지 못했습니다.</td></tr>');
    });
  },
  renderRoomList: function(list) {
    const $tbody = $('#roomTableBody');
    list.forEach(function(room) {
      roomList.addRoomToTable(room, false);
    });
  },

  addRoomToTable: function(room, isPrepend) {
    const isSecret = room.secretChk;
    const roomType = room.chatType === 'MSG' ? '일반 채팅' : '화상 채팅';
    const lockIcon = isSecret ? '🔒︎' : '';
    const btnSetting = `<button class='btn btn-primary btn-sm configRoomBtn' data-room-id='${room.roomId}'>채팅방 설정</button>`;
    const roomNameHtml = isSecret
        ? `<a href="#enterRoomModal" data-bs-toggle="modal" class="enterRoomBtn" id="showPasswordModal" data-room-id="${room.roomId}">${room.roomName}</a>`
        : `<a href="#" class="directEnterBtn" id="directEnterBtn" data-room-id="${room.roomId}">${room.roomName}</a>`;

    if ($('#roomTableBody').find(`[data-room-id='${room.roomId}']`).length === 0) {
      const html = `
      <tr>
        <td>${roomNameHtml}</td>
        <td>${lockIcon}</td>
        <td><span class="room-user-count badge bg-primary rounded-pill">${room.userCount}/${room.maxUserCnt}</span></td>
        <td>${roomType}</td>
        <td>${btnSetting}</td>
      </tr>
    `;

      if(isPrepend) {
        $('#roomTableBody').prepend(html);
      } else {
        $('#roomTableBody').append(html);
      }
    }
  },
  initSse: function() {
    const self = this;
    const eventSource = new EventSource(window.__CONFIG__.API_BASE_URL + '/sse/room-events');
    let hasShownConnectionError = false;

    function parseEventData(event, eventName) {
      try {
        hasShownConnectionError = false;
        return JSON.parse(event.data);
      } catch (error) {
        console.error(`[RoomList][SSE] ${eventName} JSON parse failed:`, error, event.data);
        showApiErrorToast('실시간 방 목록 데이터 처리 중 오류가 발생했습니다. 새로고침 후 다시 시도해주세요.');
        return null;
      }
    }

    eventSource.addEventListener('roomCreated', function(event) {
      const newRoom = parseEventData(event, 'roomCreated');
      if (!newRoom) {
        return;
      }
      self.addRoomToTable(newRoom, true);
    });

    eventSource.addEventListener('roomDeleted', function(event) {
      const deletedRoom = parseEventData(event, 'roomDeleted');
      if (!deletedRoom) {
        return;
      }
      const { roomId } = deletedRoom;
      $('#roomTableBody')
          .find(`[data-room-id='${roomId}']`)
          .closest('tr')
          .remove();
    });

    eventSource.addEventListener("ping", function (event) {
      console.log("Ping Ping Ping Ping Ping Ping Ping Ping ");
    });

    eventSource.addEventListener("changeUserCnt", function (event) {
      const userCountEvent = parseEventData(event, 'changeUserCnt');
      if (!userCountEvent) {
        return;
      }
      const { roomId, userCount, maxUserCnt } = userCountEvent;

      const $row = $('#roomTableBody')
          .find(`[data-room-id='${roomId}']`)
          .closest('tr');

      $row.find('span.room-user-count').text(`${userCount}/${maxUserCnt}`);
    });

    eventSource.addEventListener("changeRoomSetting", function (event) {
      const roomSettingEvent = parseEventData(event, 'changeRoomSetting');
      if (!roomSettingEvent) {
        return;
      }
      const { roomId, roomName, userCount, maxUserCnt } = roomSettingEvent;

      const $row = $('#roomTableBody')
          .find(`[data-room-id='${roomId}']`)
          .closest('tr');

      $row.find('span.room-user-count').text(`${userCount}/${maxUserCnt}`);
      $row.find('.enterRoomBtn, .directEnterBtn').text(roomName);
    });

    eventSource.onerror = function(error) {
      console.error('[RoomList][SSE] room-events connection error:', error);
      if (hasShownConnectionError) {
        return;
      }
      hasShownConnectionError = true;
      showApiErrorToast('실시간 방 목록 연결이 일시적으로 불안정합니다. 브라우저가 자동으로 다시 연결을 시도합니다.');
    };

    window.addEventListener("beforeunload", () => {
      eventSource.close();
    });
  },
  numberChk: function() {
    let check = /^[0-9]+$/;
    if (!check.test($("#modalMaxUserCnt").val())) {
      Toastify({
        text: '채팅 인원에는 숫자만 입력 가능합니다!',
        duration: 2000,
        gravity: 'top',
        position: 'center',
        backgroundColor: '#fa5252',
        close: true
      }).showToast();
      return false;
    }
    return true;
  },
  // 방문자 수 조회
  checkVisitor: function() {
    let url = window.__CONFIG__.API_BASE_URL + "/visitor";
    let data = {
      "isVisitedToday": sessionStorage.getItem("isVisitedToday") === 'true' ? 'true' : 'false'
    };
    let successCallback = function(response){
      const { result, data: visitorCount, message } = response || {};
      // 방문자 수 응답은 표준 wrapper의 SUCCESS + data 값으로 처리한다.
      if(result === 'SUCCESS'){
        $('#visitorCount').text('방문자 수 : ' + visitorCount);
      } else {
        console.error("Error ajax data: ", message);
      }
    };
    let errorCallback = function(error){
      console.error("Error ajax data: ", error);
    };
    let completeCallback = function (result) {
      if (!sessionStorage.getItem('isVisitedToday') || sessionStorage.getItem('isVisitedToday') === false) {
        sessionStorage.setItem('isVisitedToday', 'true');
      }
    };
    ajax(url, 'GET', true, data, successCallback, errorCallback, completeCallback);
  },
  initModals: function() {
    const self = this;
    // 모달창 열릴 때 이벤트 처리 => roomId 가져오기
    $("#enterRoomModal").on("show.bs.modal", function (event) {
      self.roomId = $(event.relatedTarget).data('room-id');
    });
    // 방 설정 모달 열릴 때 roomId 세팅 보강 및 기존 비밀번호 저장
    $(document).on('show.bs.modal', '#validatePwdModal', function (e) {
      let roomId = $(e.relatedTarget).data('room-id');
      if (roomId) {
        self.roomId = roomId;
      }
    });
    // roomConfigModal 열릴 때 현재 방 정보로 input 초기화 및 기존 비밀번호 저장
    $('#roomConfigModal').on('show.bs.modal', function () {
      if (!self.roomId) return;
      tokenAjax(window.__CONFIG__.API_BASE_URL + '/chat/room/' + self.roomId, 'GET', true, '', function(response) {
          const { data } = response || {};
          if (data) {
            $('#configRoomName').val(data.roomName);
            $('#configMaxUserCnt').val(data.maxUserCnt);
            $('#configRoomPwd').val(data.roomPwd).prop('readonly', true);
            $('#changePwdCheckbox').prop('checked', false);
            self.originPwd = data.roomPwd || '';
          }
        }, function(error) {
          console.error('방 정보 로딩 실패:', error);
          if (isAuthRequiredErrorCode(error?.responseJSON?.code)) {
            showWarningToast(getApiErrorMessage(error?.responseJSON, '로그인이 필요한 서비스입니다.'));
            redirectToLogin();
          } else if (isInvalidRoomAccessErrorCode(error?.responseJSON?.code)
                  || error?.responseJSON?.code === 'R001') {
            sessionStorage.removeItem('roomAccessToken');
            showWarningToast(getApiErrorMessage(error?.responseJSON, '방 입장 정보가 만료되었습니다. 다시 확인해주세요.'));
          } else {
            showWarningToast(getApiErrorMessage(error?.responseJSON, '방 정보를 불러오지 못했습니다.'));
          }
        }, null, { roomId: self.roomId }
      );
    });
    // 비밀번호 확인 모달 닫힐 때 입력값 및 안내 초기화
    $('#validatePwdModal').on('hidden.bs.modal', function () {
      $('#validatePwd').val('');
      $('#confirmLabel').text('비밀번호 확인');
      $('#confirm').remove();
    });
  },
  initInputLimits: function() {
    // 문자 채팅 누를 시 disabled 풀림
    let $maxUserCnt = $("#modalMaxUserCnt");
    let $msgType = $("#modalMsgType");
    $msgType.on('change', function () {
      if ($msgType.is(':checked')) {
        $maxUserCnt.attr('disabled', false);
      }
    });
  },
  initAnnouncement: function() {
    const self = this;
    if (!sessionStorage.getItem('hideAnnouncement') || sessionStorage.getItem('hideAnnouncement') === 'false') {
      $('#announcementModal').modal('show');
    } else {
      $('#announcementModal').modal('hide');
    }
    $('#announcementModal').on('hide.bs.modal', function (event) {
      const dontShowAgainEl = document.getElementById('dontShowAgain');
      if (dontShowAgainEl && dontShowAgainEl.checked) {
        sessionStorage.setItem('hideAnnouncement', 'true');
      }
    });
    // 이용약관 동의는 방문자 수 갱신과 별도 API 호출이 함께 일어나므로 한 곳에서 처리한다.
    $("#agreeBtn").on('click', function(){
      self.checkVisitor();
      fetchJson(window.__CONFIG__.API_BASE_URL + "/user_agree", { method: 'GET' }, '이용약관 동의 처리에 실패했습니다.')
        .then(() => { console.info("user agree!!") })
        .catch(error => { console.error("Error in user agree API:", error); });
      $('#announcementModal').modal('hide');
    });
  }
};

$(function() {
  roomList.init();
});
