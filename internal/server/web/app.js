// moxian-p2p Web 面板 纯 JS 不用框架
// 零知识加密：用户密码在浏览器派生 masterKey 只把 pwdHash 发给服务器

// ---- PBKDF2 (Web Crypto) ----
async function pbkdf2(password, salt, iterations, lengthBits) {
  const enc = new TextEncoder();
  const keyMaterial = await crypto.subtle.importKey(
    "raw", typeof password === "string" ? enc.encode(password) : password,
    { name: "PBKDF2" }, false, ["deriveBits"]
  );
  const bits = await crypto.subtle.deriveBits(
    { name: "PBKDF2", salt: typeof salt === "string" ? enc.encode(salt) : salt,
      iterations, hash: "SHA-256" },
    keyMaterial, lengthBits
  );
  return new Uint8Array(bits);
}

function b64(bytes) {
  return btoa(String.fromCharCode(...bytes));
}

async function deriveMasterAndHash(password, email, iterations) {
  const master = await pbkdf2(password, email.trim().toLowerCase(), iterations, 256);
  const hash = await pbkdf2(master, password, 1, 256);
  return { master, hashB64: b64(hash) };
}

// ---- API helper ----
async function api(path, options = {}) {
  const token = localStorage.getItem("moxian_jwt");
  const headers = { "Content-Type": "application/json", ...(options.headers || {}) };
  if (token) headers["Authorization"] = "Bearer " + token;
  const res = await fetch(path, { ...options, headers });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.error || res.statusText);
  return body;
}

// ---- 视图切换 ----
function show(view) {
  document.getElementById("view-auth").classList.add("hidden");
  document.getElementById("view-main").classList.add("hidden");
  document.getElementById(view).classList.remove("hidden");
}

// ---- Tabs ----
document.querySelectorAll(".tab").forEach(t => {
  t.onclick = () => {
    document.querySelectorAll(".tab").forEach(x => x.classList.remove("active"));
    t.classList.add("active");
    const pane = t.dataset.tab;
    document.getElementById("pane-login").classList.toggle("hidden", pane !== "login");
    document.getElementById("pane-register").classList.toggle("hidden", pane !== "register");
  };
});

// ---- Login ----
document.getElementById("login-btn").onclick = async () => {
  const btn = document.getElementById("login-btn");
  const msg = document.getElementById("login-msg");
  msg.textContent = "";
  const email = document.getElementById("login-email").value.trim();
  const pass = document.getElementById("login-pass").value;
  if (!email || !pass) { msg.textContent = "请输入邮箱和密码"; return; }
  btn.disabled = true;
  try {
    const pre = await api("/api/auth/prelogin", {
      method: "POST", body: JSON.stringify({ email }),
    });
    const { hashB64 } = await deriveMasterAndHash(pass, email, pre.kdf_iterations);
    const resp = await api("/api/auth/login", {
      method: "POST", body: JSON.stringify({ email, password_hash: hashB64 }),
    });
    localStorage.setItem("moxian_jwt", resp.jwt);
    localStorage.setItem("moxian_user", JSON.stringify({
      id: resp.user_id, username: resp.username, is_admin: resp.is_admin,
    }));
    await loadMain();
  } catch (e) {
    msg.textContent = "登录失败: " + e.message;
  } finally {
    btn.disabled = false;
  }
};

// ---- Register ----
document.getElementById("reg-btn").onclick = async () => {
  const btn = document.getElementById("reg-btn");
  const msg = document.getElementById("reg-msg");
  msg.textContent = "";
  const email = document.getElementById("reg-email").value.trim();
  const username = document.getElementById("reg-username").value.trim();
  const pass = document.getElementById("reg-pass").value;
  const invite = document.getElementById("reg-invite").value.trim().toUpperCase();
  if (!email || !username || !pass) { msg.textContent = "邮箱/用户名/密码必填"; return; }
  if (pass.length < 6) { msg.textContent = "密码最少 6 位"; return; }
  btn.disabled = true;
  try {
    const iterations = 600000;
    const { hashB64 } = await deriveMasterAndHash(pass, email, iterations);
    await api("/api/auth/register", {
      method: "POST",
      body: JSON.stringify({
        email, username, password_hash: hashB64,
        kdf_iterations: iterations, invite_code: invite,
      }),
    });
    msg.classList.remove("err"); msg.classList.add("ok");
    msg.textContent = "✅ 注册成功！自动登录中...";
    // 自动登录
    document.getElementById("login-email").value = email;
    document.getElementById("login-pass").value = pass;
    setTimeout(() => document.getElementById("login-btn").click(), 600);
  } catch (e) {
    msg.textContent = "注册失败: " + e.message;
  } finally {
    btn.disabled = false;
  }
};

// ---- 主面板 ----
async function loadMain() {
  const user = JSON.parse(localStorage.getItem("moxian_user") || "{}");
  document.getElementById("me-info").innerHTML =
    `👤 <b>${user.username}</b> · ${user.is_admin ? "🛡️ 管理员" : "用户"}`;

  show("view-main");
  await refreshNodes();
  if (user.is_admin) {
    document.getElementById("admin-card").classList.remove("hidden");
    document.getElementById("users-card").classList.remove("hidden");
    await refreshInvites();
    await refreshUsers();
  }
}

