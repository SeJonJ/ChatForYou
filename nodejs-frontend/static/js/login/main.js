// const { initializeApp } = require("firebase/app");
//import { initializeApp } from "https://www.gstatic.com/firebasejs/11.10.0/firebase-app.js";

(function ($) {
    "use strict";
    let isIdTokenSyncRegistered = false;


    /*==================================================================
    [ Focus input ]*/
    $('.input100').each(function(){
        $(this).on('blur', function(){
            if($(this).val().trim() != "") {
                $(this).addClass('has-val');
            }
            else {
                $(this).removeClass('has-val');
            }
        })    
    })
  
  
    /*==================================================================
    [ Validate ]*/
    const input = $('.validate-input .input100');

    $('.validate-form').on('submit',function(){
        let check = true;

        for (let i = 0; i < input.length; i++) {
            if (validate(input[i]) === false) {
                showValidate(input[i]);
                check = false;
            }
        }

        return check;
    });


    $('.validate-form .input100').each(function(){
        $(this).focus(function(){
           hideValidate(this);
        });
    });

    function validate (input) {
        if ($(input).attr('type') === 'email' || $(input).attr('name') === 'email') {
            if ($(input).val().trim().match(/^([a-zA-Z0-9_\-\.]+)@((\[[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.)|(([a-zA-Z0-9\-]+\.)+))([a-zA-Z]{1,5}|[0-9]{1,3})(\]?)$/) == null) {
                return false;
            }
        }
        else {
            if ($(input).val().trim() === '') {
                return false;
            }
        }
    }

    function showValidate(input) {
        const thisAlert = $(input).parent();

        $(thisAlert).addClass('alert-validate');
    }

    function hideValidate(input) {
        const thisAlert = $(input).parent();

        $(thisAlert).removeClass('alert-validate');
    }
    
    /*==================================================================
    [ Show pass ]*/
    let showPass = 0;
    $('.btn-show-pass').on('click', function(){
        if (showPass === 0) {
            $(this).next('input').attr('type','text');
            $(this).addClass('active');
            showPass = 1;
        }
        else {
            $(this).next('input').attr('type','password');
            $(this).removeClass('active');
            showPass = 0;
        }
        
    });

    $('#loginBtn').on('click', function() {
        alert('해당 기능은 미구현입니다.');
    });

    function initializeFirebaseAppIfNeeded() {
        if (!firebase.apps.length) {
            firebase.initializeApp({
                projectId: window.__CONFIG__.GOOGLE_OAUTH.PROJECT_ID,
                apiKey: window.__CONFIG__.GOOGLE_OAUTH.API_KEY,
                authDomain: window.__CONFIG__.GOOGLE_OAUTH.AUTH_DOMAIN
            });
        } else {
            firebase.app();
        }
    }

    function registerIdTokenSync(auth) {
        if (isIdTokenSyncRegistered) {
            return;
        }

        auth.onIdTokenChanged(function(user) {
            if (!user) {
                localStorage.removeItem('access_token');
                return;
            }

            user.getIdToken()
                .then(function(idToken) {
                    localStorage.setItem('access_token', idToken);
                })
                .catch(function(error) {
                    console.error('[Login] ID token sync failed:', error);
                });
        });
        isIdTokenSyncRegistered = true;
    }

    $('#googleOauth').on('click', function(e) {
        initializeFirebaseAppIfNeeded();
        const auth = firebase.auth();
        const provider = new firebase.auth.GoogleAuthProvider();
        registerIdTokenSync(auth);
        auth.signInWithPopup(provider)
            .then(function (result) {
                const user = result.user;
                console.log('user : ' + user);
                user.getIdToken().then((idToken) => {
                    console.log('idToken : ' + idToken);
                    const refreshToken = user.refreshToken;
                    console.log('refresh Token : ' + refreshToken);

                    const requestData = {
                        accessToken: idToken,
                        refreshToken: refreshToken,
                        name: user.displayName,
                        email: user.email,
                        emailVerified: user.emailVerified,
                        photo: user.photoURL
                    };

                    const successCallback = function(response) {
                        const { data } = response || {};
                        if (data?.emailVerified) {
                            localStorage.setItem('access_token', data.accessToken);
                            localStorage.setItem('refresh_token', data.refreshToken);
                            localStorage.setItem('email', data.email);
                            localStorage.setItem('type', data.type);
                            localStorage.setItem('nickname', data.email.split('@')[0]);
                            window.location.href = window.__CONFIG__.BASE_URL + '/';
                        } else {
                            showApiErrorToast('로그인에 실패하였습니다.');
                        }
                    };
                    const errorCallback = function(error) {
                        console.error(error);
                        handleApiError(error);
                    };
                    const url = window.__CONFIG__.API_BASE_URL + '/login/googleOauth';
                    ajax(url, 'POST', true, requestData, successCallback, errorCallback);
                }).catch(function(error) {
                    console.error('[Login] Firebase ID 토큰 조회 실패:', error);
                    showApiErrorToast('Google 로그인 토큰을 확인하지 못했습니다. 다시 시도해주세요.');
                });
            })
            .catch(function(error) {
                console.error('[Login] Google OAuth 팝업 로그인 실패:', error);
                showApiErrorToast('Google 로그인 중 오류가 발생했습니다. 다시 시도해주세요.');
            });
    });

    $('#qrLoginBtn').on('click', function() {
        window.open(window.__CONFIG__.BASE_URL + '/templates/login/qr/qrlogin.html');
    });

    // Electron 환경에서 소셜 로그인 버튼 숨기기
    $(document).ready(function() {
        if (isElectron()) {
            console.log('[Login] Electron 환경 감지 - 소셜 로그인 버튼 숨기기');
            $('#googleOauth').hide();
            $('#naverOauth').hide();
            
            // 소셜 로그인 섹션 전체가 비어있으면 숨기기
            const socialSection = $('.login-form__social');
            if (socialSection.children(':visible').length === 0) {
                socialSection.hide();
            }
        }
    });

    // storage 이벤트 리스너 추가
    window.addEventListener('storage', function(e) {
        if (e.key === 'access_token' && e.newValue) {
            console.log('[Login] QR 로그인 완료 감지, roomlist로 이동');
            
            // Electron 환경과 일반 웹 환경 구분
            if (isElectron()) {
                console.log('[Login] Electron 환경 감지');
                window.location.href = window.__CONFIG__.BASE_URL + '/templates/roomlist.html';
            } else {
                console.log('[Login] 일반 웹 환경 감지');
                window.location.href = window.__CONFIG__.BASE_URL + '/';
            }
        }
    });

})(jQuery);
