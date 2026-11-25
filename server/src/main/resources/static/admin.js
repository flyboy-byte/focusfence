// Admin page helpers: copy a block of text, or save it as a file. Nothing here talks to the server.
document.addEventListener('click', function (e) {
  var copy = e.target.closest('[data-copy]');
  if (copy) {
    var text = document.getElementById(copy.getAttribute('data-copy')).innerText;
    navigator.clipboard.writeText(text).then(function () {
      var was = copy.textContent;
      copy.textContent = 'Copied';
      setTimeout(function () { copy.textContent = was; }, 1500);
    });
    return;
  }
  var save = e.target.closest('[data-download]');
  if (save) {
    var body = document.getElementById(save.getAttribute('data-download')).innerText;
    var a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([body + '\n'], { type: 'text/plain' }));
    a.download = save.getAttribute('data-name') || 'focusfence.txt';
    document.body.appendChild(a);
    a.click();
    a.remove();
  }
});
