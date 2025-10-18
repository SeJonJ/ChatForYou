function isMobile(){
    return /iPhone|iPad|iPod|Android/i.test(navigator.userAgent);
}

let setCookie = function(name, value, exp) {
	let date = new Date();
	date.setTime(date.getTime() + exp*24*60*60*1000);
	document.cookie = name + '=' + value + ';expires=' + date.toUTCString() + ';path=/';
};

let isElectron = function() {
	return window.navigator.userAgent.toLowerCase().indexOf('electron') > -1 ||
	(window.process && window.process.versions && window.process.versions.electron) ||
	(window.require && window.require('electron')) ||
	window.__dirname !== undefined;
};
