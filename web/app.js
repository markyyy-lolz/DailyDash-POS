const SUPABASE_URL='https://cpodvrwykhkndtwcsmgp.supabase.co';
const SUPABASE_KEY='sb_publishable_FeiWv8Dur_Qr3d0LF4RBhw_QSObAAHj';
const ADMIN_URL=SUPABASE_URL+'/functions/v1/dailydash-admin';

const state={
  pin:sessionStorage.getItem('dd_manager_pin')||'',
  products:[],
  orders:[],
  inventory:[],
  movements:[],
  report:null,
  category:'All'
};

const $=s=>document.querySelector(s);
const $$=s=>Array.from(document.querySelectorAll(s));
const money=n=>'₱'+Number(n||0).toLocaleString('en-PH');
const fmt=iso=>new Intl.DateTimeFormat('en-PH',{
  dateStyle:'medium',timeStyle:'short',timeZone:'Asia/Manila'
}).format(new Date(iso));
const escapeHtml=v=>String(v==null?'':v).replace(/[&<>"]/g,c=>({
  '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'
}[c]));

function manilaDate(offsetDays=0){
  const d=new Date(Date.now()+offsetDays*86400000);
  const parts=new Intl.DateTimeFormat('en-US',{
    timeZone:'Asia/Manila',year:'numeric',month:'2-digit',day:'2-digit'
  }).formatToParts(d);
  const map={};
  parts.forEach(p=>map[p.type]=p.value);
  return map.year+'-'+map.month+'-'+map.day;
}

function toast(message,isError=false){
  const el=$('#toast');
  el.textContent=message;
  el.className='toast show'+(isError?' error':'');
  clearTimeout(toast.t);
  toast.t=setTimeout(()=>el.className='toast',2600);
}

function setCloud(ok,text){
  $('.cloud').classList.toggle('online',!!ok);
  $('#cloudText').textContent=text;
}

async function admin(action,extra={}){
  const res=await fetch(ADMIN_URL,{
    method:'POST',
    headers:{
      'Content-Type':'application/json',
      'apikey':SUPABASE_KEY,
      'Authorization':'Bearer '+SUPABASE_KEY
    },
    body:JSON.stringify(Object.assign({action,pin:state.pin},extra))
  });
  const data=await res.json().catch(()=>({error:'Invalid server response'}));
  if(!res.ok)throw new Error(data.error||('Request failed ('+res.status+')'));
  return data;
}

async function login(pin){
  state.pin=pin;
  const data=await admin('login');
  sessionStorage.setItem('dd_manager_pin',pin);
  $('#loginOverlay').classList.remove('show');
  $('#loginError').textContent='';
  setCloud(true,(data.store_name||'DailyDash')+' cloud online');
  await refreshCurrent();
}

function logout(){
  state.pin='';
  sessionStorage.removeItem('dd_manager_pin');
  $('#pinInput').value='';
  $('#loginOverlay').classList.add('show');
  setCloud(false,'Locked');
}

function currentView(){
  return $('.view.active-view')?.id||'dashboard';
}

function showView(id){
  $$('.view').forEach(v=>v.classList.remove('active-view'));
  $('#'+id).classList.add('active-view');
  $$('.nav').forEach(n=>n.classList.toggle('active',n.dataset.view===id));

  const meta={
    dashboard:['Dashboard','Live DailyDash sales overview'],
    menu:['Menu','Manage cloud prices and availability'],
    inventory:['Inventory','Track stock levels and inventory movements'],
    orders:['Orders','Review synchronized POS transactions'],
    reports:['Reports','Sales and product performance reports'],
    settings:['Settings','Cloud connection and manager access']
  };

  $('#title').textContent=meta[id][0];
  $('#subtitle').textContent=meta[id][1];
  refreshCurrent();
}

