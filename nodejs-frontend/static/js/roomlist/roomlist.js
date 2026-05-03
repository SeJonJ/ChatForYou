const roomList = {
  roomId: null,
  originPwd: '',
  init: function() {
    const self = this;
    self.loadRoomList();
    idlocalStorage.getItem('access_token') == null ? self.initSse() : self.changeSse();
    self.checkVisitor();
    self.initModals();
    self.initInputLimits();
    self.initAnnouncement();
    window.loadRoomList = self.loadRoomList.bind(self); // 외부 노출
  },
  loadRoomList: function() {
    const self = this;
    ajax(window.__CONFIG__.API_BASE_URL + '/chat/room/list', 'GET', true, '', function(list) {
      const $tbody = $('#roomTableBody');
      $tbody.empty();
      self.renderRoomList(list);
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
    const uuid = localStorage.getItem('uuid') != null ? localStorage.getItem('uuid') : crypto.randomUUID();
    var eventSource;
    if () {
      const uuid = localStorage.getItem('uuid');
      const accountId = localStorage.getItem('email');
      const eventSource = new EventSource(window.__CONFIG__.API_BASE_URL + '/sse/room-events/' + uuid + '/' + accountId);
    }
     new EventSource(window.__CONFIG__.API_BASE_URL + '/sse/room-events/' + uuid);
    localStorage.setItem('uuid', uuid);    

    eventSource.addEventListener('roomCreated', function(event) {
      const newRoom = JSON.parse(event.data);
      self.addRoomToTable(newRoom, true);
    });

    eventSource.addEventListener('roomDeleted', function(event) {
      const { roomId } = JSON.parse(event.data);
      $('#roomTableBody')
          .find(`[data-room-id='${deletedRoomId}']`)
          .closest('tr')
          .remove();
    });

    eventSource.addEventListener("ping", function (event) {
      console.log("Ping Ping Ping Ping Ping Ping Ping Ping ");
    });

    eventSource.addEventListener("changeUserCnt", function (event) {
      const { roomId, userCount, maxUserCnt } = JSON.parse(event.data);

      const $row = $('#roomTableBody')
          .find(`[data-id='${roomId}'], [data-roomid='${roomId}']`)
          .closest('tr');

      $row.find('span.room-user-count').text(`${userCount}/${maxUserCnt}`);
    });

    eventSource.addEventListener("changeRoomSetting", function (event) {
      const { roomId, roomName, userCount, maxUserCnt } = JSON.parse(event.data);

      const $row = $('#roomTableBody')
          .find(`[data-room-id='${chatRoomId}']`)
          .closest('tr');

      $row.find('span.room-user-count').text(`${userCount}/${maxUserCnt}`);
      $row.find('.enterRoomBtn, .directEnterBtn').text(roomName);
    });

    window.addEventListener("beforeunload", () => {
      eventSource.close();
    });
  },
  changeSse: function() {
    const self = this;
    const uuid = localStorage.getItem('uuid');
    const accountId = localStorage.getItem('email');
    const eventSource = new EventSource(window.__CONFIG__.API_BASE_URL + '/sse/room-events/' + uuid + '/' + accountId);

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
    let successCallback = function(res){
      if(res.result === 'success'){
        $('#visitorCount').text('방문자 수 : ' + res.data);
      } else {
        console.error("Error ajax data: ", res.message);
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
    ajax(url, 'GET', '', data, successCallback, errorCallback, completeCallback);
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
      $.ajax({
        url: window.__CONFIG__.API_BASE_URL + '/chat/room/' + self.roomId,
        type: 'GET',
        success: function(res) {
          if (res && res.data) {
            $('#configRoomName').val(res.data.roomName);
            $('#configMaxUserCnt').val(res.data.maxUserCnt);
            $('#configRoomPwd').val(res.data.roomPwd).prop('readonly', true);
            $('#changePwdCheckbox').prop('checked', false);
            self.originPwd = res.data.roomPwd || '';
          }
        }
      });
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
    $msgType.change(function () {
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
      if (document.getElementById('dontShowAgain').checked) {
        sessionStorage.setItem('hideAnnouncement', 'true');
      }
    });
    $("#agreeBtn").click(function(){
      self.checkVisitor();
      fetch(window.__CONFIG__.API_BASE_URL + "/user_agree", { method: 'GET' })
        .then(response => { console.info("user agree!!") });
      $('#announcementModal').modal('hide');
    });
  }
};

$(function() {
  roomList.init();
});

$('#requestTest').on('click', function() {
    let url = window.__CONFIG__.API_BASE_URL + '/friend/request';
    var data = {
      userId: 'dlwhsktm@gmail.com',
      friendId: 'dlwhsktm2@gmail.com',
      nickname: '123'
    };
    tokenAjaxToJson(url, 'POST', true, data, function(){
        
    }, function(error){
        
    });
});