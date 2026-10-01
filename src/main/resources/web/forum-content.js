(function () {
  if (window.linuxDoContentBound) return;
  window.linuxDoContentBound = true;
  document.addEventListener('click', function (event) {
    var spoiler = event.target.closest('.spoiler');
    if (spoiler && !spoiler.classList.contains('revealed')) {
      spoiler.classList.add('revealed');
      event.preventDefault(); event.stopImmediatePropagation();
    }
  }, true);
})();
