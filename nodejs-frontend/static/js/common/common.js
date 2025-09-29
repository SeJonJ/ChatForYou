function isMobile(){
    return /iPhone|iPad|iPod|Android/i.test(navigator.userAgent);
}

let setCookie = function(name, value, exp) {
	let date = new Date();
	date.setTime(date.getTime() + exp*24*60*60*1000);
	document.cookie = name + '=' + value + ';expires=' + date.toUTCString() + ';path=/';
};