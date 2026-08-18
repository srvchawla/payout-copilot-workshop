const progressKey = "payout-workshop-progress-v1";
const completedSteps = new Set(JSON.parse(localStorage.getItem(progressKey) || "[]"));

const architectureContent = {
  controller: {
    responsibility: "Reject untrusted requests, parse verified payloads, and publish accepted work.",
    risk: "Doing ledger or FX work synchronously delays the webhook response.",
    file: "WebhookController.java",
    path: "src/main/java/com/payout/workshop/payout/webhook/WebhookController.java"
  },
  signature: {
    responsibility: "Compute HMAC-SHA256 over the exact raw request body and compare digests safely.",
    risk: "Parsing before verification or using a non-constant-time comparison weakens authentication.",
    file: "SignatureVerifier.java",
    path: "src/main/java/com/payout/workshop/payout/webhook/SignatureVerifier.java"
  },
  redis: {
    responsibility: "Claim each event ID exactly once with an atomic SET-if-absent operation and TTL.",
    risk: "A separate read and write allows concurrent deliveries to both enter processing.",
    file: "IdempotencyService.java",
    path: "src/main/java/com/payout/workshop/payout/webhook/IdempotencyService.java"
  },
  event: {
    responsibility: "Move accepted ledger work off the HTTP request thread and process completed payouts.",
    risk: "Synchronous work can cause webhook sender timeouts and unnecessary redelivery.",
    file: "PayoutEventListener.java",
    path: "src/main/java/com/payout/workshop/payout/webhook/PayoutEventListener.java"
  },
  ledger: {
    responsibility: "Find the account, convert currency when required, and apply the credit transactionally.",
    risk: "Floating-point money or missing transaction boundaries can corrupt balance calculations.",
    file: "LedgerService.java",
    path: "src/main/java/com/payout/workshop/payout/ledger/LedgerService.java"
  },
  postgres: {
    responsibility: "Persist balances and use optimistic versioning to detect conflicting updates.",
    risk: "Concurrent updates without version checks can silently overwrite a valid balance.",
    file: "AccountBalance.java",
    path: "src/main/java/com/payout/workshop/payout/ledger/AccountBalance.java"
  }
};

function updateProgress() {
  document.querySelectorAll("[data-step]").forEach((element) => {
    const step = Number(element.dataset.step);
    element.classList.toggle("completed", completedSteps.has(step));
  });

  document.querySelectorAll("[data-complete]").forEach((button) => {
    const step = Number(button.dataset.complete);
    const complete = completedSteps.has(step);
    button.innerHTML = complete ? '<span aria-hidden="true">✓</span> Completed' : '<span aria-hidden="true">○</span> Mark complete';
    button.setAttribute("aria-pressed", String(complete));
  });

  document.querySelectorAll("[data-step-state]").forEach((state) => {
    const complete = completedSteps.has(Number(state.dataset.stepState));
    state.textContent = complete ? "✓" : "○";
    state.classList.toggle("complete", complete);
  });

  const count = completedSteps.size;
  document.querySelector("[data-progress-label]").textContent = `${count} of 6 complete`;
  document.querySelector("[data-progress-bar]").style.width = `${(count / 6) * 100}%`;
  localStorage.setItem(progressKey, JSON.stringify([...completedSteps].sort()));
}

function showToast(message) {
  const toast = document.querySelector("[data-toast]");
  toast.textContent = message;
  toast.classList.add("visible");
  window.clearTimeout(showToast.timeout);
  showToast.timeout = window.setTimeout(() => toast.classList.remove("visible"), 1800);
}

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    showToast("Copied to clipboard");
  } catch {
    const input = document.createElement("textarea");
    input.value = text;
    input.style.position = "fixed";
    input.style.opacity = "0";
    document.body.appendChild(input);
    input.select();
    document.execCommand("copy");
    input.remove();
    showToast("Copied to clipboard");
  }
}

function closeMenu() {
  document.body.classList.remove("menu-open");
  document.querySelector("[data-menu-toggle]").setAttribute("aria-expanded", "false");
}

function delay(milliseconds) {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
}

function resetPipeline() {
  document.querySelectorAll("[data-node]").forEach((node) => {
    node.className = "pipeline-node";
    node.querySelector("small").textContent = "Waiting";
  });
}

function setNode(name, state, label) {
  const node = document.querySelector(`[data-node="${name}"]`);
  node.className = `pipeline-node ${state}`;
  node.querySelector("small").textContent = label;
}

function renderResult({ status, signature, idempotency, ledger, balance, tone }) {
  document.querySelector("[data-simulation-result]").innerHTML = `
    <div class="result-grid">
      <div><span>HTTP response</span><strong class="${tone}">${status}</strong></div>
      <div><span>Signature</span><strong class="${tone}">${signature}</strong></div>
      <div><span>Idempotency</span><strong class="${idempotency.includes("Duplicate") ? "warn" : tone}">${idempotency}</strong></div>
      <div><span>Ledger</span><strong class="${ledger.includes("Skipped") ? "warn" : tone}">${ledger}</strong></div>
      <div><span>Balance</span><strong>${balance}</strong></div>
      <div><span>Data source</span><strong>Educational model</strong></div>
    </div>`;
}

