(ns darelwasl.webterm.ui
  (:require [clojure.string :as str]
            [darelwasl.webterm.tmux :as tmux])
  (:import (java.time Instant)))

(defn ui-url
  [{:keys [public-base-path]} path]
  (let [p (or path "/")
        p (if (or (str/starts-with? p "http://") (str/starts-with? p "https://")) p (if (str/starts-with? p "/") p (str "/" p)))]
    (if (str/blank? public-base-path)
      p
      (if (= p "/") (str public-base-path "/") (str public-base-path p)))))

(defn xterm-url
  [cfg n]
  (str "/xterm/?arg=" (tmux/session-name cfg n)))

(defn terminals-page
  [cfg]
  (let [{:keys [terminal-count lab-stable-session lab-canary-session]} cfg
        sessions (tmux/list-sessions cfg)
        rows (for [n (range 1 (inc terminal-count))]
               (let [name (tmux/session-name cfg n)
                     exists (contains? sessions name)
                     status (if exists "running" "empty")
                     is-stable (= n lab-stable-session)
                     is-canary (= n lab-canary-session)
                     marker (cond is-stable " (lab stable)" is-canary " (lab canary)" :else "")
                     row-style (cond is-stable " style=\"background:#fffbe6\"" is-canary " style=\"background:#e6f4ff\"" :else "")]
                 (str "<tr" row-style ">"
                      "<td>" n "</td>"
                      "<td><code>" name "</code>" marker "</td>"
                      "<td>" status "</td>"
                      "<td>"
                      "<a href=\"" (xterm-url cfg n) "\" target=\"_blank\" rel=\"noreferrer\">open</a> | "
                      "<a href=\"" (ui-url cfg (str "/open?n=" n)) "\">open+create</a> | "
                      "<a href=\"" (ui-url cfg (str "/codex?n=" n)) "\">start codex</a> | "
                      "<a href=\"/t" n "\" target=\"_blank\" rel=\"noreferrer\">legacy</a> | "
                      "<a href=\"" (ui-url cfg (str "/kill?n=" n)) "\" onclick=\"return confirm('Kill " name "?')\">kill</a>"
                      (when (or is-stable is-canary)
                        (str " | <a href=\"" (ui-url cfg (str "/lab?session=" n)) "\" target=\"_blank\" rel=\"noreferrer\">lab</a>"))
                      "</td>"
                      "</tr>")))]
    (str "<!doctype html><html><head><meta charset=\"utf-8\" />"
         "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\" />"
         "<title>Terminals</title>"
         "<style>body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;margin:24px}"
         "table{border-collapse:collapse;width:100%;max-width:1050px}th,td{border:1px solid #ddd;padding:8px}"
         "th{background:#f6f6f6;text-align:left}code{background:#f2f2f2;padding:2px 4px;border-radius:4px}</style>"
         "</head><body>"
         "<h1>Web terminals</h1>"
         "<div class=\"bar\">"
         "<a href=\"" (ui-url cfg "/new") "\">New terminal (next free)</a> "
         "<a href=\"" (ui-url cfg (str "/lab?session=" lab-stable-session)) "\" target=\"_blank\" rel=\"noreferrer\">Lab (stable " lab-stable-session ")</a> "
         "<a href=\"" (ui-url cfg (str "/lab?session=" lab-canary-session)) "\" target=\"_blank\" rel=\"noreferrer\">Lab (canary " lab-canary-session ")</a> "
         "<a href=\"" (ui-url cfg "/") "\">Refresh</a>"
         "</div>"
         "<p><b>Open</b> uses ttyd (xterm.js). <b>legacy</b> is the old shellinabox terminal.</p>"
         "<table><thead><tr><th>#</th><th>tmux</th><th>status</th><th>actions</th></tr></thead><tbody>"
         (apply str rows)
         "</tbody></table></body></html>")))

