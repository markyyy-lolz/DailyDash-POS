const SUPABASE_URL='https://cpodvrwykhkndtwcsmgp.supabase.co';
const SUPABASE_KEY='sb_publishable_FeiWv8Dur_Qr3d0LF4RBhw_QSObAAHj';
const ADMIN_URL=SUPABASE_URL+'/functions/v1/dailydash-admin';
const state={pin:sessionStorage.getItem('dd_manager_pin')||'',products:[],orders:[],category:'All'};
const $=s=>document.querySelector(s),$$=s=>Array.from(document.querySelectorAll(s));
const money=n=>'₱'+Number(n||0).toLocaleString('en-PH');
const fmt=iso=>new Intl.DateTimeFormat('en-PH',{dateStyle:'medium',timeStyle:'short',timeZone:'Asia/Manila'}).format(new Date(iso));
const escapeHtml=v=>String(v==null?'':v).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));

function toast(message,isError=false){
  const el=$('#toast');el.textContent=message;el.className='toast show'+(isError?' error':'');
  clearTimeout(toast.t);toast.t=setTimeout(()=>el.className='toast',2600);
}
function setCloud(ok,text){
  $('.cloud').classList.toggle('online',!!ok);$('#cloudText').textContent=text;
}
async function admin(action,extra={}){
  const res=await fetch(ADMIN_URL,{method:'POST',headers:{'Content-Type':'application/json','apikey':SUPABASE_KEY,'Authorization':'Bearer '+SUPABASE_KEY},body:JSON.stringify(Object.assign({action:action,pin:state.pin},extra))});
  const data=await res.json().catch(()=>({error:'Invalid server response'}));
  if(!res.ok)throw new Error(data.error||('Request failed ('+res.status+')'));
  return data;
}
async function login(pin){
  state.pin=pin;
  const data=await admin('login');
  sessionStorage.setItem('dd_manager_pin',pin);
  $('#loginOverlay').classList.remove('show');$('#loginError').textContent='';
  setCloud(true,(data.store_name||'DailyDash')+' cloud online');
  await refreshCurrent();
}
function logout(){
  state.pin='';sessionStorage.removeItem('dd_manager_pin');$('#pinInput').value='';
  $('#loginOverlay').classList.add('show');setCloud(false,'Locked');
}
function currentView(){return $('.view.active-view')?.id||'dashboard'}
function showView(id){
  $$('.view').forEach(v=>v.classList.remove('active-view'));$('#'+id).classList.add('active-view');
  $$('.nav').forEach(n=>n.classList.toggle('active',n.dataset.view===id));
  const meta={dashboard:['Dashboard','Live DailyDash sales overview'],menu:['Menu','Manage cloud prices and availability'],orders:['Orders','Review synchronized POS transactions'],settings:['Settings','Cloud connection and manager access']};
  $('#title').textContent=meta[id][0];$('#subtitle').textContent=meta[id][1];refreshCurrent();
}
async function refreshCurrent(){
  if(!state.pin)return;setCloud(false,'Syncing...');
  try{
    const view=currentView();
    if(view==='dashboard')await loadDashboard();
    if(view==='menu')await loadProducts();
    if(view==='orders')await loadOrders();
    setCloud(true,'Supabase online');
  }catch(e){setCloud(false,'Cloud error');toast(e.message,true);if(/PIN/i.test(e.message))logout()}
}
async function loadDashboard(){
  const d=await admin('dashboard');
  $('#salesToday').textContent=money(d.sales_today);$('#ordersToday').textContent=d.orders_today;
  $('#avgOrder').textContent=money(d.average_order);$('#menuCount').textContent=d.product_count;
  const totals=d.tender_totals||{};const max=Math.max(1,...Object.values(totals).map(Number));
  $('#tenderStats').innerHTML=['Cash','GCash','Maya'].map(k=>{
    const v=Number(totals[k]||0),pct=Math.round(v/max*100);
    return '<div class="payment-row"><b>'+k+'</b><div class="bar"><i style="width:'+pct+'%"></i></div><strong>'+money(v)+'</strong></div>';
  }).join('');
  const rows=d.recent_orders||[];
  $('#recentOrders').innerHTML=rows.length?rows.map(o=>
    '<div class="mini-order"><div><b>'+escapeHtml(o.order_no)+'</b><small>'+fmt(o.created_at)+' • '+escapeHtml(o.tender)+'</small></div><div><b>'+money(o.total)+'</b><small class="status '+(o.status==='voided'?'voided':'')+'">'+escapeHtml(o.status)+'</small></div></div>'
  ).join(''):'<div class="empty">No orders yet today.</div>';
}
async function loadProducts(){const d=await admin('products');state.products=d.products||[];renderProducts()}
function renderProducts(){
  const cats=['All',...new Set(state.products.map(p=>p.category))];
  $('#menuFilters').innerHTML=cats.map(c=>'<button class="filter '+(state.category===c?'active':'')+'" data-cat="'+escapeHtml(c)+'">'+escapeHtml(c)+'</button>').join('');
  $$('#menuFilters .filter').forEach(b=>b.onclick=()=>{state.category=b.dataset.cat;renderProducts()});
  const q=$('#menuSearch').value.trim().toLowerCase();
  const rows=state.products.filter(p=>(state.category==='All'||p.category===state.category)&&(!q||p.name.toLowerCase().includes(q)));
  $('#menuGrid').innerHTML=rows.length?rows.map(p=>
    '<article class="menu-card '+(p.available?'':'off')+'" data-id="'+p.id+'">'+
    '<div class="menu-top"><div><div class="cat">'+escapeHtml(p.category)+'</div><h3>'+escapeHtml(p.name)+'</h3></div><span class="badge '+(p.available?'':'off')+'">'+(p.available?'Available':'Hidden')+'</span></div>'+
    '<div class="edit"><label><small>Price</small><input class="price-input" type="number" min="0" value="'+p.price+'"></label><button class="primary save-product">Save</button></div>'+
    '<div class="switch-row"><span>Available in POS</span><label class="switch"><input class="available-toggle" type="checkbox" '+(p.available?'checked':'')+'><span></span></label></div>'+
    (p.allow_upsize?'<div class="switch-row"><span>Upsize price</span><b>'+money(p.upsize_price)+'</b></div>':'')+
    '</article>'
  ).join(''):'<div class="empty">No matching products.</div>';
  $$('.save-product').forEach(btn=>btn.onclick=async()=>{
    const card=btn.closest('.menu-card'),id=card.dataset.id,price=Number(card.querySelector('.price-input').value),available=card.querySelector('.available-toggle').checked;
    btn.disabled=true;btn.textContent='Saving...';
    try{await admin('update_product',{id:id,price:price,available:available});toast('Menu item updated');await loadProducts()}
    catch(e){toast(e.message,true);btn.disabled=false;btn.textContent='Save'}
  });
}
async function loadOrders(){const d=await admin('orders',{limit:200});state.orders=d.orders||[];renderOrders()}
function renderOrders(){
  $('#ordersTable').innerHTML=state.orders.length?
  '<table><thead><tr><th>Order</th><th>Date</th><th>Payment</th><th>Total</th><th>Status</th><th></th></tr></thead><tbody>'+
  state.orders.map(o=>'<tr><td><b>'+escapeHtml(o.order_no)+'</b><br><small>'+escapeHtml(o.device_code||'POS')+'</small></td><td>'+fmt(o.created_at)+'</td><td>'+escapeHtml(o.tender)+'</td><td><b>'+money(o.total)+'</b></td><td><span class="status '+(o.status==='voided'?'voided':'')+'">'+escapeHtml(o.status)+'</span></td><td><div class="actions"><button class="tiny details" data-details="'+o.id+'">Details</button>'+(o.status==='completed'?'<button class="tiny danger" data-void="'+o.id+'">Void</button>':'')+'</div></td></tr>').join('')+
  '</tbody></table>':'<div class="empty">No synced orders yet.</div>';
  $$('[data-details]').forEach(b=>b.onclick=()=>showOrder(b.dataset.details));
  $$('[data-void]').forEach(b=>b.onclick=()=>voidOrder(b.dataset.void));
}
async function showOrder(id){
  try{
    const d=await admin('order_details',{order_id:id});$('#modalTitle').textContent=d.order.order_no;
    $('#modalBody').innerHTML='<p class="muted">'+fmt(d.order.created_at)+' • '+escapeHtml(d.order.tender)+' • '+escapeHtml(d.order.device_code||'POS')+'</p><div class="detail-total">'+money(d.order.total)+'</div>'+
    '<table><thead><tr><th>Item</th><th>Qty</th><th>Unit</th><th>Total</th></tr></thead><tbody>'+
    (d.items||[]).map(i=>'<tr><td>'+escapeHtml(i.product_name)+(i.upsized?' <small>Upsized</small>':'')+'</td><td>'+i.quantity+'</td><td>'+money(i.unit_price)+'</td><td>'+money(i.line_total)+'</td></tr>').join('')+
    '</tbody></table>';$('#modal').classList.add('show');
  }catch(e){toast(e.message,true)}
}
async function voidOrder(id){
  if(!confirm('Void this completed order? It will stay in history but be excluded from sales totals.'))return;
  try{await admin('void_order',{order_id:id});toast('Order voided');await loadOrders()}catch(e){toast(e.message,true)}
}
$('#todayLabel').textContent=new Intl.DateTimeFormat('en-PH',{dateStyle:'full',timeZone:'Asia/Manila'}).format(new Date());
$$('.nav').forEach(n=>n.onclick=()=>showView(n.dataset.view));
$$('[data-jump]').forEach(n=>n.onclick=()=>showView(n.dataset.jump));
$('#refreshBtn').onclick=refreshCurrent;$('#reloadOrders').onclick=loadOrders;$('#logoutBtn').onclick=logout;$('#menuSearch').oninput=renderProducts;
$('#modalClose').onclick=()=>$('#modal').classList.remove('show');$('#modal').onclick=e=>{if(e.target.id==='modal')$('#modal').classList.remove('show')};
$('#pinInput').oninput=e=>e.target.value=e.target.value.replace(/\D/g,'').slice(0,6);
$('#newPin').oninput=e=>e.target.value=e.target.value.replace(/\D/g,'').slice(0,6);
$('#confirmPin').oninput=e=>e.target.value=e.target.value.replace(/\D/g,'').slice(0,6);
$('#loginForm').onsubmit=async e=>{
  e.preventDefault();const pin=$('#pinInput').value.replace(/\D/g,'').slice(0,6);
  if(pin.length!==6){$('#loginError').textContent='Enter a 6-digit PIN.';return}
  $('#loginError').textContent='Checking...';try{await login(pin)}catch(err){$('#loginError').textContent=err.message;setCloud(false,'Locked')}
};
$('#pinForm').onsubmit=async e=>{
  e.preventDefault();const a=$('#newPin').value,b=$('#confirmPin').value;
  if(a.length!==6||a!==b){toast('PINs must match and contain exactly 6 digits.',true);return}
  try{await admin('change_pin',{new_pin:a});state.pin=a;sessionStorage.setItem('dd_manager_pin',a);$('#newPin').value='';$('#confirmPin').value='';toast('Manager PIN changed')}catch(err){toast(err.message,true)}
};
if(state.pin){login(state.pin).catch(()=>logout())}
