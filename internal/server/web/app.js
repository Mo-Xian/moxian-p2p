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

// ---- HKDF-Expand (RFC 5869, Bitwarden 用法 prk 直接当 master key) ----
async function hkdfExpand(prk, info, length) {
  const enc = new TextEncoder();
  const infoBytes = enc.encode(info);
  const n = Math.ceil(length / 32);
  const okm = new Uint8Array(length);
  let t = new Uint8Array(0);
  let offset = 0;
  const hmacKey = await crypto.subtle.importKey(
    "raw", prk, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]
  );
  for (let i = 1; i <= n; i++) {
    const input = new Uint8Array(t.length + infoBytes.length + 1);
    input.set(t, 0);
    input.set(infoBytes, t.length);
    input[input.length - 1] = i;
    const sig = await crypto.subtle.sign("HMAC", hmacKey, input);
    t = new Uint8Array(sig);
    const copy = Math.min(32, length - offset);
    okm.set(t.subarray(0, copy), offset);
    offset += copy;
  }
  return okm;
}

async function stretchMasterKey(master) {
  const enc = await hkdfExpand(master, "enc", 32);
  const mac = await hkdfExpand(master, "mac", 32);
  return { enc, mac };
}

// ---- EncString type 2: "2.IV|CT|MAC" ----
function u8ToB64(u8) { return btoa(String.fromCharCode(...u8)); }
function b64ToU8(s) { return Uint8Array.from(atob(s), c => c.charCodeAt(0)); }

async function decryptEncString(blob, encKey, macKey) {
  if (!blob || !blob.startsWith("2.")) return null;
  const parts = blob.substring(2).split("|");
  if (parts.length < 3) return null;
  const iv = b64ToU8(parts[0]);
  const ct = b64ToU8(parts[1]);
  const expectedMac = b64ToU8(parts[2]);

  // 验 HMAC
  const macK = await crypto.subtle.importKey(
    "raw", macKey, { name: "HMAC", hash: "SHA-256" }, false, ["verify"]
  );
  const data = new Uint8Array(iv.length + ct.length);
  data.set(iv, 0); data.set(ct, iv.length);
  const ok = await crypto.subtle.verify("HMAC", macK, expectedMac, data);
  if (!ok) return null;

  // AES-CBC decrypt
  const aesK = await crypto.subtle.importKey(
    "raw", encKey, { name: "AES-CBC" }, false, ["decrypt"]
  );
  try {
    const plain = await crypto.subtle.decrypt({ name: "AES-CBC", iv }, aesK, ct);
    return new TextDecoder().decode(plain);
  } catch (e) { return null; }
}

async function encryptToEncString(plain, encKey, macKey) {
  const iv = crypto.getRandomValues(new Uint8Array(16));
  const aesK = await crypto.subtle.importKey(
    "raw", encKey, { name: "AES-CBC" }, false, ["encrypt"]
  );
  const ct = new Uint8Array(await crypto.subtle.encrypt(
    { name: "AES-CBC", iv }, aesK, new TextEncoder().encode(plain)
  ));
  const macK = await crypto.subtle.importKey(
    "raw", macKey, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]
  );
  const data = new Uint8Array(iv.length + ct.length);
  data.set(iv, 0); data.set(ct, iv.length);
  const mac = new Uint8Array(await crypto.subtle.sign("HMAC", macK, data));
  return `2.${u8ToB64(iv)}|${u8ToB64(ct)}|${u8ToB64(mac)}`;
}

// ---- Vault: 拉 / 加载 / 保存 ----
// _masterKey 仅内存 不持久化（login 时设置 logout 时清）
let _masterKey = null;
let _vaultVersion = 0;

async function loadVault() {
  if (!_masterKey) throw new Error("vault locked: 未登录或主密钥已清");
  const r = await api("/api/vault");
  _vaultVersion = r.version || 0;
  const blob = r.encrypted_vault || "";
  const { enc, mac } = await stretchMasterKey(_masterKey);
  if (!blob) return { version: 2, services: [], entries: [] };
  const plain = await decryptEncString(blob, enc, mac);
  if (plain == null) throw new Error("vault 解密失败 主密码不对？");
  const data = JSON.parse(plain);
  // 兼容 v1（仅 entries）
  if (!data.services) data.services = [];
  if (!data.entries) data.entries = [];
  return data;
}

