/**
 * 500 오류 페이지의 눈동자가 마우스를 따라가게 한다.
 */
$('body').mousemove(function(event) {
    const $eye = $('.eye');
    const x = ($eye.offset().left) + ($eye.width() / 2);
    const y = ($eye.offset().top) + ($eye.height() / 2);
    const rad = Math.atan2(event.pageX - x, event.pageY - y);
    const rot = (rad * (180 / Math.PI) * -1) + 180;
    $eye.css({
        '-webkit-transform': 'rotate(' + rot + 'deg)',
        'transform': 'rotate(' + rot + 'deg)'
    });
});

export {};
