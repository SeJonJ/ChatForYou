/**
 * 채팅방 생성 및 입장 관련 Popup 기능
 */

const RoomPopup = {
    roomId: null,

    /**
     * 초기화
     */
    init: function() {
        this.bindEvents();
        console.log('RoomPopup initialized');
    },

    /**
     * 이벤트 바인딩
     */
    bindEvents: function() {
        const self = this;

        // 비밀방 패스워드 모달
        $(document).off('click', '#showPasswordModal').on('click', '#showPasswordModal', function(e) {
            e.preventDefault();
            self.roomId = $(this).data('room-id');
            $('#enterRoomModal').modal('show');
        });

        // 비밀방 입장 이벤트
        $(document).off('click', '#enterRoomBtn').on('click', '#enterRoomBtn', function(e) {
            e.preventDefault();
            self.enterSecretRoom();
        });

        // 일반방 입장
        $(document).off('click', '#directEnterBtn').on('click', '#directEnterBtn', function(e) {
            e.preventDefault();
            self.roomId = $(this).data('room-id');
            self.enterRoom();
        });

        // 방 생성 폼 제출
        $('#modalCreateRoomForm').off('submit').on('submit', function(e) {
            e.preventDefault();
            self.handleCreateRoom();
        });

        // 방 생성 모달 닫힐 때 input 값 초기화
        $('#roomModal').on('hidden.bs.modal', function () {
            $('#modalRoomName').val('');
            $('#modalRoomPwd').val('');
            $('#modalMaxUserCnt').val('2');
        });
    },    /**
     * 추가 이벤트 바인딩
     */
    bindAdditionalEvents: function() {
        // 방 생성 최대 인원 입력 제한 (2~6)
        $(document).on('input', '#modalMaxUserCnt', function() {
            let val = parseInt($(this).val(), 10);
            if (isNaN(val) || val < 2) {
                $(this).val(2);
            } else if (val > 6) {
                $(this).val(6);
            }
        });

        // 비밀번호 입력란 눈 아이콘 토글
        $(document).on('click', '#roomModal .input-group-text', function() {
            const $input = $(this).siblings('input[data-toggle="password"]');
            const $icon = $(this).find('i');
            if ($input.attr('type') === 'password') {
                $input.attr('type', 'text');
                $icon.removeClass('fa-eye').addClass('fa-eye-slash');
            } else {
                $input.attr('type', 'password');
                $icon.removeClass('fa-eye-slash').addClass('fa-eye');
            }
        });
    },

    /**
     * 방 생성 처리
     */
    handleCreateRoom: function() {
        const self = this;
        const $btn = $('#modalCreateRoomBtn');
        let originalText = $btn.html();
        if (self.createRoom()) {
            setCookie('room-id', '', -1); // 쿠키 삭제

            // 로딩 UI 적용
            $btn.prop('disabled', true);
            $btn.html('<span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span> 생성 중...');
            const requestData = {
                roomName: $('#modalRoomName').val(),
                roomPwd: $('#modalRoomPwd').val(),
                secretChk: $('#modalSecret').is(':checked'),
                maxUserCnt: $('#modalMaxUserCnt').val(),
                roomType: $('input[name="modalChatType"]:checked').val()
            };

            let successCallback = function(res) {
                self.showToast('방 생성이 완료되었습니다!', 'success');
                $('#roomModal').modal('hide');
                // 로딩 UI 원복
                $btn.prop('disabled', false);
                $btn.html(originalText);
                // 전체 페이지 새로고침
                location.reload();
            };

            let errorCallback = function(err) {
                if(err.responseJSON?.code === '40042'){
                    self.showToast('이미 존재하는 방입니다. \n 다른 방 이름을 입력해주세요.');
                } else if (['40050', '40051', '40052'].includes(err.responseJSON?.code)) {
                    self.showToast('로그인이 필요한 서비스입니다.');
                    window.location.href = window.__CONFIG__.BASE_URL + '/login/chatlogin.html';
                } else {
                    self.showToast('방 생성에 실패했습니다. \n 잠시뒤에 다시 시도해주세요.');
                }
                console.error(err.responseText, 'error');
                // 로딩 UI 원복
                $btn.prop('disabled', false);
                $btn.html(originalText);
            };

            const url = window.__CONFIG__.API_BASE_URL + '/chat/room';
            tokenAjaxToJson(url, 'POST', true, requestData, successCallback, errorCallback);
        }
    },    
    /**
     * 방 생성 유효성 검사
     */
    createRoom: function() {
        const self = this;
        $('#loadingIndicator').show();
        $('#roomConfigBtn').hide();
        
        function resetEvent() {
            $('#loadingIndicator').hide();
            $('#roomConfigBtn').show();
        }

        let name = $("#modalRoomName").val();
        let pwd = $("#modalRoomPwd").val();
        let secret = $("#modalSecret").is(':checked');
        let $chatType = $('input[name="modalChatType"]:checked').val();
        let $maxUserCnt = $("#modalMaxUserCnt").val();

        if (name === "") {
            self.showToast('방 이름은 필수입니다', 'error');
            resetEvent();
            return false;
        }

        if ($("#" + name).length > 0) {
            self.showToast('이미 존재하는 방입니다', 'error');
            resetEvent();
            return false;
        }

        if (pwd === "") {
            self.showToast('비밀번호는 필수입니다', 'error');
            resetEvent();
            return false;
        }

        if ($chatType === null || $chatType === undefined) {
            self.showToast('채팅 타입은 필수입니다', 'error');
            resetEvent();
            return false;
        }

        if ($maxUserCnt <= 1) {
            self.showToast('채팅은 최소 2명 이상이어야 합니다!', 'error');
            resetEvent();
            return false;
        }

        return true;
    },    /**
     * 방 입장 처리 (비밀방)
     */
    enterSecretRoom: function() {
        const self = this;
        const pwd = $('#enterPwd').val();
        
        if (!pwd) {
            self.showToast('비밀번호를 입력해주세요.', 'error');
            return;
        }

        let successCallback = function(result) {
            if (result?.data?.isValidate === true) {
                var roomToken = result.data.token;
                sessionStorage.setItem('roomAccessToken', roomToken);
                self.showToast('방에 정상적으로 입장했습니다!', 'success');
                $('#enterRoomModal').modal('hide');

                let url = window.__CONFIG__.BASE_URL + '/room/kurentoroom.html?roomId=' + self.roomId;
                tokenAjax(url, 'GET', true, '', function(){
                    console.log('tokenCheck');
                    location.href = url;
                }, function(error){
                    if (error?.responseJSON && ['40050', '40051', '40052'].includes(error.responseJSON.code)) {
                        self.showToast('로그인이 필요한 서비스입니다.');
                        window.location.href = window.__CONFIG__.BASE_URL + '/login/chatlogin.html';
                    }
                });
                
            } else {
                self.showToast('비밀번호가 일치하지 않습니다.', 'error');
            }
        };

        let errorCallback = function(error) {
            if (error?.responseJSON && ['40050', '40051', '40052'].includes(error.responseJSON.code)) {
                self.showToast('로그인이 필요한 서비스입니다.');
                window.location.href = window.__CONFIG__.BASE_URL + '/login/chatlogin.html';
            } else {
                console.error(error.responseJSON.message, 'error');
                self.showToast('비밀번호 확인 중 오류가 발생했습니다.', 'error');
            } 
        };

        const url = window.__CONFIG__.API_BASE_URL + '/chat/room/validatePwd/' + self.roomId;
        const requestData = { roomPwd: pwd };
        tokenAjax(url, 'POST', true, requestData, successCallback, errorCallback);
    },

    /**
     * 방 인원 수 체크 후 입장 (일반방)
     */
    enterRoom: function() {
        const self = this;
        
        let successCallback = function(result) {
            if (result?.data && result?.result === 'success') {
                self.showToast('방에 정상적으로 입장했습니다!', 'success');

                let url = window.__CONFIG__.BASE_URL + '/room/kurentoroom.html?roomId=' + self.roomId;
                tokenAjax(url, 'GET', true, '', function(){
                    console.log('tokenCheck');
                    location.href = url;
                }, function(error){
                    if (error?.responseJSON && ['40050', '40051', '40052'].includes(error.responseJSON.code)) {
                        self.showToast('로그인이 필요한 서비스입니다.');
                        window.location.href = window.__CONFIG__.BASE_URL + '/login/chatlogin.html';
                    }
                });
            } else {
                self.showToast('현재는 방에 입장 할 수 없습니다.');
            }
        };

        let errorCallback = function(error) {
            if (error.responseJSON && error.responseJSON.message) {
                self.showToast(error.responseJSON.message, 'error');
            } else {
                self.showToast('방 입장 중 오류가 발생했습니다.', 'error');
            }
        };

        const url = window.__CONFIG__.API_BASE_URL + '/chat/room/chkUserCnt/' + self.roomId;
        tokenAjax(url, 'GET', true, '', successCallback, errorCallback);
    },    /**
     * 토스트 메시지 표시
     */
    showToast: function(message, type) {
        const backgroundColor = type === 'success' ? '#51cf66' : '#fa5252';
        Toastify({
            text: message,
            duration: 2500,
            gravity: 'top',
            position: 'center',
            backgroundColor: backgroundColor,
            close: true
        }).showToast();
    }
};

// 초기화 함수 호출 및 전역 노출
$(document).ready(function() {
    if (typeof RoomPopup !== 'undefined') {
        RoomPopup.init();
        RoomPopup.bindAdditionalEvents();
        window.RoomPopup = RoomPopup;
    }
});