async function runSimulation(form) {
  const submitButton = form.querySelector("button[type='submit']");
  const formData = new FormData(form);
  const status = formData.get("status");
  const amount = Number(formData.get("amount"));
  const currency = formData.get("currency");
  const validSignature = formData.get("validSignature") === "on";
  const duplicate = formData.get("duplicate") === "on";

  submitButton.disabled = true;
  submitButton.textContent = "Processing event...";
  resetPipeline();
  document.querySelector("[data-simulation-result]").innerHTML = '<div class="empty-result"><span>•••</span><strong>Tracing delivery</strong><small>Following the event through each boundary.</small></div>';

  setNode("request", "active", "Received");
  await delay(280);
  setNode("request", "success", "Accepted");
  setNode("signature", "active", "Checking");
  await delay(350);

  if (!validSignature) {
    setNode("signature", "error", "Rejected");
    setNode("redis", "", "Not reached");
    setNode("ledger", "", "Not reached");
    renderResult({ status: "401 Unauthorized", signature: "Invalid", idempotency: "Not checked", ledger: "Not published", balance: "100.0000 unchanged", tone: "bad" });
  } else {
    setNode("signature", "success", "Verified");
    setNode("redis", "active", "Claiming");
    await delay(350);

    if (duplicate) {
      setNode("redis", "stopped", "Duplicate");
      setNode("ledger", "", "Not reached");
      renderResult({ status: "200 OK", signature: "Verified", idempotency: "Duplicate stopped", ledger: "Skipped", balance: "125.0000 unchanged", tone: "good" });
    } else {
      setNode("redis", "success", "Acquired");
      setNode("ledger", "active", "Async work");
      await delay(420);
      if (status === "COMPLETED") {
        setNode("ledger", "success", "Credited");
        const convertedAmount = currency === "USD" ? amount : currency === "EUR" ? amount / 0.92 : amount / 0.79;
        renderResult({ status: "200 OK", signature: "Verified", idempotency: "Key acquired", ledger: `${amount.toFixed(2)} ${currency} credited`, balance: `100.0000 → ${(100 + convertedAmount).toFixed(4)} USD`, tone: "good" });
      } else {
        setNode("ledger", "stopped", "Ignored");
        renderResult({ status: "200 OK", signature: "Verified", idempotency: "Key acquired", ledger: `Skipped for ${status}`, balance: "100.0000 unchanged", tone: "good" });
      }
    }
  }

  submitButton.disabled = false;
  submitButton.innerHTML = '<span aria-hidden="true">▶</span> Send webhook';
}

document.querySelectorAll("[data-complete]").forEach((button) => {
  button.addEventListener("click", () => {
    const step = Number(button.dataset.complete);
    completedSteps.has(step) ? completedSteps.delete(step) : completedSteps.add(step);
    updateProgress();
  });
});

document.querySelector("[data-reset-progress]").addEventListener("click", () => {
  completedSteps.clear();
  updateProgress();
  showToast("Workshop progress reset");
});

document.querySelectorAll("[data-copy]").forEach((button) => {
  button.addEventListener("click", () => copyText(button.dataset.copy));
});

document.querySelectorAll("[data-menu-toggle]").forEach((button) => {
  button.addEventListener("click", () => {
    const open = document.body.classList.toggle("menu-open");
    document.querySelector(".menu-button").setAttribute("aria-expanded", String(open));
  });
});

document.querySelectorAll(".sidebar a").forEach((link) => link.addEventListener("click", closeMenu));

document.querySelectorAll("[data-reveal]").forEach((button) => {
  button.addEventListener("click", () => {
    const hint = document.getElementById(button.dataset.reveal);
    hint.hidden = !hint.hidden;
    button.textContent = hint.hidden ? "Reveal hint" : "Hide hint";
  });
});

document.querySelector("[data-simulator-form]").addEventListener("submit", (event) => {
  event.preventDefault();
  runSimulation(event.currentTarget);
});

document.querySelectorAll("[data-architecture]").forEach((button) => {
  button.addEventListener("click", () => {
    document.querySelectorAll("[data-architecture]").forEach((item) => item.classList.remove("active"));
    button.classList.add("active");
    const content = architectureContent[button.dataset.architecture];
    document.querySelector("[data-architecture-detail]").innerHTML = `
      <div><span class="detail-label">RESPONSIBILITY</span><strong>${content.responsibility}</strong></div>
      <div><span class="detail-label">PRIMARY RISK</span><strong>${content.risk}</strong></div>
      <div><span class="detail-label">OWNER</span><a href="https://github.com/srvchawla/payout-copilot-workshop/blob/main/${content.path}" target="_blank" rel="noreferrer">${content.file} ↗</a></div>`;
  });
});

const observedSections = [...document.querySelectorAll("main section[id]")];
const navLinks = [...document.querySelectorAll(".nav-link")];
const observer = new IntersectionObserver((entries) => {
  const visible = entries.filter((entry) => entry.isIntersecting).sort((a, b) => b.intersectionRatio - a.intersectionRatio)[0];
  if (!visible) return;
  navLinks.forEach((link) => link.classList.toggle("active", link.getAttribute("href") === `#${visible.target.id}`));
}, { rootMargin: "-25% 0px -60%", threshold: [0.05, 0.25, 0.5] });

observedSections.forEach((section) => observer.observe(section));
updateProgress();