async function refreshCurrent(){
  if(!state.pin)return;
  setCloud(false,'Syncing...');
  try{
    const view=currentView();
    if(view==='dashboard')await loadDashboard();
    if(view==='menu')await loadProducts();
    if(view==='inventory')await loadInventory();
    if(view==='orders')await loadOrders();
    if(view==='reports')await loadReport();
    setCloud(true,'Supabase online');
  }catch(e){
    setCloud(false,'Cloud error');
    toast(e.message,true);
    if(/PIN/i.test(e.message))logout();
  }
}

async function loadDashboard(){
  const d=await admin('dashboard');
  $('#salesToday').textContent=money(d.sales_today);
  $('#ordersToday').textContent=d.orders_today;
  $('#avgOrder').textContent=money(d.average_order);
  $('#menuCount').textContent=d.product_count;
  $('#lowStockCount').textContent=d.low_stock_count||0;
  $('#outStockText').textContent=(d.out_of_stock_count||0)+' out of stock';

  const totals=d.tender_totals||{};
  const max=Math.max(1,...Object.values(totals).map(Number));
  $('#tenderStats').innerHTML=['Cash','GCash','Maya'].map(k=>{
    const v=Number(totals[k]||0);
    const pct=Math.round(v/max*100);
    return '<div class="payment-row"><b>'+k+'</b><div class="bar"><i style="width:'+pct+'%"></i></div><strong>'+money(v)+'</strong></div>';
  }).join('');

  const rows=d.recent_orders||[];
  $('#recentOrders').innerHTML=rows.length?rows.map(o=>
    '<div class="mini-order"><div><b>'+escapeHtml(o.order_no)+'</b><small>'+fmt(o.created_at)+' • '+escapeHtml(o.tender)+'</small></div><div><b>'+money(o.total)+'</b><small class="status '+(o.status==='voided'?'voided':'')+'">'+escapeHtml(o.status)+'</small></div></div>'
  ).join(''):'<div class="empty">No orders yet today.</div>';
}

async function loadProducts(){
  const d=await admin('products');
  state.products=d.products||[];
  renderProducts();
}

function renderProducts(){
  const cats=['All',...new Set(state.products.map(p=>p.category))];
  $('#menuFilters').innerHTML=cats.map(c=>
    '<button class="filter '+(state.category===c?'active':'')+'" data-cat="'+escapeHtml(c)+'">'+escapeHtml(c)+'</button>'
  ).join('');

  $$('#menuFilters .filter').forEach(b=>b.onclick=()=>{
    state.category=b.dataset.cat;
    renderProducts();
  });

  const q=$('#menuSearch').value.trim().toLowerCase();
  const rows=state.products.filter(p=>
    (state.category==='All'||p.category===state.category)&&
    (!q||p.name.toLowerCase().includes(q))
  );

  $('#menuGrid').innerHTML=rows.length?rows.map(p=>
    '<article class="menu-card '+(p.available?'':'off')+'" data-id="'+escapeHtml(p.id)+'">'+
      '<div class="menu-top"><div><div class="cat">'+escapeHtml(p.category)+'</div><h3>'+escapeHtml(p.name)+'</h3></div><span class="badge '+(p.available?'':'off')+'">'+(p.available?'Available':'Hidden')+'</span></div>'+
      '<div class="edit"><label><small>Price</small><input class="price-input" type="number" min="0" value="'+p.price+'"></label><button class="primary save-product">Save</button></div>'+
      '<div class="switch-row"><span>Available in POS</span><label class="switch"><input class="available-toggle" type="checkbox" '+(p.available?'checked':'')+'><span></span></label></div>'+
      (p.allow_upsize?'<div class="switch-row"><span>Upsize price</span><b>'+money(p.upsize_price)+'</b></div>':'')+
    '</article>'
  ).join(''):'<div class="empty">No matching products.</div>';

  $$('.save-product').forEach(btn=>btn.onclick=async()=>{
    const card=btn.closest('.menu-card');
    const id=card.dataset.id;
    const price=Number(card.querySelector('.price-input').value);
    const available=card.querySelector('.available-toggle').checked;

    btn.disabled=true;
    btn.textContent='Saving...';

    try{
      await admin('update_product',{id,price,available});
      toast('Menu item updated');
      await loadProducts();
    }catch(e){
      toast(e.message,true);
      btn.disabled=false;
      btn.textContent='Save';
    }
  });
}