async function saveVault(data) {
  if (!_masterKey) throw new Error("vault locked");
  const { enc, mac } = await stretchMasterKey(_masterKey);
  data.version = 2;
  const blob = await encryptToEncString(JSON.stringify(data), enc, mac);
  const r = await api("/api/vault", {
    method: "POST",
    body: JSON.stringify({ encrypted_vault: blob, expected_version: _vaultVersion }),
  });
  _vaultVersion = r.version || _vaultVersion + 1;
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
    const { master, hashB64 } = await deriveMasterAndHash(pass, email, pre.kdf_iterations);
    const resp = await api("/api/auth/login", {
      method: "POST", body: JSON.stringify({ email, password_hash: hashB64 }),
    });
    _masterKey = master;  // 内存保留 用来读写 vault
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
  await refreshVaultUI();
  if (user.is_admin) {
    document.getElementById("admin-card").classList.remove("hidden");
    document.getElementById("users-card").classList.remove("hidden");
    document.getElementById("releases-card").classList.remove("hidden");
    await refreshInvites();
    await refreshUsers();
    await refreshReleases();
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
      <thead><tr><th>节点名</th><th>虚拟 IP</th><th>操作</th></tr></thead>
      <tbody>${r.nodes.map(n =>
        `<tr>
          <td>${escapeHtml(n.NodeID)}</td>
          <td class="code">${n.VirtualIP}</td>
          <td><button class="del-node-btn" data-id="${escapeHtml(n.NodeID)}">🗑️ 删除</button></td>
        </tr>`
      ).join("")}</tbody>
    </table>`;
    document.querySelectorAll(".del-node-btn").forEach(b => {
      b.onclick = async () => {
        if (!confirm(`删除节点 ${b.dataset.id}？\n（仅清 server 端记录 不影响该设备 APP 配置）`)) return;
        try {
          await api("/api/nodes?node=" + encodeURIComponent(b.dataset.id),
            { method: "DELETE" });
          await refreshNodes();
        } catch (e) {
          alert("删除失败: " + e.message);
        }
      };
    });
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

// ---- Releases (admin) ----
async function refreshReleases() {
  try {
    const r = await api("/api/admin/release/list");
    const list = document.getElementById("releases-list");
    if (!r.releases || r.releases.length === 0) {
      list.innerHTML = '<div class="muted">还没有 release 上传一个 APK 文件吧</div>';
      return;
    }
    const fmtSize = (n) => n >= 1024*1024 ? (n/1048576).toFixed(1)+" MB" : (n/1024).toFixed(1)+" KB";
    const fmtTime = (ts) => new Date(ts*1000).toLocaleString();
    list.innerHTML = `<table>
      <thead><tr><th>tag</th><th>文件</th><th>大小</th><th>上传时间</th><th>状态</th><th>操作</th></tr></thead>
      <tbody>${r.releases.map(e => `
        <tr>
          <td class="code">${escapeHtml(e.tag)}</td>
          <td>${escapeHtml(e.filename)}</td>
          <td>${fmtSize(e.size)}</td>
          <td>${fmtTime(e.uploaded_at)}</td>
          <td>${e.tag === r.latest ? '<b style="color:var(--accent)">✅ latest</b>' : ''}</td>
          <td>
            ${e.tag === r.latest ? '' : `<button class="promote-btn" data-tag="${escapeHtml(e.tag)}">设为 latest</button>`}
            <button class="del-rel-btn" data-tag="${escapeHtml(e.tag)}">🗑️ 删除</button>
          </td>
        </tr>`).join("")}</tbody>
    </table>`;
    document.querySelectorAll(".promote-btn").forEach(b => {
      b.onclick = async () => {
        await api("/api/admin/release/promote", {
          method: "POST", body: JSON.stringify({ tag: b.dataset.tag }),
        });
        await refreshReleases();
      };
    });
    document.querySelectorAll(".del-rel-btn").forEach(b => {
      b.onclick = async () => {
        if (!confirm(`确定删除 ${b.dataset.tag}？`)) return;
        await api("/api/admin/release?tag=" + encodeURIComponent(b.dataset.tag), { method: "DELETE" });
        await refreshReleases();
      };
    });
  } catch (e) { console.error(e); }
}

document.getElementById("upload-rel-btn").onclick = async () => {
  const btn = document.getElementById("upload-rel-btn");
  const msg = document.getElementById("upload-rel-msg");
  msg.textContent = "";
  const tag = document.getElementById("rel-tag").value.trim();
  const notes = document.getElementById("rel-notes").value;
  const fileInput = document.getElementById("rel-file");
  if (!tag) { msg.textContent = "tag 必填"; return; }
  if (!fileInput.files || fileInput.files.length === 0) { msg.textContent = "请选 APK 文件"; return; }
  const file = fileInput.files[0];
  if (file.size > 200 * 1024 * 1024) { msg.textContent = "APK 超过 200MB"; return; }

  const fd = new FormData();
  fd.append("tag", tag);
  fd.append("notes", notes);
  fd.append("file", file);

  btn.disabled = true;
  msg.textContent = `上传中 ${(file.size/1048576).toFixed(1)} MB...`;
  try {
    const token = localStorage.getItem("moxian_jwt");
    const res = await fetch("/api/admin/release/upload", {
      method: "POST",
      headers: { "Authorization": "Bearer " + token },
      body: fd,
    });
    const body = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(body.error || res.statusText);
    msg.textContent = `✅ 上传成功 sha256=${body.sha256.slice(0, 12)}...`;
    document.getElementById("rel-tag").value = "";
    document.getElementById("rel-notes").value = "";
    fileInput.value = "";
    await refreshReleases();
  } catch (e) {
    msg.textContent = "上传失败: " + e.message;
  } finally {
    btn.disabled = false;
  }
};

// ---- Vault: NAS 服务管理 ----
let _vault = null;  // 解锁后的明文 { version, services: [], entries: [] }

async function refreshVaultUI() {
  const lockedDiv = document.getElementById("vault-locked");
  const contentDiv = document.getElementById("vault-content");
  if (!_masterKey) {
    lockedDiv.classList.remove("hidden");
    contentDiv.classList.add("hidden");
    return;
  }
  lockedDiv.classList.add("hidden");
  contentDiv.classList.remove("hidden");
  try {
    _vault = await loadVault();
    renderServicesList();
  } catch (e) {
    document.getElementById("services-list").innerHTML =
      `<div class="muted">vault 加载失败: ${escapeHtml(e.message)}</div>`;
  }
}

function renderServicesList() {
  const list = document.getElementById("services-list");
  const services = _vault.services || [];
  if (services.length === 0) {
    list.innerHTML = '<div class="muted">还没有服务 在下面添加</div>';
    return;
  }
  const typeIcon = { VIDEO: "📺", MUSIC: "🎵", PHOTO: "📷", FILE: "📁",
                     PASSWORD: "🔐", DOWNLOAD: "⬇️", DASHBOARD: "📊", OTHER: "🔗" };
  list.innerHTML = `<table>
    <thead><tr><th>服务</th><th>URL</th><th>用户名</th><th>密码</th><th>操作</th></tr></thead>
    <tbody>${services.map(s => {
      const cred = (_vault.entries || []).find(e => e.service_id === s.id) || {};
      const pwdMask = cred.password ? "●●●●●●" : "<span class='muted'>未设</span>";
      return `<tr>
        <td>${typeIcon[s.type] || "🔗"} ${escapeHtml(s.name)}</td>
        <td class="code">${escapeHtml(s.url)}</td>
        <td>${escapeHtml(cred.username || "")}</td>
        <td>${pwdMask}</td>
        <td>
          <button class="edit-svc-btn" data-id="${escapeHtml(s.id)}">编辑</button>
          <button class="del-svc-btn" data-id="${escapeHtml(s.id)}">🗑️</button>
        </td>
      </tr>`;
    }).join("")}</tbody>
  </table>`;
  document.querySelectorAll(".edit-svc-btn").forEach(b => {
    b.onclick = () => editService(b.dataset.id);
  });
  document.querySelectorAll(".del-svc-btn").forEach(b => {
    b.onclick = () => deleteService(b.dataset.id);
  });
}

async function editService(id) {
  const s = _vault.services.find(x => x.id === id);
  if (!s) return;
  const cred = _vault.entries.find(e => e.service_id === id) || {};
  const newName = prompt("名称", s.name); if (newName == null) return;
  const newUrl = prompt("URL", s.url); if (newUrl == null) return;
  const newUser = prompt("用户名", cred.username || ""); if (newUser == null) return;
  const newPass = prompt("密码（留空保留）", "");
  s.name = newName.trim();
  s.url = newUrl.trim();
  if (cred.service_id) {
    cred.username = newUser;
    if (newPass !== "") cred.password = newPass;
  } else {
    _vault.entries.push({
      service_id: id, username: newUser,
      password: newPass || "", extra: ""
    });
  }
  try {
    await saveVault(_vault);
    renderServicesList();
  } catch (e) {
    alert("保存失败: " + e.message);
  }
}

async function deleteService(id) {
  const s = _vault.services.find(x => x.id === id);
  if (!s) return;
  if (!confirm(`删除 ${s.name}?`)) return;
  _vault.services = _vault.services.filter(x => x.id !== id);
  _vault.entries = _vault.entries.filter(e => e.service_id !== id);
  try {
    await saveVault(_vault);
    renderServicesList();
  } catch (e) {
    alert("删除失败: " + e.message);
  }
}

document.getElementById("vault-unlock-btn").onclick = async () => {
  const pwd = document.getElementById("vault-pwd").value;
  const msg = document.getElementById("vault-unlock-msg");
  if (!pwd) { msg.textContent = "输主密码"; return; }
  const user = JSON.parse(localStorage.getItem("moxian_user") || "{}");
  msg.textContent = "解锁中...";
  try {
    const me = await api("/api/auth/me");
    const pre = await api("/api/auth/prelogin", {
      method: "POST", body: JSON.stringify({ email: me.email }),
    });
    const { master } = await deriveMasterAndHash(pwd, me.email, pre.kdf_iterations);
    _masterKey = master;
    document.getElementById("vault-pwd").value = "";
    msg.textContent = "";
    await refreshVaultUI();
  } catch (e) {
    msg.textContent = "解锁失败: " + e.message;
  }
};

document.getElementById("svc-add-btn").onclick = async () => {
  const msg = document.getElementById("svc-add-msg");
  const name = document.getElementById("svc-new-name").value.trim();
  const url = document.getElementById("svc-new-url").value.trim();
  const type = document.getElementById("svc-new-type").value;
  const user = document.getElementById("svc-new-user").value;
  const pass = document.getElementById("svc-new-pass").value;
  if (!name || !url) { msg.textContent = "名称和 URL 必填"; return; }
  // 稳定 id：name+url 的简单 hash（两端要一致）
  const id = await sha1Hex(name + "|" + url);
  if (_vault.services.some(s => s.id === id)) {
    msg.textContent = "同名+URL 已存在"; return;
  }
  _vault.services.push({ id, name, url, type });
  if (user || pass) {
    _vault.entries.push({ service_id: id, username: user, password: pass, extra: "" });
  }
  try {
    await saveVault(_vault);
    document.getElementById("svc-new-name").value = "";
    document.getElementById("svc-new-url").value = "";
    document.getElementById("svc-new-user").value = "";
    document.getElementById("svc-new-pass").value = "";
    msg.textContent = "✅ 已添加";
    renderServicesList();
  } catch (e) {
    msg.textContent = "保存失败: " + e.message;
  }
};

async function sha1Hex(s) {
  const h = await crypto.subtle.digest("SHA-1", new TextEncoder().encode(s));
  return Array.from(new Uint8Array(h)).map(b => b.toString(16).padStart(2,"0")).join("");
}

// ---- Logout ----
document.getElementById("logout-btn").onclick = () => logout();
function logout() {
  _masterKey = null;
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
