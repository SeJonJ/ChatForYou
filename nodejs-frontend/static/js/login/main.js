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
        var projectId = window.__CONFIG__.GOOGLE_OAUTH.PROJECT_ID;
        var apiKey = window.__CONFIG__.GOOGLE_OAUTH.API_KEY;
        var authDomain = window.__CONFIG__.GOOGLE_OAUTH.AUTH_DOMAIN;

        var requestData = {};

        if (!firebase.apps.length) {
            firebase.initializeApp({
                projectId: projectId,
                apiKey: apiKey,
                authDomain: authDomain
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
                user.getIdToken()
                .then((idToken) => {
                    console.log('idToken : ' + idToken);
                    var refreshToken = user.refreshToken;
                    console.log('refresh Token : ' + refreshToken);

                    // requestData setting
                    requestData.accessToken = idToken;
                    requestData.refreshToken = refreshToken;
                    requestData.name = user.displayName;
                    requestData.email = user.email;
                    requestData.emailVerified = user.emailVerified;
                    requestData.photo = user.photoURL;

                    var successCallback = function(data) {
                        if (data.data.emailVerified) {
                            localStorage.setItem('access_token', data.data.accessToken);
                            localStorage.setItem('refresh_token', data.data.refreshToken);
                            localStorage.setItem('email', data.data.email);
                            localStorage.setItem('type', data.data.type);
                            localStorage.setItem('nickname', data.data.email.split('@')[0]);
                            window.location.href = '/';
                        } else {
                            alert('로그인에 실패하였습니다 !!!');
                        }
                    };
                    var errorCallback = function(error) {

                    };
                    const url = window.__CONFIG__.API_BASE_URL + '/login/googleOauth';
                    ajax(url, 'POST', true, requestData, successCallback, errorCallback);
                })
            });
    });


})(jQuery);