function inventoryStatus(item){
  if(!item.track_stock)return {label:'Not tracked',cls:'untracked'};
  if(Number(item.stock_qty)<=0)return {label:'Out of stock',cls:'out'};
  if(Number(item.stock_qty)<=Number(item.low_stock_level))return {label:'Low stock',cls:'low'};
  return {label:'In stock',cls:''};
}

async function loadInventory(){
  const d=await admin('inventory');
  state.inventory=d.items||[];
  state.movements=d.movements||[];
  renderInventory();
}

function renderInventory(){
  const q=$('#inventorySearch').value.trim().toLowerCase();
  const rows=state.inventory.filter(i=>
    !q||i.name.toLowerCase().includes(q)||i.category.toLowerCase().includes(q)
  );

  const tracked=state.inventory.filter(i=>i.track_stock);
  $('#trackedItems').textContent=tracked.length;
  $('#totalUnits').textContent=tracked.reduce((sum,i)=>sum+Number(i.stock_qty||0),0).toLocaleString();
  $('#inventoryLow').textContent=tracked.filter(i=>Number(i.stock_qty)<=Number(i.low_stock_level)).length;
  $('#inventoryOut').textContent=tracked.filter(i=>Number(i.stock_qty)<=0).length;

  $('#inventoryTable').innerHTML=rows.length?
    '<table><thead><tr><th>Product</th><th>Status</th><th>Stock</th><th>Low alert</th><th>Track</th><th></th></tr></thead><tbody>'+
    rows.map(i=>{
      const s=inventoryStatus(i);
      return '<tr data-inventory="'+escapeHtml(i.id)+'">'+
        '<td class="inventory-name"><b>'+escapeHtml(i.name)+'</b><small>'+escapeHtml(i.category)+'</small></td>'+
        '<td><span class="status '+s.cls+'">'+s.label+'</span></td>'+
        '<td><input class="stock-input" type="number" min="0" max="1000000" value="'+Number(i.stock_qty||0)+'"></td>'+
        '<td><input class="low-input" type="number" min="0" max="1000000" value="'+Number(i.low_stock_level||0)+'"></td>'+
        '<td><label class="switch"><input class="track-toggle" type="checkbox" '+(i.track_stock?'checked':'')+'><span></span></label></td>'+
        '<td><button class="primary inventory-save">Save</button></td>'+
      '</tr>';
    }).join('')+
    '</tbody></table>':
    '<div class="empty">No matching inventory items.</div>';

  $$('.inventory-save').forEach(btn=>btn.onclick=async()=>{
    const row=btn.closest('[data-inventory]');
    const productId=row.dataset.inventory;
    const stockQty=Number(row.querySelector('.stock-input').value);
    const lowStockLevel=Number(row.querySelector('.low-input').value);
    const trackStock=row.querySelector('.track-toggle').checked;

    btn.disabled=true;
    btn.textContent='Saving...';
    try{
      await admin('set_inventory',{
        product_id:productId,
        stock_qty:stockQty,
        low_stock_level:lowStockLevel,
        track_stock:trackStock,
        note:'Manager web inventory update'
      });
      toast('Inventory updated');
      await loadInventory();
    }catch(e){
      toast(e.message,true);
      btn.disabled=false;
      btn.textContent='Save';
    }
  });

  const productNames=new Map(state.inventory.map(i=>[i.id,i.name]));
  $('#movementTable').innerHTML=state.movements.length?
    '<table><thead><tr><th>Date</th><th>Product</th><th>Change</th><th>Balance</th><th>Reason</th></tr></thead><tbody>'+
    state.movements.map(m=>
      '<tr><td>'+fmt(m.created_at)+'</td>'+
      '<td>'+escapeHtml(productNames.get(m.product_id)||m.product_id)+'</td>'+
      '<td class="'+(Number(m.quantity_change)>0?'movement-plus':'movement-minus')+'">'+(Number(m.quantity_change)>0?'+':'')+Number(m.quantity_change)+'</td>'+
      '<td>'+Number(m.balance_after)+'</td>'+
      '<td>'+escapeHtml(String(m.reason||'').replaceAll('_',' '))+'</td></tr>'
    ).join('')+
    '</tbody></table>':
    '<div class="empty">No stock movements yet.</div>';
}

