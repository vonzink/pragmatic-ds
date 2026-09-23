/*
 * RAG Brain embeddable assistant widget.
 *
 * Drop-in usage (no build step, no dependencies):
 *
 *   <script src="https://your-brain-host/widget/rag-brain-widget.js"
 *           data-rag-api="https://your-brain-host"
 *           data-rag-slug="your-brain-slug"
 *           data-rag-token="rb_pub_..."
 *           data-rag-title="Ask us anything"
 *           defer></script>
 *
 * Compliance is enforced in the UI: the disclaimer is always shown with every
 * answer, citations are rendered when present, and a "Talk to a human" call to
 * action appears whenever the API flags humanEscalationRequired.
 */
(function () {
  "use strict";

  var script =
    document.currentScript ||
    (function () {
      var all = document.getElementsByTagName("script");
      return all[all.length - 1];
    })();

  var cfg = {
    api: (script.getAttribute("data-rag-api") || "").replace(/\/+$/, ""),
    slug: script.getAttribute("data-rag-slug") || "",
    token: script.getAttribute("data-rag-token") || "",
    title: script.getAttribute("data-rag-title") || "Ask the assistant",
    accent: script.getAttribute("data-rag-accent") || "#2563eb",
  };

  if (!cfg.api || !cfg.slug || !cfg.token) {
    console.error("[rag-brain] missing data-rag-api, data-rag-slug, or data-rag-token");
    return;
  }

  var SESSION_KEY = "rag-brain-session-" + cfg.slug;
  function sessionId() {
    var id = sessionStorage.getItem(SESSION_KEY);
    if (!id) {
      id =
        (window.crypto && window.crypto.randomUUID && window.crypto.randomUUID()) ||
        "s-" + Date.now() + "-" + Math.random().toString(16).slice(2);
      sessionStorage.setItem(SESSION_KEY, id);
    }
    return id;
  }
  var conversationId = null;

  function el(tag, props, children) {
    var node = document.createElement(tag);
    if (props) Object.keys(props).forEach(function (k) {
      if (k === "style") node.setAttribute("style", props[k]);
      else if (k === "class") node.className = props[k];
      else node[k] = props[k];
    });
    (children || []).forEach(function (c) {
      node.appendChild(typeof c === "string" ? document.createTextNode(c) : c);
    });
    return node;
  }

  var styles =
    ".ragb-btn{position:fixed;right:20px;bottom:20px;z-index:2147483000;border:none;border-radius:999px;" +
    "padding:14px 18px;color:#fff;font:600 14px system-ui,sans-serif;cursor:pointer;box-shadow:0 6px 24px rgba(0,0,0,.2)}" +
    ".ragb-panel{position:fixed;right:20px;bottom:78px;width:360px;max-width:calc(100vw - 40px);height:520px;" +
    "max-height:calc(100vh - 120px);z-index:2147483000;background:#fff;border-radius:14px;display:none;flex-direction:column;" +
    "overflow:hidden;box-shadow:0 16px 48px rgba(0,0,0,.28);font:14px system-ui,sans-serif;color:#0f172a}" +
    ".ragb-panel.open{display:flex}" +
    ".ragb-head{padding:14px 16px;color:#fff;font-weight:600}" +
    ".ragb-log{flex:1;overflow-y:auto;padding:14px;background:#f8fafc}" +
    ".ragb-msg{margin:0 0 12px;line-height:1.45}" +
    ".ragb-user{text-align:right}" +
    ".ragb-user span{display:inline-block;background:#e2e8f0;border-radius:12px 12px 2px 12px;padding:8px 12px}" +
    ".ragb-bot span{display:inline-block;background:#fff;border:1px solid #e2e8f0;border-radius:12px 12px 12px 2px;padding:8px 12px;white-space:pre-wrap}" +
    ".ragb-meta{font-size:12px;color:#64748b;margin-top:6px}" +
    ".ragb-cite{font-size:12px;color:#475569;margin-top:6px}" +
    ".ragb-disc{font-size:11px;color:#94a3b8;margin-top:8px;font-style:italic}" +
    ".ragb-cta{display:inline-block;margin-top:8px;font-size:12px;font-weight:600}" +
    ".ragb-form{display:flex;border-top:1px solid #e2e8f0}" +
    ".ragb-input{flex:1;border:none;padding:12px 14px;font:14px system-ui,sans-serif;outline:none}" +
    ".ragb-send{border:none;background:transparent;color:#2563eb;font-weight:600;padding:0 14px;cursor:pointer}";

  document.head.appendChild(el("style", { textContent: styles }));

  var log = el("div", { class: "ragb-log" });
  var input = el("input", { class: "ragb-input", placeholder: "Type your question…", type: "text" });
  var send = el("button", { class: "ragb-send", type: "submit" }, ["Send"]);
  var form = el("form", { class: "ragb-form" }, [input, send]);
  var head = el("div", { class: "ragb-head", style: "background:" + cfg.accent }, [cfg.title]);
  var panel = el("div", { class: "ragb-panel" }, [head, log, form]);
  var button = el("button", { class: "ragb-btn", style: "background:" + cfg.accent }, ["Chat"]);

  document.body.appendChild(panel);
  document.body.appendChild(button);

  button.addEventListener("click", function () {
    panel.classList.toggle("open");
    if (panel.classList.contains("open")) input.focus();
  });

  function addUser(text) {
    log.appendChild(el("div", { class: "ragb-msg ragb-user" }, [el("span", null, [text])]));
    log.scrollTop = log.scrollHeight;
  }

  function addBot(build) {
    var wrap = el("div", { class: "ragb-msg ragb-bot" });
    log.appendChild(wrap);
    build(wrap);
    log.scrollTop = log.scrollHeight;
    return wrap;
  }

  function renderAnswer(wrap, data) {
    wrap.innerHTML = "";
    var text = data.answer || data.message || data.clarifyingQuestion || "…";
    wrap.appendChild(el("span", null, [text]));

    if (data.citations && data.citations.length) {
      var parts = data.citations
        .map(function (c) {
          return [c.source_name, c.section, c.page_number ? "p." + c.page_number : null]
            .filter(Boolean)
            .join(", ");
        })
        .filter(Boolean);
      if (parts.length) wrap.appendChild(el("div", { class: "ragb-cite" }, ["Sources: " + parts.join("; ")]));
    }

    if (data.humanEscalationRequired) {
      wrap.appendChild(
        el("a", { class: "ragb-cta", href: "#", style: "color:" + cfg.accent }, ["Talk to a human →"])
      );
    }

    if (data.disclaimer) wrap.appendChild(el("div", { class: "ragb-disc" }, [data.disclaimer]));
  }

  function ask(message) {
    addUser(message);
    var pending = addBot(function (w) { w.appendChild(el("span", null, ["…"])); });

    fetch(cfg.api + "/api/ai/public/" + encodeURIComponent(cfg.slug) + "/ask", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Public-Brain-Token": cfg.token },
      body: JSON.stringify({
        sessionId: sessionId(),
        conversationId: conversationId,
        message: message,
        pageRoute: location.pathname,
        surface: "PUBLIC",
        facts: {},
      }),
    })
      .then(function (res) {
        return res.json().then(function (body) {
          if (!res.ok) throw new Error(body && body.error ? body.error : "HTTP " + res.status);
          return body;
        });
      })
      .then(function (data) {
        if (data.conversationId) conversationId = data.conversationId;
        renderAnswer(pending, data);
      })
      .catch(function (err) {
        pending.innerHTML = "";
        pending.appendChild(el("span", null, ["Sorry — " + err.message + ". Please try again shortly."]));
      });
  }

  form.addEventListener("submit", function (e) {
    e.preventDefault();
    var message = input.value.trim();
    if (!message) return;
    input.value = "";
    ask(message);
  });
})();