async function refreshNodes() {
  try {
    const r = await api("/api/nodes");
    const list = document.getElementById("nodes-list");
    if (!r.nodes || r.nodes.length === 0) {
      list.innerHTML = '<div class="muted">还没有节点 在下面注册第一个</div>';
      return;
    }
    list.innerHTML = `<table>
      <thead><tr><th>节点名</th><th>虚拟 IP</th></tr></thead>
      <tbody>${r.nodes.map(n =>
        `<tr><td>${escapeHtml(n.NodeID)}</td><td class="code">${n.VirtualIP}</td></tr>`
      ).join("")}</tbody>
    </table>`;
  } catch (e) {
    console.error(e);
    if (e.message.includes("token")) logout();
  }
}

document.getElementById("add-node-btn").onclick = async () => {
  const id = document.getElementById("new-node-id").value.trim();
  if (!id) return;
  try {
    await api("/api/nodes", {
      method: "POST", body: JSON.stringify({ node_id: id }),
    });
    document.getElementById("new-node-id").value = "";
    await refreshNodes();
  } catch (e) { alert("注册失败: " + e.message); }
};

async function refreshInvites() {
  try {
    const r = await api("/api/admin/invites");
    const list = document.getElementById("invites-list");
    if (!r.invites || r.invites.length === 0) {
      list.innerHTML = '<div class="muted">还没有邀请码</div>';
      return;
    }
    list.innerHTML = `<table>
      <thead><tr><th>邀请码</th><th>状态</th><th>过期</th></tr></thead>
      <tbody>${r.invites.map(inv => {
        const now = Date.now() / 1000;
        const used = inv.used_by ? "已用" : (inv.expires_at < now ? "过期" : "可用");
        const color = used === "可用" ? "var(--green)" : "var(--muted)";
        return `<tr><td class="code">${inv.code}</td>
                <td style="color:${color}">${used}</td>
                <td class="muted">${new Date(inv.expires_at * 1000).toLocaleString()}</td></tr>`;
      }).join("")}</tbody>
    </table>`;
  } catch (e) { console.error(e); }
}

document.getElementById("new-invite-btn").onclick = async () => {
  try {
    const r = await api("/api/admin/invites", {
      method: "POST", body: JSON.stringify({ ttl_hours: 24 }),
    });
    alert("新邀请码: " + r.code + "\n把它发给要加入的人");
    await refreshInvites();
  } catch (e) { alert("生成失败: " + e.message); }
};

async function refreshUsers() {
  try {
    const r = await api("/api/admin/users");
    const list = document.getElementById("users-list");
    if (!r.users || r.users.length === 0) { list.innerHTML = '<div class="muted">无用户</div>'; return; }
    list.innerHTML = `<table>
      <thead><tr><th>邮箱</th><th>用户名</th><th>角色</th><th>操作</th></tr></thead>
      <tbody>${r.users.map(u =>
        `<tr><td>${escapeHtml(u.email)}</td><td>${escapeHtml(u.username)}</td>
         <td>${u.is_admin ? "🛡️ 管理员" : "用户"}</td>
         <td><button class="reset-pwd-btn" data-uid="${u.id}" data-email="${escapeHtml(u.email)}">🔑 重置密码</button></td></tr>`
      ).join("")}</tbody>
    </table>`;
    document.querySelectorAll(".reset-pwd-btn").forEach(btn => {
      btn.onclick = () => resetPasswordFlow(parseInt(btn.dataset.uid), btn.dataset.email);
    });
  } catch (e) { console.error(e); }
}

// 管理员重置用户主密码
// 浏览器端用 PBKDF2 派生新 pwdHash 发给 server
// vault 会被清空（旧 masterKey 已失效）告知用户后再执行
async function resetPasswordFlow(userId, email) {
  const pwd = prompt(
    `⚠️ 重置 ${email} 的主密码\n\n` +
    `重置后影响:\n` +
    `  · 该用户的 vault（已存的 NAS 服务凭据）会被清空 需重新填\n` +
    `  · P2P 节点配置保留 不影响互连\n` +
    `  · 用户需用新密码重新登录\n\n` +
    `请输入新密码（至少 6 位）:`
  );
  if (!pwd) return;
  if (pwd.length < 6) { alert("密码至少 6 位"); return; }
  const confirm2 = prompt("再输一次确认:");
  if (confirm2 !== pwd) { alert("两次输入不一致 已取消"); return; }
  try {
    const iterations = 600000;
    const { hashB64 } = await deriveMasterAndHash(pwd, email, iterations);
    await api("/api/admin/users/reset-password", {
      method: "POST",
      body: JSON.stringify({
        user_id: userId,
        password_hash: hashB64,
        kdf_iterations: iterations,
      }),
    });
    alert(`✅ ${email} 的密码已重置 vault 已清空 用户用新密码重新登录即可`);
  } catch (e) {
    alert("重置失败: " + e.message);
  }
}

// ---- Logout ----
document.getElementById("logout-btn").onclick = () => logout();
function logout() {
  localStorage.removeItem("moxian_jwt");
  localStorage.removeItem("moxian_user");
  location.reload();
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  }[c]));
}

// ---- 启动检查登录态 ----
(async () => {
  const token = localStorage.getItem("moxian_jwt");
  if (!token) return;
  try {
    await api("/api/auth/me");
    await loadMain();
  } catch (e) {
    localStorage.removeItem("moxian_jwt");
  }
})();