async function loadOrders(){
  const d=await admin('orders',{limit:200});
  state.orders=d.orders||[];
  renderOrders();
}

function renderOrders(){
  $('#ordersTable').innerHTML=state.orders.length?
    '<table><thead><tr><th>Order</th><th>Date</th><th>Payment</th><th>Total</th><th>Status</th><th></th></tr></thead><tbody>'+
    state.orders.map(o=>
      '<tr><td><b>'+escapeHtml(o.order_no)+'</b><br><small>'+escapeHtml(o.device_code||'POS')+'</small></td>'+
      '<td>'+fmt(o.created_at)+'</td>'+
      '<td>'+escapeHtml(o.tender)+'</td>'+
      '<td><b>'+money(o.total)+'</b></td>'+
      '<td><span class="status '+(o.status==='voided'?'voided':'')+'">'+escapeHtml(o.status)+'</span></td>'+
      '<td><div class="actions"><button class="tiny details" data-details="'+o.id+'">Details</button>'+
      (o.status==='completed'?'<button class="tiny danger" data-void="'+o.id+'">Void</button>':'')+
      '</div></td></tr>'
    ).join('')+
    '</tbody></table>':
    '<div class="empty">No synced orders yet.</div>';

  $$('[data-details]').forEach(b=>b.onclick=()=>showOrder(b.dataset.details));
  $$('[data-void]').forEach(b=>b.onclick=()=>voidOrder(b.dataset.void));
}

async function showOrder(id){
  try{
    const d=await admin('order_details',{order_id:id});
    $('#modalTitle').textContent=d.order.order_no;
    $('#modalBody').innerHTML=
      '<p class="muted">'+fmt(d.order.created_at)+' • '+escapeHtml(d.order.tender)+' • '+escapeHtml(d.order.device_code||'POS')+'</p>'+
      '<div class="detail-total">'+money(d.order.total)+'</div>'+
      '<table><thead><tr><th>Item</th><th>Qty</th><th>Unit</th><th>Total</th></tr></thead><tbody>'+
      (d.items||[]).map(i=>
        '<tr><td>'+escapeHtml(i.product_name)+(i.upsized?' <small>Upsized</small>':'')+'</td>'+
        '<td>'+i.quantity+'</td><td>'+money(i.unit_price)+'</td><td>'+money(i.line_total)+'</td></tr>'
      ).join('')+
      '</tbody></table>';
    $('#modal').classList.add('show');
  }catch(e){
    toast(e.message,true);
  }
}

async function voidOrder(id){
  if(!confirm('Void this completed order? Tracked inventory from this sale will be restored automatically.'))return;
  try{
    await admin('void_order',{order_id:id});
    toast('Order voided and tracked stock restored');
    await loadOrders();
  }catch(e){
    toast(e.message,true);
  }
}

function setReportRange(days){
  $('#reportEnd').value=manilaDate(0);
  $('#reportStart').value=manilaDate(-(Math.max(1,days)-1));
}

async function loadReport(){
  if(!$('#reportStart').value||!$('#reportEnd').value)setReportRange(7);
  const d=await admin('reports',{
    start:$('#reportStart').value,
    end:$('#reportEnd').value
  });
  state.report=d.report||null;
  renderReport();
}

