(() => {
  const report = JSON.parse(document.getElementById('feedback-log-data').textContent);
  const session = crypto.randomUUID();
  const prefix = 'lorikeet.feedback.' + report.report_id + '.';
  const ledger = new Map();
  const online = location.protocol === 'http:' || location.protocol === 'https:';
  let token = null, sending = false;
  function persist(record){
    ledger.set(record.event.event_id, record);
    try{localStorage.setItem(prefix + record.event.event_id, JSON.stringify(record));}
    catch{/* Keep the event in memory when browser storage is unavailable. */}
  }
  try{
    for(let i=0;i<localStorage.length;i++){
      const key=localStorage.key(i);
      if(key.startsWith(prefix)){
        try{const record=JSON.parse(localStorage.getItem(key));if(record.event?.event_id)ledger.set(record.event.event_id,record);}catch{}
      }
    }
  }catch{/* Browser storage may be unavailable. */}
  async function request(url, options={}){
    const controller=new AbortController();const timeout=setTimeout(()=>controller.abort(),5000);
    try{return await fetch(url,{...options,signal:controller.signal,cache:'no-store'});}
    finally{clearTimeout(timeout);}
  }
  async function flush(){
    if(!online || sending)return;
    sending=true;
    try{
      if(!report.event_endpoint&&!token){const response=await request('/api/log-token');if(!response.ok)throw Error('Log connection failed');token=(await response.json()).token;}
      for(const record of ledger.values()){
        if(record.synced)continue;
        const headers={'Content-Type':'application/json'};
        if(token)headers['X-Log-Token']=token;
        const response=await request(report.event_endpoint||'/api/log-events',{method:'POST',headers,body:JSON.stringify(record.event),keepalive:true});
        if(!response.ok){if(!report.event_endpoint&&response.status===403)token=null;throw Error('Log save failed');}
        record.synced=true;persist(record);
      }
    }catch{/* Leave unsent events queued for the next connection attempt. */}
    finally{sending=false;}
  }
  function record(type, domId, rating=null){
    const issue=report.issues[domId];if(!issue)return;
    const timestamp=new Date().toISOString();
    const event={event_id:crypto.randomUUID(),session_id:session,report_id:report.report_id,submission:report.submission,run:report.run,event_type:type,timestamp,issue,rating};
    persist({event,synced:false});void flush();
  }
  document.addEventListener('timelinechange', event=>record('feedback_view',event.detail.id));
  document.addEventListener('click',event=>{
    const button=event.target.closest('[data-rating]');if(!button)return;
    const id=button.closest('[data-feedback-id]')?.dataset.feedbackId;if(!id)return;
    button.parentElement.querySelectorAll('[data-rating]').forEach(peer=>peer.classList.toggle('selected',peer===button));
    record('feedback_rating',id,button.dataset.rating);
  });
  Object.keys(report.issues).forEach(id=>record('issue_loaded',id));
  window.addEventListener('online',flush);
  window.addEventListener('pagehide',flush);
  setInterval(flush,5000);
})();
