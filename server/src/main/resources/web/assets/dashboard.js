// Orbit Server dashboard: small progressive enhancements. Every page works without this file.
(function () {
  "use strict";

  // Copy buttons: <button data-copy="element-id">.
  document.querySelectorAll("[data-copy]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      var el = document.getElementById(btn.getAttribute("data-copy"));
      if (!el) return;
      var text = el.textContent;
      var done = function () {
        btn.classList.add("copied");
        setTimeout(function () { btn.classList.remove("copied"); }, 1600);
      };
      if (navigator.clipboard && window.isSecureContext) {
        navigator.clipboard.writeText(text).then(done, function () { selectText(el); });
      } else {
        selectText(el);
      }
    });
  });

  function selectText(el) {
    var range = document.createRange();
    range.selectNodeContents(el);
    var sel = window.getSelection();
    sel.removeAllRanges();
    sel.addRange(range);
  }

  // Confirm before destructive forms: <form data-confirm="...">.
  document.querySelectorAll("form[data-confirm]").forEach(function (form) {
    form.addEventListener("submit", function (e) {
      if (!window.confirm(form.getAttribute("data-confirm"))) e.preventDefault();
    });
  });

  // Agent page: show the base URL only for OpenAI-compatible providers.
  var provider = document.getElementById("provider");
  if (provider) {
    var sync = function () {
      document.querySelectorAll("[data-show-for]").forEach(function (el) {
        el.hidden = el.getAttribute("data-show-for") !== provider.value;
      });
    };
    provider.addEventListener("change", sync);
    sync();
  }

  // Pair page: count down to expiry, then show a fresh code (a few times; after that, on request).
  var frame = document.querySelector("[data-expires-at]");
  if (frame) {
    var expires = Number(frame.getAttribute("data-expires-at"));
    var skew = Date.now() - Number(frame.getAttribute("data-now"));
    var refreshes = Number(frame.getAttribute("data-refresh") || "0");
    var out = document.querySelector("[data-countdown]");
    var tick = function () {
      var left = Math.max(0, Math.round((expires - (Date.now() - skew)) / 1000));
      if (out) out.textContent = Math.floor(left / 60) + ":" + String(left % 60).padStart(2, "0");
      if (left > 0) return;
      clearInterval(timer);
      frame.classList.add("expired");
      if (refreshes < 3) {
        var go = function () { window.location.replace("/pair?r=" + (refreshes + 1)); };
        if (document.visibilityState === "visible") setTimeout(go, 1500);
        else document.addEventListener("visibilitychange", function () { if (document.visibilityState === "visible") go(); }, { once: true });
      }
    };
    var timer = setInterval(tick, 1000);
    tick();
  }
})();
