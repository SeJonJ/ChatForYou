// const { initializeApp } = require("firebase/app");
//import { initializeApp } from "https://www.gstatic.com/firebasejs/11.10.0/firebase-app.js";

(function ($) {
    "use strict";


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
    var input = $('.validate-input .input100');

    $('.validate-form').on('submit',function(){
        var check = true;

        for(var i=0; i<input.length; i++) {
            if(validate(input[i]) == false){
                showValidate(input[i]);
                check=false;
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
        if($(input).attr('type') == 'email' || $(input).attr('name') == 'email') {
            if($(input).val().trim().match(/^([a-zA-Z0-9_\-\.]+)@((\[[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.)|(([a-zA-Z0-9\-]+\.)+))([a-zA-Z]{1,5}|[0-9]{1,3})(\]?)$/) == null) {
                return false;
            }
        }
        else {
            if($(input).val().trim() == ''){
                return false;
            }
        }
    }

    function showValidate(input) {
        var thisAlert = $(input).parent();

        $(thisAlert).addClass('alert-validate');
    }

    function hideValidate(input) {
        var thisAlert = $(input).parent();

        $(thisAlert).removeClass('alert-validate');
    }
    
    /*==================================================================
    [ Show pass ]*/
    var showPass = 0;
    $('.btn-show-pass').on('click', function(){
        if(showPass == 0) {
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

    $('#googleOauth').on('click', function(e) {

        if (!firebase.apps.length) {
            firebase.initializeApp({
                projectId: window.__CONFIG__.GOOGLE_OAUTH.PROJECT_ID,
                apiKey: window.__CONFIG__.GOOGLE_OAUTH.API_KEY,
                authDomain: window.__CONFIG__.GOOGLE_OAUTH.AUTH_DOMAIN
            });
        } else {
            firebase.app();
        }

        var auth = firebase.auth();
        var provider = new firebase.auth.GoogleAuthProvider();
        auth.signInWithPopup(provider)
            .then(function (result) {
                var user = result.user;
                console.log('user : ' + user);
                user.getIdToken().then((idToken) => {
                    console.log('idToken : ' + idToken);
                    var refreshToken = user.refreshToken;
                    console.log('refresh Token : ' + refreshToken);

                    var requestData = {};
                    // requestData setting
                    requestData.accessToken = idToken;
                    requestData.refreshToken = refreshToken;
                    requestData.name = user.displayName;
                    requestData.email = user.email;
                    requestData.emailVerified = user.emailVerified;
                    requestData.photo = user.photoURL;

                    var successCallback = function(result) {
                        if (result.data.emailVerified) {
                            localStorage.setItem('access_token', result.data.accessToken);
                            localStorage.setItem('refresh_token', result.data.refreshToken);
                            localStorage.setItem('email', result.data.email);
                            localStorage.setItem('type', result.data.type);
                            localStorage.setItem('nickname', result.data.email.split('@')[0]);
                            window.location.href = '/';
                        } else {
                            alert('로그인에 실패하였습니다 !!!');
                        }
                    };
                    var errorCallback = function(error) {
                        console.error(error);
                    };
                    const url = window.__CONFIG__.API_BASE_URL + '/login/googleOauth';
                    ajax(url, 'POST', true, requestData, successCallback, errorCallback);
                })
            });
    });

    $('#qrLoginBtn').on('click', function() {
        window.open(window.__CONFIG__.BASE_URL + '/templates/login/qr/qrlogin.html');
    });

    // Electron 환경 감지 함수
    function isElectron() {
        return window.navigator.userAgent.toLowerCase().indexOf('electron') > -1 ||
               (window.process && window.process.versions && window.process.versions.electron) ||
               (window.require && window.require('electron')) ||
               window.__dirname !== undefined;
    }

    // storage 이벤트 리스너 추가
    window.addEventListener('storage', function(e) {
        if (e.key === 'access_token' && e.newValue) {
            console.log('[Login] QR 로그인 완료 감지, roomlist로 이동');
            
            // Electron 환경과 일반 웹 환경 구분
            if (isElectron()) {
                console.log('[Login] Electron 환경 감지');
                window.location.href = 'templates/roomlist.html';
            } else {
                console.log('[Login] 일반 웹 환경 감지');
                window.location.href = '/templates/roomlist.html';
            }
        }
    });

})(jQuery);