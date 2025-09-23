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

        var firebaseapp = firebase.initializeApp({
            projectId: projectId,
            apiKey: apiKey,
            authDomain: authDomain
        });

        var auth = getAuth(firebaseapp);

        var googleAuthProvider = new GoogleAuthProvider();
        googleAuthProvider.setDefaultLanguage('ko');
        googleAuthProvider.setCustomParameters({
            login_hint: 'user@example.com'
        });

        signInWithPopup(auth, googleAuthProvider)
        .then((userCredential) => {
            userCredential.user.getIdToken()
            .then((idToken) => {
                console.log('idToken : ' + idToken);
                var refreshToken = userCredential.user.refreshToken;
                console.log('refresh Token : ' + refreshToken);
            });
        });

        // var url = "https://accounts.google.com/o/oauth2/v2/auth";
        // var width = 500;
        // var height = 600;
        // var left = (window.screen.width / 2) - (width / 2);
        // var top = (window.screen.height / 2) - (height / 2);

        // var clientId = window.__CONFIG__.GOOGLE_OAUTH.CLIENT_ID;
        // var redirectURL = window.__CONFIG__.GOOGLE_OAUTH.REDIRECT_URL;
        // var responseType = window.__CONFIG__.GOOGLE_OAUTH.RESPONSE_TYPE;
        // var scope = window.__CONFIG__.GOOGLE_OAUTH.SCOPE;

        // url += '?client_id=' + clientId + '&redirect_uri=' + redirectURL
        //  + '&response_type=' + responseType + '&scope=' + scope;


        // window.open(
        //     url,
        //     "Google",
        //     `width=${width},height=${height},left=${left},top=${top},resizable=no,scrollbars=no`
        // );
    });


})(jQuery);