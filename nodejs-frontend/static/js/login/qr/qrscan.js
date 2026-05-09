const QRScan = {
    isInit: false,
    sessionId: null,
    $statusMessage: null,
    init: function() {
        console.log('[QR Scan] 초기화 시작...');
        let self = this;
        self.$statusMessage = $('#status-message');
        self.initSessionId();
        self.initFirebase();
    },
    initSessionId: function() {
        // URL에서 sessionId 추출
        let self = this;
        let urlParams = new URLSearchParams(window.location.search);
        self.sessionId = urlParams.get('sessionId');
        console.log('QR 스캔 페이지 로드, sessionId:', self.sessionId);
    },
    initFirebase: function() {
        let self = this;
        // Firebase 초기화
        if (!firebase.apps.length) {
            firebase.initializeApp({
                projectId: window.__CONFIG__.GOOGLE_OAUTH.PROJECT_ID,
                apiKey: window.__CONFIG__.GOOGLE_OAUTH.API_KEY,
                authDomain: window.__CONFIG__.GOOGLE_OAUTH.AUTH_DOMAIN
            });
        } else {
            firebase.app();
        }

         // Firebase UI 설정
         const ui = new firebaseui.auth.AuthUI(firebase.auth());
        
         ui.start('#firebaseui-auth-container', {
             signInOptions: [
                 {
                     provider: firebase.auth.GoogleAuthProvider.PROVIDER_ID,
                     customParameters: {
                         // 항상 계정 선택 화면 표시
                         prompt: 'select_account'
                     }
                 }
             ],
            callbacks: {
                 signInSuccessWithAuthResult: (authResult) => {
                     console.log('Firebase 로그인 성공:', authResult.user.email);
                     const {user} = authResult;
                     self.authenticateSession(user);
                     return false;
                 },
                 signInFailure: (error) => {
                     console.error('Firebase 로그인 실패:', error);
                     self.showError('로그인에 실패했습니다. 다시 시도해주세요.');
                 }
             },
             signInFlow: 'popup', // 팝업 방식 (모바일 친화적)
             tosUrl: window.__CONFIG__.BASE_URL + '/login/terms.html', // 약관 URL (선택사항)
             privacyPolicyUrl: window.__CONFIG__.BASE_URL + '/login/privacy.html' // 개인정보 처리방침 URL (선택사항)
         });
    },
    authenticateSession: function(user) {
        let self = this;
        self.showSpinner(true);
        console.log('백엔드 인증 시작...');
        // Firebase 토큰 가져오기
        user.getIdToken().then((idToken) => {
            console.log('idToken : ' + idToken);

            // 폼 데이터 생성
            const requestData = {
                sessionId: self.sessionId,
                accessToken: idToken,
                refreshToken: user.refreshToken,
                name: user.displayName,
                email: user.email,
                emailVerified: user.emailVerified,
                photo: user.photoURL || ''
            };


            console.log('백엔드 API 호출:', {
                sessionId: self.sessionId,
                email: user.email
            });

            // 백엔드 API 호출
            ajax(window.__CONFIG__.API_BASE_URL + '/login/qr/authenticate', 'POST', true, requestData, function(response) {
                const { result, data } = response || {};
                console.log('백엔드 인증 성공:', response);
                if (result === 'SUCCESS' && data) {
                    console.log('백엔드 인증 성공');
                    self.showSpinner(false);

                    // Firebase 로그아웃
                    setTimeout(() => {
                        firebase.auth().signOut();
                        self.showSuccess('✅ 로그인 성공! \n이 창을 닫으셔도 됩니다.');
                        // Firebase UI 및 안내 문구 숨김
                        $('#firebaseui-auth-container').hide();
                        $('#instructions').hide();
                    }, 2000);
                } else {
                    console.error('백엔드 인증 실패:', response);
                    self.showError('인증 실패: ' + (response?.message || '알 수 없는 오류'));
                }
            }, function(error) {
                console.error('백엔드 인증 실패:', error);
                self.showError(getApiErrorMessage(error?.responseJSON, '인증 중 오류가 발생했습니다.\n네트워크 연결을 확인하세요.'));
            }, function() {
                self.showSpinner(false);
            });

            return false; // 리다이렉트 방지
        }).catch(function(error) {
            // Firebase ID 토큰 조회 실패 시 스피너 해제 및 오류 표시
            console.error('[QR] Firebase ID 토큰 조회 실패:', error);
            self.showSpinner(false);
            self.showError('Google 로그인 토큰을 확인하지 못했습니다. 다시 시도해주세요.');
        });
        
    },
    showSpinner: function(show) {
        $('#loading-spinner').css('display', show ? 'block' : 'none');
            
        // Firebase UI 컨테이너 숨김/표시
        const authContainer = $('#firebaseui-auth-container');
        if (authContainer) {
            authContainer.css('display', show ? 'none' : 'block');
        }
    },
    showSuccess: function(message) {
        this.$statusMessage.text(message);
        this.$statusMessage.addClass('status-success');
        this.$statusMessage.css('display', 'block');
    },
    showError: function(message) {
        this.$statusMessage.text(message);
        this.$statusMessage.addClass('status-error');
        this.$statusMessage.css('display', 'block');
    }
}

$(document).ready(function() {
    QRScan.init();
});