function renderReport(){
  const r=state.report||{
    sales:0,orders:0,average_order:0,items_sold:0,voided_orders:0,
    tenders:[],categories:[],daily:[],top_products:[]
  };

  $('#reportSales').textContent=money(r.sales);
  $('#reportOrders').textContent=Number(r.orders||0).toLocaleString();
  $('#reportAvg').textContent=money(r.average_order);
  $('#reportItems').textContent=Number(r.items_sold||0).toLocaleString();
  $('#reportVoided').textContent=Number(r.voided_orders||0).toLocaleString();

  $('#reportTender').innerHTML=(r.tenders||[]).length?
    '<div class="report-list">'+r.tenders.map(t=>
      '<div class="report-line"><div><b>'+escapeHtml(t.tender)+'</b><small>'+Number(t.orders||0)+' order(s)</small></div><strong>'+money(t.total)+'</strong></div>'
    ).join('')+'</div>':
    '<div class="empty">No payment data for this range.</div>';

  $('#reportCategories').innerHTML=(r.categories||[]).length?
    '<div class="report-list">'+r.categories.map(c=>
      '<div class="report-line"><div><b>'+escapeHtml(c.category)+'</b><small>'+Number(c.quantity||0)+' item(s)</small></div><strong>'+money(c.revenue)+'</strong></div>'
    ).join('')+'</div>':
    '<div class="empty">No category sales for this range.</div>';

  $('#dailyReportTable').innerHTML=(r.daily||[]).length?
    '<table><thead><tr><th>Date</th><th>Orders</th><th>Sales</th></tr></thead><tbody>'+
    r.daily.map(d=>'<tr><td>'+escapeHtml(d.day)+'</td><td>'+Number(d.orders||0)+'</td><td><b>'+money(d.sales)+'</b></td></tr>').join('')+
    '</tbody></table>':
    '<div class="empty">No daily sales for this range.</div>';

  $('#topProductsTable').innerHTML=(r.top_products||[]).length?
    '<table><thead><tr><th>Product</th><th>Qty</th><th>Revenue</th></tr></thead><tbody>'+
    r.top_products.map(p=>
      '<tr><td><b>'+escapeHtml(p.product_name)+'</b><br><small>'+escapeHtml(p.category)+'</small></td><td>'+Number(p.quantity||0)+'</td><td><b>'+money(p.revenue)+'</b></td></tr>'
    ).join('')+
    '</tbody></table>':
    '<div class="empty">No product sales for this range.</div>';
}

function csvCell(value){
  let s=String(value==null?'':value);
  if(/^[=+\-@]/.test(s))s="'"+s;
  return '"'+s.replaceAll('"','""')+'"';
}

function exportReport(){
  const r=state.report;
  if(!r){toast('Generate a report first.',true);return}

  const rows=[
    ['DailyDash Sales Report'],
    ['From',$('#reportStart').value,'To',$('#reportEnd').value],
    [],
    ['Summary','Value'],
    ['Total Sales',r.sales],
    ['Orders',r.orders],
    ['Average Order',r.average_order],
    ['Items Sold',r.items_sold],
    ['Voided Orders',r.voided_orders],
    [],
    ['Payment','Orders','Total'],
    ...(r.tenders||[]).map(x=>[x.tender,x.orders,x.total]),
    [],
    ['Category','Items','Revenue'],
    ...(r.categories||[]).map(x=>[x.category,x.quantity,x.revenue]),
    [],
    ['Date','Orders','Sales'],
    ...(r.daily||[]).map(x=>[x.day,x.orders,x.sales]),
    [],
    ['Top Product','Category','Quantity','Revenue'],
    ...(r.top_products||[]).map(x=>[x.product_name,x.category,x.quantity,x.revenue])
  ];

  const csv=rows.map(row=>row.map(csvCell).join(',')).join('\r\n');
  const blob=new Blob([csv],{type:'text/csv;charset=utf-8'});
  const url=URL.createObjectURL(blob);
  const a=document.createElement('a');
  a.href=url;
  a.download='DailyDash-Report-'+$('#reportStart').value+'-to-'+$('#reportEnd').value+'.csv';
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
  toast('Report CSV exported');
}


