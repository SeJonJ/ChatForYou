/**
 * 채팅방 설정 및 관리 관련 Popup 기능
 */

const RoomSettingsPopup = {
    roomId: null,
    originPwd: '',

    /**
     * 초기화
     */
    init: function () {
        this.initEvents();
        console.log('RoomSettingsPopup initialized');
    },

    /**
     * 이벤트 바인딩
     */
    initEvents: function () {
        const self = this;
        
        if (localStorage.getItem('access_token') != null) {
            $('#logoutBtn').removeClass('display-none');
            $('#loginBtn').addClass('display-none');
        } else {
            $('#loginBtn').removeClass('display-none');
            $('#logoutBtn').addClass('display-none');
        }

        // 방 설정 모달 버튼 클릭
        $(document).off('click', '.configRoomBtn').on('click', '.configRoomBtn', function () {
            self.roomId = $(this).data('room-id');
            $('#validatePwdModal').modal('show');
        });

        // 비밀번호 확인 모달에서 설정하기 버튼 클릭
        $(document).off('click', '#configRoomBtn').on('click', '#configRoomBtn', function () {
            self.validatePassword($('#validatePwd').val());
        });

        // 방 삭제 버튼 클릭
        $(document).off('click', '#deleteRoomBtn').on('click', '#deleteRoomBtn', function () {
            self.delRoom();
        });

        // 방 수정 저장 버튼 클릭
        $(document).off('click', '#saveRoomConfigBtn').on('click', '#saveRoomConfigBtn', function () {
            self.saveRoomConfig();
        });

        // 로그인 버튼 클릭
        $('#loginBtn').on('click', function() {
            window.location.href = window.__CONFIG__.BASE_URL + '/login/chatlogin.html';
        });

        // 로그아웃 버튼 클릭
        $('#logoutBtn').on('click', function() {
            const url = window.__CONFIG__.API_BASE_URL + '/login/logout';
            const requestData = {
                email: localStorage.getItem('email')
            };

            const successCallback = function() {
                localStorage.removeItem('access_token');
                localStorage.removeItem('refresh_token');
                localStorage.removeItem('email');
                localStorage.removeItem('type');
                localStorage.removeItem('nickname');
                window.location.href = window.__CONFIG__.BASE_URL + '/login/chatlogin.html';
            };
            const errorCallback = function(error) {
                console.error('로그아웃 실패:', error);
            };
            tokenAjax(url, 'POST', true, requestData, successCallback, errorCallback);
        });

        // 비밀번호 변경 체크박스
        $(document).off('change', '#changePwdCheckbox').on('change', '#changePwdCheckbox', function () {
            if ($(this).is(':checked')) {
                $('#configRoomPwd').prop('readonly', false).val('');
            } else {
                $('#configRoomPwd').prop('readonly', true);
            }
        });

        // 방 수정 모달 최대 인원 입력 제한 (2~6)
        $(document).on('input', '#configMaxUserCnt', function () {
            let val = parseInt($(this).val(), 10);
            if (isNaN(val) || val < 2) {
                $(this).val(2);
            } else if (val > 6) {
                $(this).val(6);
            }
        });

        // 비밀번호 확인 입력 시 설정 버튼 활성화/비활성화
        $(document).off('input', '#validatePwd').on('input', '#validatePwd', function () {
            const pwd = $(this).val().trim();
            const $configBtn = $('#configRoomBtn');

            if (pwd.length > 0) {
                $configBtn.removeClass('disabled').attr('aria-disabled', 'false');
            } else {
                $configBtn.addClass('disabled').attr('aria-disabled', 'true');
            }
        });

        // 비밀번호 확인 모달이 열릴 때 버튼 초기화
        $(document).off('shown.bs.modal', '#validatePwdModal').on('shown.bs.modal', '#validatePwdModal', function () {
            $('#validatePwd').val('');
            $('#configRoomBtn').addClass('disabled').attr('aria-disabled', 'true');
        });
    },

    /**
     * 방 설정 모달에 현재 방 정보를 채운다.
     * @returns {void}
     */
    loadRoomInfo: function () {
        const self = this;

        const successCallback = function (response) {
            const { data } = response || {};
            if (data) {

                // 방 정보를 설정 폼에 기본값으로 설정
                self.originPwd = data.roomPwd;
                $('#configRoomName').val(data.roomName);
                $('#configMaxUserCnt').val(data.maxUserCnt);
                $('#configRoomPwd').val(data.roomPwd);

                // 비밀번호 변경 체크박스 초기화
                $('#changePwdCheckbox').prop('checked', false);
                $('#configRoomPwd').prop('readonly', true);

                $('#validatePwdModal').modal('hide');
                $('#roomConfigModal').modal('show');

            } else {
                self.showToast('방 정보를 가져올 수 없습니다.', 'error');
            }
        };

        const errorCallback = function (error) {
            if (isAuthRequiredErrorCode(error.responseJSON?.code)) {
                self.showToast(getApiErrorMessage(error.responseJSON, '로그인이 필요한 서비스입니다.'));
                redirectToLogin();
            } else {
                self.showToast(getApiErrorMessage(error.responseJSON, '방 정보 로딩 중 오류가 발생했습니다.'), 'error');
            }        
        };

        const url = window.__CONFIG__.API_BASE_URL + '/chat/room/' + self.roomId;
        tokenAjax(url, 'GET', true, '', successCallback, errorCallback);
    },

    /**
     * 설정 권한 확인용 방 비밀번호를 검증한다.
     * @param {string} password
     * @returns {void}
     */
    validatePassword: function (password) {
        const self = this;

        const successCallback = function (response) {
            const { result, data } = response || {};
            // 비밀번호 검증은 표준 응답의 SUCCESS + token data 조합으로 판단한다.
            if (result === 'SUCCESS' && data) {
                const roomToken = data.token;
                if (roomToken) {
                    sessionStorage.setItem('roomAccessToken', roomToken);
                }
                self.loadRoomInfo();
            } else {
                self.showToast('비밀번호가 일치하지 않습니다.', 'error');
            }
        };

        const errorCallback = function (error) {
            self.showToast(getApiErrorMessage(error.responseJSON, '비밀번호 확인 중 오류가 발생했습니다.'), 'error');
            $('#configRoomBtn').addClass('disabled').attr('aria-disabled', 'true');
        };

        const url = window.__CONFIG__.API_BASE_URL + '/chat/room/validatePwd/' + self.roomId;
        const requestData = {
            roomPwd: password
        };
        tokenAjax(url, 'POST', true, requestData, successCallback, errorCallback);
    },

    /**
     * 방 설정 저장
     */
    saveRoomConfig: function () {
        const self = this;
        const roomName = $('#configRoomName').val();
        const maxUserCnt = $('#configMaxUserCnt').val();
        const isChangePwd = $('#changePwdCheckbox').is(':checked');
        const newPwd = isChangePwd ? $('#configRoomPwd').val() : self.originPwd;
        const $btn = $('#saveRoomConfigBtn');

        if (!roomName) {
            self.showToast('방 이름을 입력해주세요.', 'error');
            return;
        }

        if (maxUserCnt < 2 || maxUserCnt > 6) {
            self.showToast('최대 인원은 2~6명까지 설정 가능합니다.', 'error');
            return;
        }

        if (isChangePwd && !newPwd) {
            self.showToast('새 비밀번호를 입력해주세요.', 'error');
            return;
        }

        // 로딩 UI 적용
        $btn.prop('disabled', true);
        const originalText = $btn.html();
        $btn.html('<span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span> 저장 중...');

        const successCallback = function (result) {
            self.showToast('방 설정이 저장되었습니다.', 'success');
            $('#roomConfigModal').modal('hide');
            // 로딩 UI 원복
            $btn.prop('disabled', false);
            $btn.html(originalText);
            setTimeout(function () {
                location.reload();
            }, 500);
        };

        const errorCallback = function (error) {
            self.showToast(getApiErrorMessage(error.responseJSON, '방 설정 저장 중 오류가 발생했습니다.'), 'error');
            console.error(error?.responseJSON?.message, 'error');
            // 로딩 UI 원복
            $btn.prop('disabled', false);
            $btn.html(originalText);
        };

        const url = window.__CONFIG__.API_BASE_URL + '/chat/room/' + self.roomId;
        const requestData = {
            roomName: roomName,
            maxUserCnt: parseInt(maxUserCnt),
            roomPwd: newPwd
        };
        tokenAjaxToJson(url, 'PUT', true, requestData, successCallback, errorCallback);
    },

    /**
     * 방 삭제
     */
    delRoom: function () {
        const self = this;
        
        if (!confirm('정말 방을 삭제하시겠습니까? 이 작업은 되돌릴 수 없습니다.')) {
            return;
        }

        const successCallback = function (result) {
            self.showToast('방이 삭제되었습니다.', 'success');
            $('#roomConfigModal').modal('hide');

            // 전체 페이지 새로고침
            setTimeout(function () {
                location.reload();
            }, 500);
        };

        const errorCallback = function (error) {
            self.showToast(getApiErrorMessage(error.responseJSON, '방 삭제 중 오류가 발생했습니다.'), 'error');
        };

        const url = window.__CONFIG__.API_BASE_URL + '/chat/room/' + self.roomId;
        tokenAjax(url, 'DELETE', true, '', successCallback, errorCallback);
    },

    /**
     * 토스트 메시지 표시
     */
    showToast: function (message, type) {
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
$(document).ready(function () {
    if (typeof RoomSettingsPopup !== 'undefined') {
        RoomSettingsPopup.init();
        window.RoomSettingsPopup = RoomSettingsPopup;
    }
});
