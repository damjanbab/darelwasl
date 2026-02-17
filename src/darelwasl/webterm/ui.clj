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
         ".bar{position:fixed;top:0;left:0;right:0;height:46px;display:flex;align-items:center;gap:8px;padding:8px 10px;background:var(--bar);border-bottom:1px solid var(--border);backdrop-filter:blur(8px);-webkit-backdrop-filter:blur(8px);z-index:10;overflow-x:auto;-webkit-overflow-scrolling:touch}"
         ".bar::-webkit-scrollbar{display:none}.bar{scrollbar-width:none}"
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
         "<button class=\"btn\" id=\"b-work\" type=\"button\">Work</button>"
         "<button class=\"btn\" id=\"b-lib\" type=\"button\">Library</button>"
         "<button class=\"btn\" id=\"b-add\" type=\"button\">Add</button>"
         "<button class=\"btn\" id=\"b-paste\" type=\"button\">Paste</button>"
         "<button class=\"btn\" id=\"b-history\" type=\"button\">History</button>"
         "<button class=\"btn\" id=\"b-session\" type=\"button\">Session</button>"
         "<button class=\"btn\" id=\"b-clear\" type=\"button\">Clear</button>"
         "<span class=\"sp\"></span>"
         "<a class=\"btn\" href=\"" (ui-url cfg "/") "\">Terminals</a>"
         "<a class=\"btn\" href=\"" (:href other) "\" rel=\"noreferrer\">" (:label other) "</a>"
         "<a class=\"btn primary\" id=\"open-term\" href=\"" iframe-src "\" target=\"_blank\" rel=\"noreferrer\">Open</a>"
         "<span class=\"chip\">ui " ui-role "</span>"
         "<span class=\"chip\">build " build-stamp "</span>"
         "<span class=\"chip\">active <span id=\"active-session\">" sess "</span></span>"
         "<span class=\"chip\"><code id=\"active-tmux\">" sname "</code></span>"
         "<span class=\"chip\">work <code id=\"active-work\">(none)</code></span>"
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
         "let workId='';"
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
         "function getCookie(name){const c=(document.cookie||'').split(';').map(s=>s.trim()); const p=c.find(x=>x.startsWith(name+'=')); return p?decodeURIComponent((p.split('=',2)[1]||'')):''}"
         "function setCookie(name,val){document.cookie=name+'='+encodeURIComponent(String(val||''))+'; Path=/; Max-Age=31536000; SameSite=Lax'}"
         "function setWork(id){workId=String(id||'').trim(); setCookie('dw_work_id', workId); document.getElementById('active-work').textContent=workId?workId:'(none)'}"
         "function viewUrl(ref){return api('/api/library/view?ref='+encodeURIComponent(String(ref||'')))}"
         "function downloadUrl(ref){return api('/api/library/download?ref='+encodeURIComponent(String(ref||'')))}"
         "async function copyText(t){try{await navigator.clipboard.writeText(String(t||'')); return true;}catch(e){return false;}}"

         "async function viewRef(ref,label){const e=extLower(ref); const url=viewUrl(ref); const dl=document.getElementById('p-dl'); dl.href=downloadUrl(ref); dl.style.display='inline-flex';"
         "let inner=''; if(e==='.pdf'){inner='<div class=\"paper\"><iframe src=\"'+url+'\" title=\"pdf\"></iframe></div>';} "
         "else if(isImg(e)){inner='<div class=\"paper\"><img src=\"'+url+'\" style=\"width:100%;height:auto;display:block\" /></div>';} "
         "else if(isText(e)){inner='<div class=\"paper\"><pre id=\"tpre\">(loading…)</pre></div>';}"
         "else{inner='<div class=\"sub\">Not previewable. Use Download.</div>';}"
         "openPanel((label||'File')+' · '+ref, inner); if(isText(e)){try{const r=await fetch(url,{cache:'no-store'}); document.getElementById('tpre').textContent=await r.text();}catch(err){document.getElementById('tpre').textContent='Failed: '+err;}} }"

         "async function showWork(){openPanel('Work', '<div class=\"sub\">Work items are PR candidates. Select one to scope uploads/artifacts.</div><div style=\"height:10px\"></div><ul class=\"list\" id=\"wl\">Loading…</ul>');"
         "const data=await jget(api('/api/work/list')); const items=(data.items||[]); const ul=document.getElementById('wl'); ul.innerHTML='';"
         "if(!items.length){ul.innerHTML='<div class=\"sub\">No work items found in docs/work.</div>'; return;}"
         "for(const it of items){const li=document.createElement('li'); li.className='item'; const left=document.createElement('div');"
         "const nm=document.createElement('div'); nm.className='name'; nm.textContent=it.id+(it.summary?(' — '+it.summary):'');"
         "const sb=document.createElement('div'); sb.className='sub'; sb.textContent=(it.status?('status: '+it.status+' · '):'')+(it.type?('type: '+it.type):'');"
         "left.appendChild(nm); left.appendChild(sb); const right=document.createElement('div'); right.className='row';"
         "const bSel=document.createElement('button'); bSel.className='btn primary'; bSel.type='button'; bSel.textContent='Select'; bSel.addEventListener('click', ()=>{setWork(it.id); closePanel();});"
         "const bOpen=document.createElement('button'); bOpen.className='btn'; bOpen.type='button'; bOpen.textContent='Open'; bOpen.addEventListener('click', ()=>openWork(it.id));"
         "const bCopy=document.createElement('button'); bCopy.className='btn'; bCopy.type='button'; bCopy.textContent='Copy PR ready'; bCopy.addEventListener('click', async ()=>{await copyText('PR ready '+it.id);});"
         "right.appendChild(bOpen); right.appendChild(bSel); right.appendChild(bCopy); li.appendChild(left); li.appendChild(right); ul.appendChild(li);} }"

         "async function openWork(id){const wid=String(id||''); const r=await fetch(api('/api/work/file?id='+encodeURIComponent(wid)),{cache:'no-store'});"
         "if(!r.ok){openPanel('Work · '+wid, '<div class=\"sub\">Failed to load.</div>'); return;} const txt=await r.text();"
         "openPanel('Work · '+wid, '<div class=\"row\"><button class=\"btn\" id=\"wsel\" type=\"button\">Select</button><button class=\"btn\" id=\"wref\" type=\"button\">Copy workfile ref</button></div><div style=\"height:10px\"></div><div class=\"paper\"><pre id=\"wpre\"></pre></div>');"
         "document.getElementById('wpre').textContent=txt; document.getElementById('wsel').addEventListener('click', ()=>{setWork(wid); closePanel();});"
         "document.getElementById('wref').addEventListener('click', async ()=>{await copyText('workfile:'+wid);});}"

         "async function showLibrary(){openPanel('Library', '<div class=\"sub\">Artifacts attached to work items (download/viewable).</div><div style=\"height:10px\"></div><ul class=\"list\" id=\"ll\">Loading…</ul>');"
         "const url=workId?api('/api/library/list?work='+encodeURIComponent(workId)):api('/api/library/recent?limit=120');"
         "const data=await jget(url); const items=(data.items||[]); const ul=document.getElementById('ll'); ul.innerHTML='';"
         "if(!items.length){ul.innerHTML='<div class=\"sub\">Empty.</div>'; return;}"
         "for(const it of items){const li=document.createElement('li'); li.className='item'; const left=document.createElement('div');"
         "const nm=document.createElement('div'); nm.className='name'; nm.textContent=(it.work_id?('['+it.work_id+'] '):'')+it.name;"
         "const sb=document.createElement('div'); sb.className='sub'; sb.textContent=fmtKB(it.size_bytes);"
         "left.appendChild(nm); left.appendChild(sb); const right=document.createElement('div'); right.className='row';"
         "const bV=document.createElement('button'); bV.className='btn'; bV.type='button'; bV.textContent='View'; bV.addEventListener('click', ()=>viewRef(it.ref,'File'));"
         "const bC=document.createElement('button'); bC.className='btn'; bC.type='button'; bC.textContent='Copy ref'; bC.addEventListener('click', async ()=>{await copyText(it.ref);});"
         "right.appendChild(bV); right.appendChild(bC); li.appendChild(left); li.appendChild(right); li.addEventListener('click', ()=>viewRef(it.ref,'File')); ul.appendChild(li);} }"

         "function showAdd(){openPanel('Add file', '<div class=\"sub\">Upload into the Library for a work item.</div><div style=\"height:10px\"></div>' +"
         "'<form id=\"uf\"><div class=\"row\"><input type=\"file\" name=\"file\" required style=\"flex:1\" />'"
         "+'<input id=\"uw\" type=\"text\" placeholder=\"work id\" style=\"width:260px\" />'"
         "+'<button class=\"btn primary\" type=\"submit\">Upload</button></div>'"
         "+'<div class=\"sub\" style=\"margin-top:8px\">Max upload: " (int (/ lab-max-upload-bytes (* 1024 1024))) " MB.</div>'"
         "+'<div class=\"sub\" id=\"ust\" style=\"margin-top:8px\"></div></form>');"
         "document.getElementById('uw').value=workId||'';"
         "const f=document.getElementById('uf'); f.addEventListener('submit', async (e)=>{e.preventDefault(); const st=document.getElementById('ust'); st.textContent='Uploading…';"
         "const wid=String(document.getElementById('uw').value||'').trim(); if(!wid){st.textContent='Pick a work first.'; return;}"
         "const fd=new FormData(f); const url=api('/api/library/upload?work='+encodeURIComponent(wid));"
         "try{const r=await fetch(url,{method:'POST',body:fd}); if(!r.ok) throw new Error('HTTP '+r.status); const data=await r.json(); st.textContent='Saved: '+data.ref;}catch(err){st.textContent='Failed: '+err;} });}"

         "function showPaste(){openPanel('Paste → file', '<div class=\"sub\">Save text as an artifact under a work.</div><div style=\"height:10px\"></div>' +"
         "'<div class=\"row\"><input id=\"pw\" type=\"text\" placeholder=\"work id\" style=\"width:260px\" /><input id=\"pn\" type=\"text\" placeholder=\"filename (optional)\" style=\"flex:1\" /></div><div style=\"height:8px\"></div>' +"
         "'<textarea id=\"pc\" placeholder=\"Paste text here…\"></textarea><div style=\"height:8px\"></div>' +"
         "'<div class=\"row\"><button class=\"btn primary\" id=\"pb\" type=\"button\">Save</button><button class=\"btn\" id=\"pb2\" type=\"button\">Copy ref</button><span class=\"sub\" id=\"pst\"></span></div>');"
         "document.getElementById('pw').value=workId||''; let lastRef='';"
         "document.getElementById('pb').addEventListener('click', async ()=>{const pst=document.getElementById('pst'); pst.textContent='Saving…';"
         "const wid=String(document.getElementById('pw').value||'').trim(); if(!wid){pst.textContent='Pick a work first.'; return;}"
         "try{const resp=await fetch(api('/api/library/paste'),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({work:wid,name:document.getElementById('pn').value||'',content:document.getElementById('pc').value||''})});"
         "if(!resp.ok) throw new Error('HTTP '+resp.status); const data=await resp.json(); lastRef=data.ref; pst.textContent='Saved: '+data.ref; setWork(wid);}catch(err){pst.textContent='Failed: '+err;}});"
         "document.getElementById('pb2').addEventListener('click', async ()=>{if(lastRef) await copyText(lastRef);});}"

         "function showHistory(){openPanel('History', '<div class=\"sub\">Captured from tmux scrollback.</div><div style=\"height:10px\"></div>' +"
         "'<div class=\"row\"><input id=\"hl\" type=\"number\" min=\"200\" max=\"200000\" value=\"" lab-default-history-lines "\" style=\"width:140px\" />'"
         "+'<button class=\"btn\" id=\"hc\" type=\"button\">Capture</button><button class=\"btn\" id=\"hs\" type=\"button\">Save to Library</button><span class=\"sub\" id=\"hst\"></span></div>'"
         "+'<div style=\"height:10px\"></div><div class=\"paper\"><pre id=\"hpre\">(capturing…)</pre></div>');"
         "async function capture(){const st=document.getElementById('hst'); st.textContent='Capturing…'; const lines=Number(document.getElementById('hl').value||" lab-default-history-lines ");"
         "const data=await jget(api('/api/lab/history?lines='+encodeURIComponent(String(lines)))); document.getElementById('hpre').textContent=(data.text||''); st.textContent='Captured.'; return data;}"
         "document.getElementById('hc').addEventListener('click', ()=>capture());"
         "document.getElementById('hs').addEventListener('click', async ()=>{const st=document.getElementById('hst'); if(!workId){st.textContent='Pick a work first.'; return;} try{await capture(); const text=document.getElementById('hpre').textContent||''; const stamp=String(Date.now());"
         "await fetch(api('/api/library/paste'),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({work:workId,name:'lab-history-'+tmuxName(active)+'-'+stamp+'.txt',content:text})}); st.textContent='Saved.';}catch(e){st.textContent='Failed.';} });"
         "capture();}"

         "function showSession(){openPanel('Session', '<div class=\"sub\">Pick which tmux session the Lab controls.</div><div style=\"height:10px\"></div>' +"
         "'<div class=\"row\"><button class=\"btn\" id=\"ss\" type=\"button\">Stable (" lab-stable-session ")</button><button class=\"btn\" id=\"sc\" type=\"button\">Canary (" lab-canary-session ")</button></div>' +"
         "'<div style=\"height:10px\"></div><div class=\"row\"><input id=\"sn\" type=\"number\" min=\"1\" max=\"" terminal-count "\" value=\"'+String(active)+'\" style=\"width:140px\" />'"
         "+'<button class=\"btn primary\" id=\"sa\" type=\"button\">Apply</button></div>' +"
         "'<div class=\"sub\" style=\"margin-top:10px\">Terminal: <code>'+tmuxName(active)+'</code></div>');"
         "function setActive(n){const nn=Number(n); if(!Number.isFinite(nn)||nn<1||nn>COUNT) return; active=nn; setCookie('dw_lab_session', String(nn));"
         "document.getElementById('active-session').textContent=String(nn); document.getElementById('active-tmux').textContent=tmuxName(nn);"
         "document.getElementById('term').src='/xterm/?arg='+encodeURIComponent(tmuxName(nn));}"
         "document.getElementById('ss').addEventListener('click', ()=>{setActive(STABLE); closePanel();});"
         "document.getElementById('sc').addEventListener('click', ()=>{setActive(CANARY); closePanel();});"
         "document.getElementById('sa').addEventListener('click', ()=>{setActive(document.getElementById('sn').value); closePanel();});}"

         "document.getElementById('b-work').addEventListener('click', ()=>showWork().catch(()=>{}));"
         "document.getElementById('b-lib').addEventListener('click', ()=>showLibrary().catch(()=>{}));"
         "document.getElementById('b-add').addEventListener('click', ()=>showAdd());"
         "document.getElementById('b-paste').addEventListener('click', ()=>showPaste());"
         "document.getElementById('b-history').addEventListener('click', ()=>showHistory());"
         "document.getElementById('b-session').addEventListener('click', ()=>showSession());"
         "document.getElementById('b-clear').addEventListener('click', async ()=>{try{await fetch(api('/api/lab/terminal/clear'),{method:'POST'});}catch(e){} });"
         "(function(){const c=getCookie('dw_lab_session'); if(c){const n=Number(c); if(Number.isFinite(n)) active=n;} setWork(getCookie('dw_work_id'));})();"
         "</script></body></html>")))