async function loadStaff(){
  const d=await admin('staff');
  state.staff=d.staff||[];
  renderStaff();
}

function staffInitials(name){
  return String(name||'S').split(/\s+/).filter(Boolean).slice(0,2).map(x=>x[0].toUpperCase()).join('');
}

function renderStaff(){
  const active=state.staff.filter(s=>s.is_active);
  $('#activeStaffCount').textContent=active.length;
  $('#managerStaffCount').textContent=active.filter(s=>['manager','admin'].includes(s.role)).length;
  $('#cashierStaffCount').textContent=active.filter(s=>s.role==='cashier').length;

  $('#staffGrid').innerHTML=state.staff.length?state.staff.map(s=>{
    const avatar=s.avatar_url
      ? '<img class="staff-avatar" src="'+escapeHtml(s.avatar_url)+'" alt="">'
      : '<div class="staff-avatar">'+escapeHtml(staffInitials(s.display_name))+'</div>';
    const login=s.last_login_at?fmt(s.last_login_at):'Never';
    return '<article class="staff-card '+(s.is_active?'':'off')+'" data-staff-id="'+s.id+'">'+
      '<div class="staff-card-head">'+avatar+'<div><h3>'+escapeHtml(s.display_name)+'</h3><small>'+escapeHtml(s.staff_code)+(s.username?' • @'+escapeHtml(s.username):'')+'</small></div></div>'+
      '<span class="staff-role">'+escapeHtml(s.role)+'</span>'+
      '<div class="staff-meta"><div><span>Status</span><b>'+(s.is_active?'Active':'Disabled')+'</b></div><div><span>Last login</span><b>'+escapeHtml(login)+'</b></div></div>'+
      '<div class="staff-card-actions"><button class="secondary edit-staff">Edit</button><button class="'+(s.is_active?'tiny danger':'secondary')+' toggle-staff">'+(s.is_active?'Disable':'Enable')+'</button></div>'+
    '</article>';
  }).join(''):'<div class="empty">No staff accounts yet.</div>';

  $('.edit-staff').forEach(btn=>btn.onclick=()=>{
    const id=btn.closest('[data-staff-id]').dataset.staffId;
    openStaffModal(state.staff.find(s=>s.id===id));
  });
  $('.toggle-staff').forEach(btn=>btn.onclick=async()=>{
    const id=btn.closest('[data-staff-id]').dataset.staffId;
    const member=state.staff.find(s=>s.id===id);
    if(!member)return;
    try{
      await admin('staff_toggle',{id,is_active:!member.is_active});
      toast(member.is_active?'Staff access disabled':'Staff access enabled');
      await loadStaff();
    }catch(e){toast(e.message,true)}
  });
}

function openStaffModal(member=null){
  $('#staffModalTitle').textContent=member?'Edit Staff':'Add Staff';
  $('#staffId').value=member?.id||'';
  $('#staffCode').value=member?.staff_code||'';
  $('#staffName').value=member?.display_name||'';
  $('#staffUsername').value=member?.username||'';
  $('#staffRole').value=member?.role||'cashier';
  $('#staffPin').value='';
  $('#staffPassword').value='';
  $('#staffAvatar').value=member?.avatar_url||'';
  $('#staffActive').checked=member?!!member.is_active:true;
  $('#staffModal').classList.add('show');
}

function closeStaffModal(){
  $('#staffModal').classList.remove('show');
}

$('#todayLabel').textContent=new Intl.DateTimeFormat('en-PH',{
  dateStyle:'full',timeZone:'Asia/Manila'
}).format(new Date());

setReportRange(7);