(defn lab-page
  [cfg {:keys [sess build-stamp ui-role]}]
  (let [{:keys [lab-stable-session lab-canary-session terminal-count lab-max-upload-bytes lab-default-history-lines tmux-prefix]} cfg
        stable-href (str "/lab?session=" sess)
        canary-href (str "/canary/lab?session=" sess)
        other (if (= ui-role "canary") {:label "Stable" :href stable-href} {:label "Canary" :href canary-href})
        sname (tmux/session-name cfg sess)
        iframe-src (xterm-url cfg sess)]
    (str "<!doctype html><html><head><meta charset=\"utf-8\" />"
         "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\" />"
         "<title>Lab · " (str/escape sname {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;"}) "</title>"
         "<style>"
         ":root{--bg:#050a14;--bar:rgba(11,18,32,0.86);--border:rgba(255,255,255,0.12);--text:rgba(255,255,255,0.92);--muted:rgba(255,255,255,0.65);--accent:#7dd3fc;--mono:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,\"Liberation Mono\",\"Courier New\",monospace;--sans:system-ui,-apple-system,Segoe UI,Roboto,sans-serif}"
         "*{box-sizing:border-box}html,body{height:100%;margin:0;background:var(--bg);color:var(--text);font-family:var(--sans)}"
         "a{color:var(--accent);text-decoration:none}a:hover{text-decoration:underline}"
         ".bar{position:fixed;top:0;left:0;right:0;height:46px;display:flex;align-items:center;gap:8px;padding:8px 10px;background:var(--bar);border-bottom:1px solid var(--border);backdrop-filter:blur(8px);-webkit-backdrop-filter:blur(8px);z-index:10}"
         ".chip{font-family:var(--mono);font-size:11px;padding:2px 8px;border-radius:999px;border:1px solid var(--border);color:var(--muted)}"
         ".sp{flex:1}"
         ".btn{appearance:none;border:1px solid var(--border);background:rgba(255,255,255,0.06);color:var(--text);padding:6px 10px;border-radius:10px;font-size:12px;cursor:pointer}"
         ".btn:hover{background:rgba(255,255,255,0.10)}"
         ".btn.primary{border-color:rgba(125,211,252,0.45);background:rgba(125,211,252,0.14)}"
         ".term{position:fixed;top:46px;left:0;right:0;bottom:0}"
         "iframe{width:100%;height:100%;border:0;background:#000}"
         ".overlay{position:fixed;inset:0;display:none;background:rgba(0,0,0,0.55);z-index:30}"
         ".overlay.open{display:block}"
         ".panel{position:absolute;top:0;bottom:0;right:0;width:100%;max-width:100%;background:rgba(11,18,32,0.96);border-left:1px solid var(--border);display:flex;flex-direction:column}"
         "@media(min-width:980px){.panel{width:50vw;max-width:760px}}"
         ".ph{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:10px;border-bottom:1px solid var(--border);background:rgba(255,255,255,0.04)}"
         ".pt{font-family:var(--mono);font-size:12px;color:var(--text);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}"
         ".pb{padding:12px;overflow:auto}"
         ".row{display:flex;gap:8px;align-items:center;flex-wrap:wrap}"
         "input,textarea,select{width:100%;background:rgba(0,0,0,0.22);border:1px solid var(--border);color:var(--text);border-radius:12px;padding:10px;outline:none}"
         "textarea{min-height:120px;font-family:var(--mono);font-size:12px;line-height:1.35;resize:vertical}"
         ".list{list-style:none;padding:0;margin:0;display:flex;flex-direction:column;gap:8px}"
         ".item{display:flex;justify-content:space-between;gap:10px;align-items:center;border:1px solid var(--border);border-radius:12px;padding:10px;background:rgba(255,255,255,0.04)}"
         ".name{font-family:var(--mono);font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:60vw}"
         "@media(min-width:980px){.name{max-width:22vw}}"
         ".sub{font-size:12px;color:var(--muted)}"
         ".paper{background:#fff;color:#111;border-radius:14px;box-shadow:0 22px 60px rgba(0,0,0,0.45);border:1px solid rgba(0,0,0,0.08);overflow:hidden}"
         ".paper iframe{height:76vh;background:#fff}"
         ".paper pre{margin:0;padding:12px;font-family:var(--mono);font-size:13px;line-height:1.45;white-space:pre;overflow:auto}"
         "</style></head><body>"
         "<div class=\"bar\">"
         "<span class=\"chip\">ui " ui-role "</span>"
         "<span class=\"chip\">build " build-stamp "</span>"
         "<span class=\"chip\">stable " lab-stable-session "</span>"
         "<span class=\"chip\">canary " lab-canary-session "</span>"
         "<span class=\"chip\">active <span id=\"active-session\">" sess "</span></span>"
         "<span class=\"chip\"><code id=\"active-tmux\">" sname "</code></span>"
         "<span class=\"sp\"></span>"
         "<button class=\"btn\" id=\"b-files\" type=\"button\">Files</button>"
         "<button class=\"btn\" id=\"b-add\" type=\"button\">Add</button>"
         "<button class=\"btn\" id=\"b-paste\" type=\"button\">Paste</button>"
         "<button class=\"btn\" id=\"b-history\" type=\"button\">History</button>"
         "<button class=\"btn\" id=\"b-session\" type=\"button\">Session</button>"
         "<button class=\"btn\" id=\"b-clear\" type=\"button\">Clear</button>"
         "<a class=\"btn\" href=\"" (ui-url cfg "/") "\">Terminals</a>"
         "<a class=\"btn\" href=\"" (:href other) "\" rel=\"noreferrer\">" (:label other) "</a>"
         "<a class=\"btn primary\" id=\"open-term\" href=\"" iframe-src "\" target=\"_blank\" rel=\"noreferrer\">Open</a>"
         "</div>"
         "<div class=\"term\"><iframe id=\"term\" src=\"" iframe-src "\" title=\"terminal\"></iframe></div>"
         "<div class=\"overlay\" id=\"ov\"><div class=\"panel\">"
         "<div class=\"ph\"><div class=\"pt\" id=\"pt\">Panel</div>"
         "<div class=\"row\"><a class=\"btn\" id=\"p-dl\" href=\"#\" rel=\"noreferrer\" style=\"display:none\">Download</a>"
         "<button class=\"btn\" id=\"p-close\" type=\"button\">Close</button></div></div>"
         "<div class=\"pb\" id=\"pb\"></div>"
         "</div></div>"
         "<script>"
         "const UI_PREFIX=" (pr-str (:public-base-path cfg)) ";"
         "const TMUX_PREFIX=" (pr-str tmux-prefix) ";"
         "const COUNT=" terminal-count ";"
         "const STABLE=" lab-stable-session ";"
         "const CANARY=" lab-canary-session ";"
         "let active=Number(" sess ");"
         "function tmuxName(n){return TMUX_PREFIX+String(n)}"
         "function api(path){const sep=path.includes('?')?'&':'?';return UI_PREFIX+path+sep+'session='+encodeURIComponent(String(active))}"
         "function fmtKB(n){return String(Math.round((Number(n||0))/1024))+' KB'}"
         "function openPanel(title, bodyHtml){document.getElementById('pt').textContent=title;document.getElementById('pb').innerHTML=bodyHtml;document.getElementById('p-dl').style.display='none';document.getElementById('ov').classList.add('open')}"
         "function closePanel(){document.getElementById('ov').classList.remove('open')}"
         "document.getElementById('p-close').addEventListener('click', closePanel);"
         "document.getElementById('ov').addEventListener('click', (e)=>{if(e.target && e.target.id==='ov') closePanel();});"
         "document.addEventListener('keydown', (e)=>{if(e.key==='Escape') closePanel();});"
         "async function jget(url){const r=await fetch(url,{cache:'no-store'}); if(!r.ok) throw new Error('HTTP '+r.status); return await r.json();}"
         "function extLower(n){const s=String(n||'').toLowerCase(); const i=s.lastIndexOf('.'); return i>=0?s.slice(i):''}"
         "function isImg(e){return ['.png','.jpg','.jpeg','.gif','.webp','.svg'].includes(e)}"
         "function isText(e){return ['.txt','.md','.markdown','.log','.json','.edn','.csv'].includes(e)}"
         "function viewUrl(name){return api('/api/lab/outbox/view?name='+encodeURIComponent(String(name||'')))}"
         "function downloadUrl(name){return api('/api/lab/outbox/download?name='+encodeURIComponent(String(name||'')))}"
         "async function showFiles(){openPanel('Files', '<div class=\"sub\">Outbox is the shared library (agent outputs land here).</div><div style=\"height:10px\"></div><ul class=\"list\" id=\"fl\">Loading…</ul>');"
         "const data=await jget(api('/api/lab/outbox')); const items=(data.outbox||[]); const ul=document.getElementById('fl'); ul.innerHTML=''; if(!items.length){ul.innerHTML='<div class=\"sub\">Empty.</div>'; return;} for(const it of items){"
         "const li=document.createElement('li'); li.className='item'; const left=document.createElement('div');"
         "const nm=document.createElement('div'); nm.className='name'; nm.textContent=it.name; const sb=document.createElement('div'); sb.className='sub'; sb.textContent=fmtKB(it.size_bytes);"
         "left.appendChild(nm); left.appendChild(sb); const right=document.createElement('div'); right.className='row';"
         "const b=document.createElement('button'); b.className='btn'; b.type='button'; b.textContent='View'; b.addEventListener('click', ()=>viewFile(it.name));"
         "right.appendChild(b); li.appendChild(left); li.appendChild(right); li.addEventListener('click', ()=>viewFile(it.name)); ul.appendChild(li);} }"
         "async function viewFile(name){const e=extLower(name); const url=viewUrl(name); const dl=document.getElementById('p-dl'); dl.href=downloadUrl(name); dl.style.display='inline-flex';"
         "let inner=''; if(e==='.pdf'){inner='<div class=\"paper\"><iframe src=\"'+url+'\" title=\"pdf\"></iframe></div>';} "
         "else if(isImg(e)){inner='<div class=\"paper\"><img src=\"'+url+'\" style=\"width:100%;height:auto;display:block\" /></div>';} "
         "else if(isText(e)){inner='<div class=\"paper\"><pre id=\"tpre\">(loading…)</pre></div>';}"
         "else{inner='<div class=\"sub\">Not previewable. Use Download.</div>';}"
         "openPanel('File · '+name, inner); if(isText(e)){try{const r=await fetch(url,{cache:'no-store'}); document.getElementById('tpre').textContent=await r.text();}catch(err){document.getElementById('tpre').textContent='Failed: '+err;}} }"
         "function showAdd(){openPanel('Add file', '<div class=\"sub\">Upload a file to outbox (library) or inbox.</div><div style=\"height:10px\"></div>' +"
         "'<form id=\"uf\"><div class=\"row\"><input type=\"file\" name=\"file\" required style=\"flex:1\" />'"
         "+'<select name=\"dir\" style=\"width:160px\"><option value=\"outbox\" selected>outbox</option><option value=\"inbox\">inbox</option></select>'"
         "+'<button class=\"btn primary\" type=\"submit\">Upload</button></div>'"
         "+'<div class=\"sub\" style=\"margin-top:8px\">Max upload: " (int (/ lab-max-upload-bytes (* 1024 1024))) " MB.</div>'"
         "+'<div class=\"sub\" id=\"ust\" style=\"margin-top:8px\"></div></form>');"
         "const f=document.getElementById('uf'); f.addEventListener('submit', async (e)=>{e.preventDefault(); const st=document.getElementById('ust'); st.textContent='Uploading…';"
         "const fd=new FormData(f); const dir=fd.get('dir')||'outbox'; const url=api('/api/lab/upload?dir='+encodeURIComponent(String(dir)));"
         "try{const r=await fetch(url,{method:'POST',body:fd}); if(!r.ok) throw new Error('HTTP '+r.status); st.textContent='Uploaded.';}catch(err){st.textContent='Failed: '+err;} });}"
         "function showPaste(){openPanel('Paste → file', '<div class=\"sub\">Save clipboard text to a file.</div><div style=\"height:10px\"></div>' +"
         "'<div class=\"row\"><input id=\"pn\" type=\"text\" placeholder=\"filename (optional)\" /></div><div style=\"height:8px\"></div>' +"
         "'<textarea id=\"pc\" placeholder=\"Paste text here…\"></textarea><div style=\"height:8px\"></div>' +"
         "'<div class=\"row\"><button class=\"btn\" id=\"pb-in\" type=\"button\">Save to inbox</button><button class=\"btn primary\" id=\"pb-out\" type=\"button\">Save to outbox</button><span class=\"sub\" id=\"pst\"></span></div>');"
         "async function doPaste(dir){const pst=document.getElementById('pst'); pst.textContent='Saving…';"
         "try{const resp=await fetch(api('/api/lab/paste'),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({dir:dir,name:document.getElementById('pn').value||'',content:document.getElementById('pc').value||''})});"
         "if(!resp.ok) throw new Error('HTTP '+resp.status); const data=await resp.json(); pst.textContent='Saved: '+data.name;}catch(err){pst.textContent='Failed: '+err;}}"
         "document.getElementById('pb-in').addEventListener('click', ()=>doPaste('inbox'));"
         "document.getElementById('pb-out').addEventListener('click', ()=>doPaste('outbox'));}"
         "function showHistory(){openPanel('History', '<div class=\"sub\">Captured from tmux scrollback.</div><div style=\"height:10px\"></div>' +"
         "'<div class=\"row\"><input id=\"hl\" type=\"number\" min=\"200\" max=\"200000\" value=\"" lab-default-history-lines "\" style=\"width:140px\" />'"
         "+'<button class=\"btn\" id=\"hc\" type=\"button\">Capture</button><button class=\"btn\" id=\"hs\" type=\"button\">Save to outbox</button><span class=\"sub\" id=\"hst\"></span></div>'"
         "+'<div style=\"height:10px\"></div><div class=\"paper\"><pre id=\"hpre\">(capturing…)</pre></div>');"
         "async function capture(){const st=document.getElementById('hst'); st.textContent='Capturing…'; const lines=Number(document.getElementById('hl').value||" lab-default-history-lines ");"
         "const data=await jget(api('/api/lab/history?lines='+encodeURIComponent(String(lines)))); document.getElementById('hpre').textContent=(data.text||''); st.textContent='Captured.';}"
         "document.getElementById('hc').addEventListener('click', ()=>capture());"
         "document.getElementById('hs').addEventListener('click', async ()=>{try{await capture(); const text=document.getElementById('hpre').textContent||''; const stamp=String(Date.now());"
         "await fetch(api('/api/lab/paste'),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({dir:'outbox',name:'lab-history-'+tmuxName(active)+'-'+stamp+'.txt',content:text})});}catch(e){} });"
         "capture();}"
         "function showSession(){openPanel('Session', '<div class=\"sub\">Pick which tmux session the Lab controls.</div><div style=\"height:10px\"></div>' +"
         "'<div class=\"row\"><button class=\"btn\" id=\"ss\" type=\"button\">Stable (" lab-stable-session ")</button><button class=\"btn\" id=\"sc\" type=\"button\">Canary (" lab-canary-session ")</button></div>' +"
         "'<div style=\"height:10px\"></div><div class=\"row\"><input id=\"sn\" type=\"number\" min=\"1\" max=\"" terminal-count "\" value=\"'+String(active)+'\" style=\"width:140px\" />'"
         "+'<button class=\"btn primary\" id=\"sa\" type=\"button\">Apply</button></div>' +"
         "'<div class=\"sub\" style=\"margin-top:10px\">Terminal: <code>'+tmuxName(active)+'</code></div>');"
         "function setActive(n){const nn=Number(n); if(!Number.isFinite(nn)||nn<1||nn>COUNT) return; active=nn; document.cookie='dw_lab_session='+encodeURIComponent(String(nn))+'; Path=/; Max-Age=31536000; SameSite=Lax';"
         "document.getElementById('active-session').textContent=String(nn); document.getElementById('active-tmux').textContent=tmuxName(nn);"
         "document.getElementById('term').src='/xterm/?arg='+encodeURIComponent(tmuxName(nn));}"
         "document.getElementById('ss').addEventListener('click', ()=>{setActive(STABLE); closePanel();});"
         "document.getElementById('sc').addEventListener('click', ()=>{setActive(CANARY); closePanel();});"
         "document.getElementById('sa').addEventListener('click', ()=>{setActive(document.getElementById('sn').value); closePanel();});}"
         "document.getElementById('b-files').addEventListener('click', ()=>showFiles().catch(()=>{}));"
         "document.getElementById('b-add').addEventListener('click', ()=>showAdd());"
         "document.getElementById('b-paste').addEventListener('click', ()=>showPaste());"
         "document.getElementById('b-history').addEventListener('click', ()=>showHistory());"
         "document.getElementById('b-session').addEventListener('click', ()=>showSession());"
         "document.getElementById('b-clear').addEventListener('click', async ()=>{try{await fetch(api('/api/lab/terminal/clear'),{method:'POST'});}catch(e){} });"
         "(function(){const c=(document.cookie||'').split(';').map(s=>s.trim()).find(s=>s.startsWith('dw_lab_session=')); if(c){const v=c.split('=',2)[1]||''; const n=Number(v); if(Number.isFinite(n)) active=n;}"
         "document.getElementById('active-session').textContent=String(active); document.getElementById('active-tmux').textContent=tmuxName(active);})();"
         "</script></body></html>")))
