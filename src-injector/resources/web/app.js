/* The bundled injector UI. All runtime state comes from the local service. */
(function () {
  'use strict';
  const $ = id => document.getElementById(id);
  const params = new URLSearchParams(location.search);
  const token = params.get('t') || '';
  const initialPage = params.get('panel') || location.hash.slice(1) || 'inject';
  const escape = value => String(value == null ? '' : value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  const pages = ['inject','procs','log','diag','settings','about'];
  let state = null, entered = false, currentPage = 'inject', selectedPid = '', attemptedPid = '';
  let polling = false, connected = false, regMode = false, authEpoch = 0, authBusy = false, authTouched = false;
  let countdownTimer = null, toastTimer = null, settingsPending = null, settingsSaving = false;
  let logWhich = 'injector', logPath = '', logRequest = 0, diagRequest = 0, lastProcs = '', lastLines = '', stateKey = '';
  let requestingInject = false, scanning = false, rangeEditing = false;
  let disconnected = false, recoveryFocus = null;
  let subscriptionData = null, subscriptionOwner = '', accountRequest = 0, redeemBusy = false;
  const animations = new WeakMap();

  async function api(name, data) {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), ['login','register','autologin','account','redeem'].includes(name) ? 30000 : 12000);
    try {
      const response = await fetch('/api/' + name + '?t=' + encodeURIComponent(token), {
        method: data === undefined ? 'GET' : 'POST',
        headers: data === undefined ? {} : {'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8'},
        body: data === undefined ? undefined : new URLSearchParams(data).toString(),
        cache: 'no-store', signal: ctrl.signal
      });
      if (!response.ok) throw new Error('本地服务返回 ' + response.status + '，请重新打开注入器。');
      const result = await response.json();
      if (result.ok === false && name !== 'autologin') throw new Error(result.error || '操作未完成，请重试。');
      return result;
    } catch (error) {
      if (error.name === 'AbortError') throw new Error('请求超时，请检查连接后重试。');
      if (error instanceof TypeError) throw new Error('连接本地服务失败，请确认注入器仍在运行。');
      throw error;
    } finally { clearTimeout(timer); }
  }
  function text(id, value) { const node = $(id); const next = String(value == null ? '' : value); if (node.textContent !== next) node.textContent = next; }
  function motion(el, signature) {
    if (reduced.matches || document.body.classList.contains('noanim') || !el.animate) return;
    const previous = animations.get(el); if (previous) previous.cancel();
    const animation = el.animate(signature ? [{clipPath:'inset(0 0 100% 0)',transform:'translateY(5px)'},{clipPath:'inset(0)',transform:'translateY(0)'}] : [{opacity:.65,transform:'translateY(6px)'},{opacity:1,transform:'translateY(0)'}], {duration:signature ? 260 : 180,easing:'cubic-bezier(.16,1,.3,1)'});
    animations.set(el, animation);
  }
  function syncMotion(enabled) {
    document.body.classList.toggle('noanim', !enabled);
    if (!enabled || reduced.matches) document.getAnimations().forEach(a => a.cancel());
  }
  reduced.addEventListener('change', () => syncMotion(state ? state.anim : true));
  function toast(message) { clearTimeout(toastTimer); text('toast', message); $('toast').hidden = false; toastTimer = setTimeout(() => { $('toast').hidden = true; }, 3200); }
  function authStatus(message, kind) { text('loginStatus', message); $('loginStatus').className = 'form-status' + (kind ? ' ' + kind : ''); }
  function setConnection(available) {
    const app = document.querySelector('.app');
    const overlay = $('disconnect');
    if (!available && !disconnected) {
      recoveryFocus = app.contains(document.activeElement) ? document.activeElement : null;
      disconnected = true;
      app.inert = true;
      overlay.hidden = false;
      overlay.focus({preventScroll:true});
    } else if (available && disconnected) {
      disconnected = false;
      app.inert = false;
      overlay.hidden = true;
      if (recoveryFocus && recoveryFocus.isConnected && !recoveryFocus.disabled && recoveryFocus.getClientRects().length) {
        recoveryFocus.focus({preventScroll:true});
      }
      recoveryFocus = null;
    }
  }
  function uptime(seconds) { const s = Math.max(0, Math.floor(Number(seconds) || 0)); return [Math.floor(s/3600),Math.floor(s/60)%60,s%60].map(n => String(n).padStart(2,'0')).join(':'); }
  function processLabel(p) { if (!p) return '等待 Minecraft'; if (!p.mc) return 'Java 进程'; return /forge|modlauncher|bootstraplauncher/i.test(p.name) ? 'Minecraft · Forge' : 'Minecraft'; }
  function renderProcesses(procs, busy) {
    const signature = JSON.stringify([procs, busy, selectedPid]);
    if (signature === lastProcs) return; lastProcs = signature;
    $('procBody').innerHTML = procs.length ? procs.map(p => '<tr><td><div class="process-name">' + escape(p.name || 'Java') + '</div><span class="process-id">PID ' + escape(p.pid) + '</span></td><td><span class="process-type' + (p.mc ? ' minecraft' : '') + '">' + (p.mc ? 'Minecraft' : '其他 JVM') + '</span></td><td><button class="text-button" data-pid="' + escape(p.pid) + '"' + (busy ? ' disabled' : '') + '>' + (String(p.pid) === selectedPid ? '已选择' : '选择') + '</button></td></tr>').join('') : '<tr><td colspan="3" class="empty-state">尚未检测到 Java 进程。请先启动 Minecraft。</td></tr>';
    text('procHint', procs.length ? '检测到 ' + procs.length + ' 个 Java 进程。选中后，在连接页面确认并注入。' : '如果游戏已经运行，请重新检测，或打开环境诊断查看运行环境。');
    const options = procs.filter(p => p.mc || String(p.pid) === selectedPid);
    const optionSignature = JSON.stringify(options);
    if ($('targetSelect').dataset.signature !== optionSignature) {
      $('targetSelect').innerHTML = options.map(p => '<option value="' + escape(p.pid) + '">PID ' + escape(p.pid) + ' · ' + escape(p.name || 'Java') + '</option>').join('');
      $('targetSelect').dataset.signature = optionSignature;
    }
    $('targetSelect').value = selectedPid;
    $('targetField').hidden = options.length < 2;
    $('targetSelect').disabled = busy;
  }
  function render(s) {
    const inj = s.inject || {}, procs = s.procs || [], stats = s.stats || {};
    const busy = !!inj.busy || requestingInject;
    // A selected process is local UI intent. The operation PID is reported by the backend separately.
    if (!busy && !procs.some(p => String(p.pid) === selectedPid)) selectedPid = procs.some(p => String(p.pid) === String(inj.pid)) ? String(inj.pid) : String((procs.find(p => p.mc) || {}).pid || '');
    const operationPid = requestingInject && !inj.busy ? attemptedPid : String(inj.operationPid || attemptedPid || inj.pid || '');
    const targetPid = busy ? operationPid || selectedPid : selectedPid;
    const target = procs.find(p => String(p.pid) === targetPid) || (targetPid ? {pid:targetPid,name:inj.target || '',mc:true} : null);
    const sameTarget = !!target && String(target.pid) === operationPid;
    const error = !busy && sameTarget && inj.error ? inj.error : '';
    const done = sameTarget && !error && !busy && !!inj.result;
    const kind = error ? 'error' : busy ? 'busy' : done ? 'success' : target ? 'ready' : 'waiting';
    $('session').dataset.state = kind;
    $('sessionStatus').className = 'session-status ' + kind;
    text('gameName', processLabel(target));
    text('gameMeta', target ? 'PID ' + target.pid + (target.name ? ' · ' + target.name : '') : '启动游戏后，这里会显示可用进程。');
    text('sessionTag', {error:'连接失败',busy:'正在连接',success:'已连接',ready:'准备就绪',waiting:'等待游戏'}[kind]);
    text('actionTitle', {error:'检查后再试一次',busy:'正在为游戏加载组件',success:'回到游戏，开始使用',ready:'连接到这个游戏',waiting:'先打开 Minecraft'}[kind]);
    text('actionNote', {error:'错误详情与诊断入口就在下方。',busy:'保持游戏运行，稍等片刻。',success:'进入世界后，按右 Shift 打开面板。',ready:target && !target.mc ? '这是手动选择的 Java 进程，请确认目标。' : '保持游戏运行，点击「开始注入」。',waiting:'保持游戏运行，然后重新检测。'}[kind]);
    text('injectLabel', busy ? '正在注入…' : done ? '再次注入' : error ? '重新注入' : '开始注入');
    $('btnInject').disabled = busy || !target || !connected;
    $('btnScan').disabled = $('btnRescan').disabled = busy || scanning;
    const pct = requestingInject && !inj.busy ? 0 : Math.max(0,Math.min(100,Number(inj.pct) || 0));
    $('injectProgress').hidden = !busy && !done;
    $('injectProgress').setAttribute('aria-valuenow', String(pct));
    $('mBar').style.width = pct + '%';
    text('mPct', busy || done ? pct + '%' : '');
    mood(kind, pct);
    const title = error ? '注入未完成' : busy ? (requestingInject && !inj.busy ? '正在连接游戏' : inj.step || '正在连接游戏') : done ? '注入完成' : target ? '游戏已就绪' : inj.scanned ? '暂未找到游戏' : '正在检测游戏进程';
    text('mTitle', title);
    text('mSub', error || (done ? inj.result : busy ? 'PID ' + targetPid + ' · 请勿关闭游戏或注入器。' : target ? '也可以在进程页面手动选择其他目标。' : '启动 Minecraft 后，点击重新检测。'));
    $('errorHelp').hidden = !error;
    const nextStateKey = [kind,targetPid].join('|');
    if (nextStateKey !== stateKey) { if (stateKey && entered) motion($('mTitle'), true); stateKey = nextStateKey; }
    renderProcesses(procs, busy);
    const lines = s.lines || [], linesSignature = JSON.stringify(lines);
    if (linesSignature !== lastLines) {
      lastLines = linesSignature;
      $('hpLines').innerHTML = lines.length ? lines.map(line => { const split = line.indexOf('  '); return '<div class="activity-line"><time>' + escape(split >= 0 ? line.slice(0,split) : '') + '</time><span>' + escape(split >= 0 ? line.slice(split+2) : line) + '</span></div>'; }).join('') : '<p>暂无活动记录。</p>';
      text('activityCount', lines.length);
    }
    text('acUser', s.loggedIn ? s.user : '未登录');
    text('accountInitial', (s.user || 'A').slice(0,1).toUpperCase());
    text('acMode', s.mode === 'online' ? '在线验证' : '离线模式');
    text('acToken', s.hasToken ? '已保存，可自动登录' : '未保存');
    if (subscriptionOwner !== (s.user || '')) {
      subscriptionOwner = s.user || ''; subscriptionData = null; accountRequest++;
      text('acExpiry','进入账号页查看'); text('acRemaining','—'); text('redeemStatus',''); $('redeemCode').value = '';
    }
    $('btnRedeem').disabled = redeemBusy || !s.loggedIn || s.mode !== 'online' || !!(subscriptionData && subscriptionData.permanent);
    $('redeemCode').disabled = redeemBusy || s.mode !== 'online' || !!(subscriptionData && subscriptionData.permanent);
    $('btnLogout').disabled = redeemBusy;
    if ($('machine').value !== (s.machine || '无法读取')) $('machine').value = s.machine || '无法读取';
    text('accountMachine', s.machine || '无法读取');
    $('btnMachine').disabled = $('btnAccountMachine').disabled = !s.machine;
    text('machineHint', s.machine ? '换电脑时，将机器码发给管理员换绑。' : '未能读取机器码，请联系管理员。');
    if (!settingsPending && !settingsSaving) {
      $('setAnim').checked = !!s.anim;
      if (!rangeEditing) { $('setLog').value = s.logKeep; text('setLogV', s.logKeep); }
      syncMotion(s.anim);
    }
    text('tCpu', stats.cpu >= 0 ? Math.round(stats.cpu*100) + '%' : '不可用');
    text('tMem', stats.physTotal > 0 ? (stats.physUsed/1073741824).toFixed(1) + ' GB' : '不可用');
    text('tSig', stats.ping >= 0 ? stats.ping + ' ms' : stats.ping === -2 ? '未连接' : '检测中');
    text('footerAccount', s.loggedIn ? s.user : '尚未登录');
    text('footerRuntime', '运行时间 ' + uptime(inj.uptime));
    text('connectionText', busy ? '正在连接游戏' : '本地服务已连接');
    $('connectionDot').className = 'status-dot connected';
    const runtime = navigator.userAgent.match(/(?:Edg|Chrome)\/[\d.]+/);
    text('abJvm', runtime ? runtime[0] : 'Chromium'); text('abOs', navigator.platform || 'Windows');
  }
  async function poll() {
    if (polling) return; polling = true;
    try { const s = await api('state'); connected = true; state = s; render(s); setConnection(true); }
    catch (error) { connected = false; setConnection(false); $('btnInject').disabled = true; text('connectionText','本地服务已断开'); $('connectionDot').className = 'status-dot error'; }
    finally { polling = false; }
  }
  async function pollLoop() { await poll(); setTimeout(pollLoop,700); }
  function switchTo(name, focus) {
    if (!pages.includes(name)) name = 'inject';
    const changed = name !== currentPage; currentPage = name;
    document.querySelectorAll('[data-page]').forEach(b => { const active = b.dataset.page === name; b.classList.toggle('on', active); if (active) b.setAttribute('aria-current','page'); else b.removeAttribute('aria-current'); });
    document.querySelectorAll('[data-panel]').forEach(p => { p.hidden = p.dataset.panel !== name; });
    const panel = document.querySelector('[data-panel="' + name + '"]');
    if (changed) reveal(panel);
    placeIndicator(name);
    if (focus) { const h = panel.querySelector('h1'); h.tabIndex = -1; h.focus({preventScroll:true}); }
    if (name === 'log') loadLog(); if (name === 'diag') loadDiag(); if (name === 'account') loadSubscription();
  }
  document.addEventListener('click', e => {
    const nav = e.target.closest('[data-page],[data-go]');
    if (nav) switchTo(nav.dataset.page || nav.dataset.go, !!nav.dataset.go);
    const choose = e.target.closest('[data-pid]');
    if (choose && !choose.disabled) { selectedPid = choose.dataset.pid; if (state) render(state); switchTo('inject',true); }
  });
  document.querySelector('.wordmark').addEventListener('click', e => { e.preventDefault(); if (entered) switchTo('inject'); });
  $('targetSelect').addEventListener('change', () => { selectedPid = $('targetSelect').value; if (state) render(state); motion($('gameName'),true); });
  function stopCountdown() { clearInterval(countdownTimer); countdownTimer = null; $('countdown').hidden = true; }
  function enterApp() { stopCountdown(); if (entered) return; entered = true; $('viewLogin').hidden = true; $('viewApp').hidden = false; switchTo(initialPage); motion($('viewApp'),false); }
  function setRegMode(on) {
    if (authBusy) return;
    authEpoch++; authTouched = true; stopCountdown(); regMode = on;
    $('fInvite').hidden = !on; $('invite').required = on; $('rowRemember').hidden = on; $('regNote').hidden = !on;
    $('tabLogin').classList.toggle('on',!on); $('tabReg').classList.toggle('on',on);
    $('tabLogin').setAttribute('aria-pressed',String(!on)); $('tabReg').setAttribute('aria-pressed',String(on));
    text('gateTitle',on ? '创建你的账号' : '登录 AZclient'); text('gateSub',on ? '使用兑换码注册并绑定本机。' : '使用你的账号继续。');
    $('btnLogin').querySelector('span').textContent = on ? '注册并继续' : '登录并继续';
    $('pass').autocomplete = on ? 'new-password' : 'current-password';
    authStatus(on ? '同一种兑换码可用于注册或为已有账号续费。' : '请输入账号和密码');
  }
  $('tabLogin').onclick = () => setRegMode(false); $('tabReg').onclick = () => setRegMode(true);
  ['user','pass','invite'].forEach(id => $(id).addEventListener('input', () => { authTouched = true; authEpoch++; stopCountdown(); }));
  $('authForm').addEventListener('submit', async e => {
    e.preventDefault(); if (authBusy) return;
    authTouched = true; authEpoch++; stopCountdown();
    const user = $('user').value.trim(), pass = $('pass').value, invite = $('invite').value.trim();
    if (!user || !pass) return authStatus('请填写账号和密码。','error');
    if (regMode && !invite) return authStatus('请填写兑换码。','error');
    if (regMode && !(state && state.machine)) return authStatus('未能读取机器码，无法绑定账号。请联系管理员。','error');
    authBusy = true; $('btnLogin').disabled = $('tabLogin').disabled = $('tabReg').disabled = true;
    authStatus(regMode ? '正在注册并绑定本机…' : '正在登录…');
    try { await api(regMode ? 'register' : 'login',{user,pass,invite,remember:regMode || $('remember').checked ? '1' : '0'}); authStatus('登录成功','success'); await poll(); enterApp(); }
    catch (error) { authStatus(error.message,'error'); }
    finally { authBusy = false; $('btnLogin').disabled = $('tabLogin').disabled = $('tabReg').disabled = false; }
  });
  async function logout(clearAccount) {
    if (authBusy || redeemBusy) return;
    authEpoch++; authTouched = true; stopCountdown();
    try { await api('logout'); entered = false; attemptedPid = ''; selectedPid = ''; $('viewApp').hidden = true; $('viewLogin').hidden = false; $('pass').value = ''; if (clearAccount) $('user').value = ''; setRegMode(false); authStatus('已退出登录，请输入账号和密码。'); await poll(); motion($('viewLogin'),false); }
    catch (error) { entered ? toast(error.message) : authStatus(error.message,'error'); }
  }
  $('btnLogout').onclick = () => logout(false); $('btnForget').onclick = () => logout(true);
  function showSubscription(result) {
    subscriptionData = result;
    text('acExpiry',result.permanent ? '永久有效' : new Date(result.expiresAt).toLocaleString('zh-CN',{hour12:false}) + '（本地时间）');
    const remaining = result.expiresAt - result.serverNow;
    text('acRemaining',result.permanent ? '无需续费' : remaining <= 0 ? '已到期，可在下方兑换续费' : remaining < 86400000 ? '不足 1 天' : '约 ' + Math.floor(remaining/86400000) + ' 天');
    $('acRemaining').className = result.expired ? 'failure' : '';
    if (state) render(state);
  }
  async function loadSubscription() {
    if (redeemBusy) return;
    const request = ++accountRequest;
    $('btnAccountRefresh').disabled = true; text('acExpiry','正在读取…'); text('acRemaining','—');
    try { const result = await api('account'); if (request === accountRequest) showSubscription(result); }
    catch (error) { if (request === accountRequest) { text('acExpiry',error.message); text('acRemaining','点击刷新重试'); } }
    finally { if (request === accountRequest) $('btnAccountRefresh').disabled = false; }
  }
  $('btnAccountRefresh').onclick = loadSubscription;
  $('redeemForm').addEventListener('submit', async event => {
    event.preventDefault();
    if (redeemBusy || $('btnRedeem').disabled) return;
    const invite = $('redeemCode').value.trim();
    if (!invite) { text('redeemStatus','请输入兑换码。'); $('redeemCode').focus(); return; }
    redeemBusy = true; accountRequest++;
    $('btnAccountRefresh').disabled = true; $('btnRedeem').textContent = '正在兑换…';
    $('redeemStatus').className = 'form-status'; text('redeemStatus','正在为当前账号兑换，请稍候。'); if (state) render(state);
    try {
      const result = await api('redeem',{invite}); showSubscription(result);
      text('redeemStatus',result.replayed ? '此码此前已兑换成功，未重复扣码或加时；上方为当前有效期。' : result.permanent ? '兑换成功，账号已永久有效。' : '兑换成功，已增加 ' + result.days + ' 天；上方有效期已更新。');
      $('redeemStatus').className = 'form-status success'; $('redeemCode').value = ''; motion($('redeemStatus'),true);
    } catch (error) { text('redeemStatus',error.message); $('redeemStatus').className = 'form-status error'; }
    finally { redeemBusy = false; $('btnAccountRefresh').disabled = false; $('btnRedeem').textContent = '兑换并续费'; if (state) render(state); }
  });
  $('btnEnterNow').onclick = enterApp;
  async function copy(value, label) {
    if (!value) return toast('没有可复制的内容。');
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) await navigator.clipboard.writeText(value);
      else { const field = document.createElement('textarea'); field.value = value; field.style.cssText = 'position:fixed;left:-9999px'; document.body.appendChild(field); field.select(); const ok = document.execCommand('copy'); field.remove(); if (!ok) throw new Error('copy'); }
      toast(label + '已复制');
    } catch (error) { toast('复制失败，请选中内容后按 Ctrl+C。'); }
  }
  $('btnMachine').onclick = $('btnAccountMachine').onclick = () => copy(state && state.machine,'机器码');
  async function scan() { if (scanning || state && state.inject.busy) return; scanning = true; if (state) render(state); try { await api('scan'); await poll(); } catch (error) { toast(error.message); } finally { scanning = false; if (state) render(state); } }
  $('btnScan').onclick = $('btnRescan').onclick = scan;
  $('btnInject').onclick = async () => {
    if (!selectedPid || requestingInject || state && state.inject.busy) return;
    attemptedPid = selectedPid; requestingInject = true; if (state) render(state);
    try { await api('inject',{pid:selectedPid}); await poll(); }
    catch (error) { toast(error.message); }
    finally { requestingInject = false; if (state) render(state); }
  };
  async function loadLog() {
    const request = ++logRequest, which = logWhich;
    $('btnLogInjector').classList.toggle('on',which === 'injector'); $('btnLogAgent').classList.toggle('on',which === 'agent');
    $('btnLogInjector').setAttribute('aria-pressed',String(which === 'injector')); $('btnLogAgent').setAttribute('aria-pressed',String(which === 'agent'));
    text('logPath','正在读取…');
    try { const r = await api('logs',{which}); if (request !== logRequest) return; logPath = r.path || ''; text('logPath',logPath + (r.exists ? ' · ' + r.size + ' 字节' : ' · 文件尚未生成')); text('logText',r.text || '暂无日志内容。'); $('logText').scrollTop = $('logText').scrollHeight; }
    catch (error) { if (request === logRequest) { logPath = ''; text('logPath','读取失败'); text('logText',error.message); } }
  }
  $('btnLogInjector').onclick = () => { logWhich = 'injector'; loadLog(); }; $('btnLogAgent').onclick = () => { logWhich = 'agent'; loadLog(); };
  $('btnLogRefresh').onclick = loadLog; $('btnLogCopy').onclick = () => copy($('logText').textContent,'日志内容'); $('btnLogDir').onclick = () => copy(logPath,'日志路径');
  async function loadDiag() {
    const request = ++diagRequest; $('diagBody').innerHTML = '<div><dt>环境检查</dt><dd>正在检查…</dd></div>'; $('btnDiag').disabled = true;
    try { const r = await api('diag'); if (request !== diagRequest) return; $('diagBody').innerHTML = (r.items || []).map(item => '<div><dt>' + escape(item.name) + '</dt><dd class="diag-result">' + (item.pass ? '<svg class="icon" role="img" aria-label="通过"><use href="#i-check"/></svg>' : '<span class="failure">未通过</span>') + '<span>' + escape(item.value) + '</span></dd></div>').join('') || '<div><dt>检查结果</dt><dd>暂无检查项。</dd></div>'; }
    catch (error) { if (request === diagRequest) $('diagBody').innerHTML = '<div><dt>检查失败</dt><dd>' + escape(error.message) + '</dd></div>'; }
    finally { if (request === diagRequest) $('btnDiag').disabled = false; }
  }
  $('btnDiag').onclick = loadDiag;
  async function saveSettings() {
    settingsPending = {anim:$('setAnim').checked ? '1' : '0',logLines:$('setLog').value};
    syncMotion(settingsPending.anim === '1'); text('settingsStatus','正在保存…'); $('settingsStatus').className = 'save-status';
    if (settingsSaving) return;
    settingsSaving = true;
    try { while (settingsPending) { const next = settingsPending; settingsPending = null; await api('settings',next); } text('settingsStatus','已保存'); }
    catch (error) { settingsPending = null; text('settingsStatus','保存失败，请重试'); $('settingsStatus').className = 'save-status error'; toast(error.message); }
    finally { settingsSaving = false; await poll(); }
  }
  $('setAnim').onchange = saveSettings;
  $('setLog').oninput = () => { rangeEditing = true; text('setLogV',$('setLog').value); };
  $('setLog').onchange = () => { rangeEditing = false; saveSettings(); };
  $('setLog').onblur = () => { rangeEditing = false; };
  window.addEventListener('pagehide', () => { try { navigator.sendBeacon('/api/bye?t=' + encodeURIComponent(token)); } catch (_) {} });
  async function autoLogin() {
    const epoch = authEpoch;
    try {
      const result = await api('autologin');
      if (epoch !== authEpoch || authTouched || entered) return;
      if (result.user) $('user').value = result.user;
      if (!result.ok) { if (result.error && result.error !== '没有保存的登录') authStatus(result.error,'error'); return; }
      let remaining = 5; $('countdown').hidden = false; authStatus('已恢复保存的登录。','success');
      function tick() { text('cdTxt',remaining + ' 秒后进入'); $('cdBar').style.width = remaining/5*100 + '%'; }
      tick(); countdownTimer = setInterval(() => { remaining--; tick(); if (remaining <= 0) enterApp(); },1000);
    } catch (error) { if (epoch === authEpoch && !authTouched) authStatus(error.message,'error'); }
  }
  /* ── Atmosphere ─────────────────────────────────────────────
     Presentation only: nothing below changes runtime state. */
  function canAnimate() { return !reduced.matches && !document.body.classList.contains('noanim'); }

  // The sun follows the session: low while waiting, rising with progress, fully up on success.
  let lastMood = '';
  function mood(kind, pct) {
    const root = document.documentElement;
    const lift = kind === 'busy' ? .34 + .6 * (pct / 100) : ({waiting:.12, ready:.32, error:.2, success:1})[kind];
    root.style.setProperty('--lift', lift.toFixed(3));
    if (kind === lastMood) return;
    root.dataset.mood = kind;
    if (kind === 'success' && lastMood === 'busy' && canAnimate()) {
      const s = $('session'); s.classList.remove('burst'); void s.offsetWidth; s.classList.add('burst');
      setTimeout(() => s.classList.remove('burst'), 1400);
    }
    lastMood = kind;
  }

  // Panel children arrive in a short cascade when switching pages.
  function reveal(panel) {
    if (!canAnimate() || !panel || !panel.animate) return;
    Array.from(panel.children).forEach((el, i) => {
      const previous = animations.get(el); if (previous) previous.cancel();
      animations.set(el, el.animate(
        [{opacity:0, transform:'translateY(14px)', filter:'blur(5px)'}, {opacity:1, transform:'none', filter:'blur(0)'}],
        {duration:640, delay:Math.min(i, 6) * 65, easing:'cubic-bezier(.16,1,.3,1)', fill:'backwards'}));
    });
  }

  // A single highlight slides between navigation items.
  let indicatorPlaced = false;
  function placeIndicator(name, instant) {
    const nav = document.querySelector('.navigation'), ind = $('navIndicator');
    const btn = nav && nav.querySelector('.nav-button[data-page="' + name + '"]');
    if (!btn || !nav.getClientRects().length) return;
    // offset* ignore the nav's own entrance transform, so the highlight lands exactly.
    const jump = instant || !indicatorPlaced;
    if (jump) ind.classList.add('instant');
    ind.style.width = btn.offsetWidth + 'px'; ind.style.height = btn.offsetHeight + 'px';
    ind.style.transform = 'translate(' + btn.offsetLeft + 'px,' + btn.offsetTop + 'px)';
    ind.style.opacity = '1';
    if (jump) { void ind.offsetWidth; ind.classList.remove('instant'); }
    indicatorPlaced = true;
  }
  addEventListener('resize', () => { if (entered) placeIndicator(currentPage, true); });

  // Light follows the pointer across filled buttons; the sky leans gently with it.
  let parallaxFrame = 0, px = 0, py = 0;
  document.addEventListener('pointermove', e => {
    const button = e.target.closest && e.target.closest('.button');
    if (button) { const r = button.getBoundingClientRect(); button.style.setProperty('--mx', (e.clientX - r.left) + 'px'); button.style.setProperty('--my', (e.clientY - r.top) + 'px'); }
    px = e.clientX / innerWidth * 2 - 1; py = e.clientY / innerHeight * 2 - 1;
    if (!parallaxFrame && canAnimate()) parallaxFrame = requestAnimationFrame(() => {
      parallaxFrame = 0;
      document.documentElement.style.setProperty('--px', px.toFixed(3));
      document.documentElement.style.setProperty('--py', py.toFixed(3));
    });
  }, {passive:true});

  // Warm dust drifting up from the horizon.
  (function motes() {
    const canvas = $('motes'); if (!canvas || !canvas.getContext) return;
    const ctx = canvas.getContext('2d'); const parts = []; let w = 0, h = 0, raf = 0;
    function size() { const dpr = Math.min(2, devicePixelRatio || 1); w = canvas.clientWidth; h = canvas.clientHeight; canvas.width = w * dpr; canvas.height = h * dpr; ctx.setTransform(dpr, 0, 0, dpr, 0, 0); }
    function spawn(p, initial) {
      p.x = Math.random() * w; p.y = initial ? h * (.3 + Math.random() * .52) : h * (.76 + Math.random() * .08);
      p.r = .5 + Math.random() * 1.5; p.vy = -(.05 + Math.random() * .2); p.vx = (Math.random() - .5) * .1;
      p.life = initial ? Math.random() * 400 : 0; p.max = 700 + Math.random() * 900; p.tw = Math.random() * 6.28; return p;
    }
    function live() { return !document.hidden && canAnimate(); }
    function draw(step) {
      ctx.clearRect(0, 0, w, h); ctx.fillStyle = '#f2d2ac';
      const lift = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--lift')) || .12;
      for (const p of parts) {
        if (step) { p.x += p.vx; p.y += p.vy; p.life++; p.tw += .025; if (p.life > p.max || p.y < -8) spawn(p, false); }
        const fade = Math.max(0, Math.min(1, p.life / 140, (p.max - p.life) / 180));
        ctx.globalAlpha = fade * (.25 + .2 * Math.sin(p.tw)) * (.55 + lift * .6);
        ctx.beginPath(); ctx.arc(p.x, p.y, p.r, 0, 6.2832); ctx.fill();
      }
      ctx.globalAlpha = 1;
    }
    function frame() { raf = 0; draw(true); if (live()) raf = requestAnimationFrame(frame); }
    function wake() { if (live()) { if (!raf) raf = requestAnimationFrame(frame); } else { if (raf) cancelAnimationFrame(raf); raf = 0; draw(false); } }
    size(); for (let i = 0; i < 38; i++) parts.push(spawn({}, true));
    addEventListener('resize', () => { size(); draw(false); });
    document.addEventListener('visibilitychange', wake);
    reduced.addEventListener('change', wake);
    new MutationObserver(wake).observe(document.body, {attributes:true, attributeFilter:['class']});
    wake();
  })();

  enterApp(); pollLoop();
})();