$$('.nav').forEach(n=>n.onclick=()=>showView(n.dataset.view));
$$('[data-jump]').forEach(n=>n.onclick=()=>showView(n.dataset.jump));

$('#addStaffBtn').onclick=()=>openStaffModal();
$('#staffModalClose').onclick=closeStaffModal;
$('#staffCancel').onclick=closeStaffModal;
$('#staffModal').onclick=e=>{if(e.target.id==='staffModal')closeStaffModal()};
$('#staffPin').oninput=e=>e.target.value=e.target.value.replace(/\D/g,'').slice(0,6);
$('#staffForm').onsubmit=async e=>{
  e.preventDefault();
  const id=$('#staffId').value||null;
  const pin=$('#staffPin').value;
  const password=$('#staffPassword').value;
  if(!id&&!pin&&!password){toast('New staff needs a PIN or password.',true);return}
  if(pin&&pin.length<4){toast('PIN must be 4 to 6 digits.',true);return}
  if(password&&password.length<6){toast('Password must be at least 6 characters.',true);return}

  const btn=$('#staffSaveBtn');
  btn.disabled=true;btn.textContent='Saving...';
  try{
    await admin('staff_save',{
      id,
      staff_code:$('#staffCode').value.trim(),
      display_name:$('#staffName').value.trim(),
      username:$('#staffUsername').value.trim()||null,
      role:$('#staffRole').value,
      staff_pin:pin||null,
      password:password||null,
      avatar_url:$('#staffAvatar').value.trim()||null,
      is_active:$('#staffActive').checked
    });
    closeStaffModal();
    toast(id?'Staff updated':'Staff added');
    await loadStaff();
  }catch(err){toast(err.message,true)}
  finally{btn.disabled=false;btn.textContent='Save Staff'}
};
\n$('#refreshBtn').onclick=refreshCurrent;
$('#reloadOrders').onclick=loadOrders;
$('#logoutBtn').onclick=logout;
$('#menuSearch').oninput=renderProducts;
$('#inventorySearch').oninput=renderInventory;
$('#runReport').onclick=loadReport;
$('#exportReport').onclick=exportReport;

$$('[data-range]').forEach(b=>b.onclick=async()=>{
  const value=b.dataset.range;
  setReportRange(value==='today'?1:Number(value));
  try{await loadReport()}catch(e){toast(e.message,true)}
});

$('#modalClose').onclick=()=>$('#modal').classList.remove('show');
$('#modal').onclick=e=>{
  if(e.target.id==='modal')$('#modal').classList.remove('show');
};

$('#pinInput').oninput=e=>e.target.value=e.target.value.replace(/\D/g,'').slice(0,6);
$('#newPin').oninput=e=>e.target.value=e.target.value.replace(/\D/g,'').slice(0,6);
$('#confirmPin').oninput=e=>e.target.value=e.target.value.replace(/\D/g,'').slice(0,6);

$('#loginForm').onsubmit=async e=>{
  e.preventDefault();
  const pin=$('#pinInput').value.replace(/\D/g,'').slice(0,6);
  if(pin.length!==6){
    $('#loginError').textContent='Enter a 6-digit PIN.';
    return;
  }
  $('#loginError').textContent='Checking...';
  try{
    await login(pin);
  }catch(err){
    $('#loginError').textContent=err.message;
    setCloud(false,'Locked');
  }
};

$('#pinForm').onsubmit=async e=>{
  e.preventDefault();
  const a=$('#newPin').value;
  const b=$('#confirmPin').value;

  if(a.length!==6||a!==b){
    toast('PINs must match and contain exactly 6 digits.',true);
    return;
  }

  try{
    await admin('change_pin',{new_pin:a});
    state.pin=a;
    sessionStorage.setItem('dd_manager_pin',a);
    $('#newPin').value='';
    $('#confirmPin').value='';
    toast('Manager PIN changed');
  }catch(err){
    toast(err.message,true);
  }
};

if(state.pin){
  login(state.pin).catch(()=>logout());
}
