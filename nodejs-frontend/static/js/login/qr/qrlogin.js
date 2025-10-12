const QRLogin = {
    isInit: false,
    pollingCount: 0,
    remainingSeconds: 300,
    isPolling: false,
    isTimer: false,
    sessionId: null,
    timerInterval: null,
    pollingInterval: null,
    $cancelBtn: $('#cancelBtn'),
    $retryBtn: $('#retryBtn'),
    $statusMessage: $('#status-message'),
    $instructions: $('#instructions'),
    $initialSpinner: $('#initial-spinner'),
    $qrCode: $('#qr-code'),
    $qrCodeArea: $('#qr-code-area'),
    $qrCodeImage: $('#qr-code-image'),
    init: function() {
        console.log('[QR Login] 초기화 시작...');
        let self = this;
        self.initClickEvents();
        self.initQRLogin();
    },
    initQRLogin: function() {
        let self = this;
        console.log('[QR Login] 세션 생성 시작...');
        let url = window.__CONFIG__.API_BASE_URL + '/login/qr/create';
        ajax(url, 'GET', true, '', function(result) {
            console.log('[QR Login] 세션 생성 성공:', result);
            self.sessionId = result.data.sessionId;
            
            let qrUrl = result.data.qrUrl;
            let qrImage = result.data.qrImage;

            console.log('[QR Login] 세션 생성 성공:', self.sessionId);
            console.log('[QR Login] QR 데이터:', qrUrl);
            console.log('[QR Login] QR 이미지:', qrImage);

            self.$qrCodeArea.html('');
            new QRCode(self.$qrCodeArea[0], {
                text: qrUrl,
                width: 256,
                height: 256,
                colorDark: "#000000",
                colorLight: "#ffffff",
                correctLevel: QRCode.CorrectLevel.H
            });

            self.updateStatus('모바일로 QR 코드를 스캔하세요', 'pending');
            self.$initialSpinner.hide();
            
            // 폴링 시작
            self.startPolling();
            
            // 타이머 시작
            self.startTimer();
            
        }, function(error) {
            console.error('[QR Login] 세션 생성 실패:', error);

            console.error('[QR Login] 초기화 실패:', error);
            self.updateStatus('오류가 발생했습니다: ' + error.message, 'error');
            self.$initialSpinner.hide();
            self.showRetryButton();
        });

    },
    initClickEvents: function() {
        let self = this;
        if(self.isInit) {
            return;
        }
        self.isInit = true;
        self.$cancelBtn.on('click', function() {
            self.cancelBtnClick();
        });
        self.$retryBtn.on('click', function() {
            self.retryBtnClick();
        });
    },
    /*
    * 폴링 시작
    */
    startPolling : function() {
        let self = this;
        console.log('[QR Login] 폴링 시작...');
        $('#polling-indicator').css('display', 'block');
        self.pollingInterval = setInterval(self.checkStatus, 3000);
    },
    /*
    * 폴링 중지
    */
    stopPolling: function() {
        let self = this;
        if (self.pollingInterval) {
            clearInterval(self.pollingInterval);
            self.pollingInterval = null;
            $('#polling-indicator').css('display', 'none');
            console.log('[QR Login] 폴링 중지');
        }
    },
    /*
    * 상태 확인
    */
    checkStatus: async function() {
        let self = QRLogin;
        self.pollingCount++;
        if (self.pollingCount > 100) {
            self.stopPolling();
            self.updateStatus('시간이 초과되었습니다. 다시 시도해주세요.', 'error');
            self.showRetryButton();
        }

        let url = window.__CONFIG__.API_BASE_URL + '/login/qr/status/' + self.sessionId;
        await ajax(url, 'GET', false, '', function(resp) {
            console.log('[QR Login] 상태 확인 성공:', resp);
            if (resp.result === 'success' && resp.data) {
                const { status, userData } = resp.data;
                
                console.log(`[QR Login] 폴링 ${self.pollingCount}회: status=${status}`);
                
                if (status === 'AUTHENTICATED') {
                    console.log('[QR Login] 인증 완료!', userData);
                    
                    self.stopPolling();
                    self.stopTimer();
                    self.updateStatus('✅ 로그인 성공! 메인 화면으로 이동합니다...', 'success');
                    
                    // userData 저장 (localStorage)
                    if (userData) {
                        localStorage.setItem('access_token', userData.accessToken);
                        localStorage.setItem('refresh_token', userData.refreshToken);
                        localStorage.setItem('email', userData.email);
                        localStorage.setItem('type', userData.type);
                        localStorage.setItem('nickName', userData.nickName);
                        console.log('[QR Login] 토큰 저장 완료');

                        // 팝업만 닫기
                        setTimeout(() => {
                            window.close();
                        }, 500);
                    }
                } else if (status === 'EXPIRED') {
                    console.log('[QR Login] 세션 만료');
                    
                    self.stopPolling();
                    self.stopTimer();
                    self.updateStatus('QR 코드가 만료되었습니다.', 'error');
                    self.showRetryButton();
                }
                // status === 'pending'인 경우는 계속 폴링
            } else {
                console.warn('[QR Login] 상태 확인 실패:', resp.error);
            }
        }, function(error) {
            console.error('[QR Login] 상태 확인 실패:', error);
        });
    },
    /*
    * 상태 업데이트
    */
    updateStatus: function(message, type) {
        let self = this;
        self.$statusMessage.text(message);
        self.$statusMessage.addClass('status-' + type);
    },
    /**
    * 타이머 시작 (5분 카운트다운)
    */
    startTimer: function() {
        let self = this;
        self.timerInterval = setInterval(() => {
            self.remainingSeconds--;
            
            const minutes = Math.floor(self.remainingSeconds / 60);
            const seconds = self.remainingSeconds % 60;
            document.getElementById('timer').textContent = 
                `유효 시간: ${minutes}:${seconds.toString().padStart(2, '0')}`;
            
            if (self.remainingSeconds <= 0) {
                self.stopTimer();
                self.stopPolling();
                self.updateStatus('시간이 초과되었습니다.', 'error');
                self.showRetryButton();
            }
        }, 1000);
    },
    /*
    * 타이머 중지
    */
    stopTimer: function() {
        let self = this;
        if (self.timerInterval) {
            clearInterval(self.timerInterval);
            self.timerInterval = null;
            console.log('[QR Login] 타이머 중지');
        }
    },
    /*
    * 다시 시도 버튼 표시
    */
    showRetryButton: function() {
        let self = this;
        self.$retryBtn.style.display = 'block';
        self.$cancelBtn.textContent = '로그인 화면으로';
        self.$instructions.style.display = 'none';
    },
    /*
    * 취소 버튼 클릭
    */
    cancelBtnClick: function() {
        let self = this;
        self.stopPolling();
        self.stopTimer();
        window.location.href = 'templates/login/chatlogin.html';
    },
    /*
    * 다시 시도 버튼 클릭
    */
    retryBtnClick: function() {
        let self = this;
        self.stopPolling();
        self.stopTimer();
        self.initQRLogin();
    }
}

$(document).ready(function() {
    QRLogin.init();
});