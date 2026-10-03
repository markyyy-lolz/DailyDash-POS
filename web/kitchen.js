const U='https://cpodvrwykhkndtwcsmgp.supabase.co',K='sb_publishable_FeiWv8Dur_Qr3d0LF4RBhw_QSObAAHj',A=U+'/functions/v1/dailydash-admin';
let pin=sessionStorage.getItem('dd_manager_pin')||'';
const $=s=>document.querySelector(s),esc=v=>String(v??'').replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
async function api(action,extra={}){if(!pin){pin=prompt('Enter DailyDash manager PIN')||'';if(pin)sessionStorage.setItem('dd_manager_pin',pin)}
 const r=await fetch(A,{method:'POST',headers:{'Content-Type':'application/json','apikey':K,'Authorization':'Bearer '+K},body:JSON.stringify(Object.assign({action,pin},extra))});
 const d=await r.json();if(!r.ok){if(r.status===401){sessionStorage.removeItem('dd_manager_pin');pin=''}throw new Error(d.error||'Request failed')}return d}
function card(o){
 const items=(o.dailydash_order_items||[]).map(i=>'<div class="item">'+i.quantity+'× '+esc(i.product_name)+(i.upsized?' <span class="up">UPSIZED</span>':'')+'</div>').join('');
 let actions=''; if(o.prep_status==='pending')actions='<button class="primary" onclick="setStatus(\''+o.id+'\',\'preparing\')">Start</button>';
 if(o.prep_status==='preparing')actions='<button class="ready" onclick="setStatus(\''+o.id+'\',\'ready\')">Mark Ready</button>';
 if(o.prep_status==='ready')actions='<button class="served" onclick="setStatus(\''+o.id+'\',\'served\')">Served</button>';
 return '<article class="ticket"><div class="top"><div><div class="queue">#'+(o.queue_no||'—')+'</div><div class="order">'+esc(o.order_no)+'</div></div><div class="order">'+new Date(o.created_at).toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'})+'</div></div>'+items+(o.notes?'<div class="note">'+esc(o.notes)+'</div>':'')+'<div class="actions" style="margin-top:10px">'+actions+'</div></article>'}
function render(list){['pending','preparing','ready'].forEach(s=>{$('#'+s).innerHTML=list.filter(o=>o.prep_status===s).map(card).join('')||'<div class="empty">No '+s+' orders</div>'})}
async function load(){try{const d=await api('kitchen_orders');render(d.orders||[]);$('#kdsStatus').textContent='Live • '+new Date().toLocaleTimeString()}catch(e){$('#kdsStatus').textContent=e.message}}
async function setStatus(id,status){try{await api('kitchen_update',{order_id:id,status});await load()}catch(e){alert(e.message)}}window.setStatus=setStatus;load();setInterval(load,5000);