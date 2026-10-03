const SUPABASE_URL='https://cpodvrwykhkndtwcsmgp.supabase.co';
const SUPABASE_KEY='sb_publishable_FeiWv8Dur_Qr3d0LF4RBhw_QSObAAHj';
const ADMIN_URL=SUPABASE_URL+'/functions/v1/dailydash-admin';
let pin=sessionStorage.getItem('dd_manager_pin')||'';
let ingredients=[],suppliers=[],staff=[],refundOrder=null;

const $=s=>document.querySelector(s);
const $$=s=>Array.from(document.querySelectorAll(s));
const money=n=>'₱'+Number(n||0).toLocaleString('en-PH');
const esc=v=>String(v??'').replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const dt=v=>v?new Intl.DateTimeFormat('en-PH',{dateStyle:'medium',timeStyle:'short',timeZone:'Asia/Manila'}).format(new Date(v)):'—';

function toast(msg,error=false){
  const el=$('#toast'); el.textContent=msg; el.className='toast show'+(error?' error':'');
  clearTimeout(toast.t); toast.t=setTimeout(()=>el.className='toast',2800);
}

async function admin(action,extra={}){
  if(!pin){
    pin=prompt('Enter DailyDash manager PIN')||'';
    if(pin)sessionStorage.setItem('dd_manager_pin',pin);
  }
  const res=await fetch(ADMIN_URL,{
    method:'POST',
    headers:{'Content-Type':'application/json','apikey':SUPABASE_KEY,'Authorization':'Bearer '+SUPABASE_KEY},
    body:JSON.stringify(Object.assign({action,pin},extra))
  });
  const data=await res.json().catch(()=>({error:'Invalid server response'}));
  if(!res.ok){
    if(res.status===401){
      sessionStorage.removeItem('dd_manager_pin'); pin='';
    }
    throw new Error(data.error||'Request failed');
  }
  return data;
}

function table(headers,rows){
  if(!rows.length)return '<div class="empty">No records yet.</div>';
  return '<table><thead><tr>'+headers.map(h=>'<th>'+esc(h)+'</th>').join('')+'</tr></thead><tbody>'+
    rows.map(r=>'<tr>'+r.map(v=>'<td>'+v+'</td>').join('')+'</tr>').join('')+'</tbody></table>';
}

async function loadSummary(){
  const d=await admin('commercial_summary');
  $('#sumShifts').textContent=d.open_shifts||0;
  $('#sumHeld').textContent=d.held_orders||0;
  $('#sumExpenses').textContent=money(d.expenses_today);
  $('#sumRefunds').textContent=money(d.refunds_today);
  const rows=(d.audit||[]).map(a=>[
    dt(a.created_at),esc(a.dailydash_staff?.display_name||'System'),
    '<b>'+esc(a.action)+'</b>',esc(a.entity_type||''),esc(a.entity_id||'')
  ]);
  $('#overviewAudit').innerHTML=table(['Date','Staff','Action','Type','ID'],rows);
}

async function loadDiscounts(){
  const d=await admin('discounts');
  const rows=(d.discounts||[]).map(x=>[
    '<b>'+esc(x.name)+'</b><br><small>'+esc(x.code||'No code')+'</small>',
    esc(x.discount_type),x.discount_type==='percentage'?Number(x.value)+'%':money(x.value),
    x.requires_manager?'Yes':'No',
    '<span class="status '+(x.active?'':'voided')+'">'+(x.active?'Active':'Disabled')+'</span>'+
      ' <button class="tiny details" data-discount-toggle="'+x.id+'" data-active="'+(!x.active)+'">'+(x.active?'Disable':'Enable')+'</button>'
  ]);
  $('#discountTable').innerHTML=table(['Discount','Type','Value','Manager','Status'],rows);
  $$('[data-discount-toggle]').forEach(b=>b.onclick=async()=>{
    try{await admin('discount_toggle',{id:b.dataset.discountToggle,active:b.dataset.active==='true'});await loadDiscounts();toast('Discount updated')}
    catch(e){toast(e.message,true)}
  });
}

async function loadIngredients(){
  const d=await admin('ingredients');
  ingredients=d.ingredients||[];
  const rows=ingredients.map(x=>[
    '<b>'+esc(x.name)+'</b><br><small>'+esc(x.unit)+'</small>',
    Number(x.stock_qty).toLocaleString(),
    Number(x.low_stock_level).toLocaleString(),
    Number(x.cost_per_unit).toLocaleString(undefined,{maximumFractionDigits:4}),
    Number(x.stock_qty)<=Number(x.low_stock_level)?'<span class="status low">Low</span>':'<span class="status">OK</span>'
  ]);
  $('#ingredientTable').innerHTML=table(['Ingredient','Stock','Low alert','Cost/unit','Status'],rows);
  const opts=ingredients.map(x=>'<option value="'+x.id+'">'+esc(x.name)+' ('+esc(x.unit)+')</option>').join('');
  $('#adjustIngredient').innerHTML=opts;
  $('#purchaseIngredient').innerHTML=opts;
}

async function loadSuppliers(){
  const [s,p,st]=await Promise.all([admin('suppliers'),admin('purchases'),admin('staff')]);
  suppliers=s.suppliers||[]; staff=st.staff||[];
  $('#purchaseSupplier').innerHTML='<option value="">No supplier</option>'+suppliers.filter(x=>x.active).map(x=>'<option value="'+x.id+'">'+esc(x.name)+'</option>').join('');
  const staffOptions='<option value="">Use original cashier</option>'+staff.filter(x=>x.is_active).map(x=>'<option value="'+x.id+'">'+esc(x.display_name)+'</option>').join('');
  $('#purchaseStaff').innerHTML=staffOptions.replace('Use original cashier','System');
  $('#refundStaff').innerHTML=staffOptions;
  $('#supplierTable').innerHTML=table(['Supplier','Contact','Phone'],suppliers.map(x=>[
    '<b>'+esc(x.name)+'</b>',esc(x.contact_name||''),esc(x.phone||'')
  ]));
  $('#purchaseTable').innerHTML=table(['Date','Supplier','Reference','Total'],(p.purchases||[]).map(x=>[
    dt(x.received_at),esc(x.dailydash_suppliers?.name||'—'),esc(x.reference_no||'—'),money(x.total_cost)
  ]));
}

async function loadExpenses(){
  const d=await admin('expenses');
  $('#expenseTable').innerHTML=table(['Date','Category','Amount','Staff','Note'],(d.expenses||[]).map(x=>[
    dt(x.created_at),esc(x.category),'<b>'+money(x.amount)+'</b>',esc(x.dailydash_staff?.display_name||'—'),esc(x.note||'')
  ]));
}

async function loadShifts(){
  const d=await admin('shifts');
  $('#shiftTable').innerHTML=table(['Opened','Staff','Device','Opening','Expected','Closing','Variance','Status'],(d.shifts||[]).map(x=>[
    dt(x.opened_at),esc(x.dailydash_staff?.display_name||'—'),esc(x.device_code),money(x.opening_cash),
    x.expected_cash==null?'—':money(x.expected_cash),x.closing_cash==null?'—':money(x.closing_cash),
    x.variance==null?'—':money(x.variance),
    '<span class="status '+(x.status==='open'?'':'untracked')+'">'+esc(x.status)+'</span>'
  ]));
}

async function loadAudit(){
  const d=await admin('audit');
  $('#auditTable').innerHTML=table(['Date','Staff','Action','Entity','Details'],(d.audit||[]).map(x=>[
    dt(x.created_at),esc(x.dailydash_staff?.display_name||'System'),'<b>'+esc(x.action)+'</b>',
    esc((x.entity_type||'')+' '+(x.entity_id||'')),
    '<small>'+esc(JSON.stringify(x.details||{}))+'</small>'
  ]));
}

async function loadSettings(){
  const d=await admin('business_settings'),s=d.settings||{};
  $('#setStore').value=s.store_name||'DailyDash';
  $('#setBranch').value=s.branch_name||'';
  $('#setAddress').value=s.receipt_address||'';
  $('#setPhone').value=s.receipt_phone||'';
  $('#setFooter').value=s.receipt_footer||'';
  $('#setRequireShift').checked=String(s.require_open_shift)==='true';
  $('#setQueue').checked=String(s.queue_enabled)!=='false';
}

async function loadAll(){
  try{
    await Promise.all([loadSummary(),loadDiscounts(),loadIngredients(),loadSuppliers(),loadExpenses(),loadShifts(),loadAudit(),loadSettings()]);
  }catch(e){toast(e.message,true)}
}

$$('.ops-tab').forEach(b=>b.onclick=()=>{
  $$('.ops-tab').forEach(x=>x.classList.remove('active'));b.classList.add('active');
  $$('.ops-panel').forEach(x=>x.classList.remove('active'));$('#'+b.dataset.tab).classList.add('active');
});
$('#opsRefresh').onclick=loadAll;

$('#discountForm').onsubmit=async e=>{
  e.preventDefault();
  try{
    await admin('discount_save',{
      name:$('#discountName').value,code:$('#discountCode').value||null,
      discount_type:$('#discountType').value,value:Number($('#discountValue').value||0),
      min_spend:Number($('#discountMin').value||0),max_amount:$('#discountMax').value||null,
      requires_manager:$('#discountManager').checked,active:true
    });
    e.target.reset();$('#discountMin').value='0';await loadDiscounts();toast('Discount saved');
  }catch(err){toast(err.message,true)}
};

$('#ingredientForm').onsubmit=async e=>{
  e.preventDefault();
  try{
    await admin('ingredient_save',{
      name:$('#ingredientName').value,unit:$('#ingredientUnit').value,
      stock_qty:Number($('#ingredientStock').value||0),low_stock_level:Number($('#ingredientLow').value||0),
      cost_per_unit:Number($('#ingredientCost').value||0),active:true
    });
    e.target.reset();$('#ingredientUnit').value='ml';await loadIngredients();toast('Ingredient added');
  }catch(err){toast(err.message,true)}
};

$('#adjustForm').onsubmit=async e=>{
  e.preventDefault();
  try{
    await admin('ingredient_adjust',{
      ingredient_id:$('#adjustIngredient').value,quantity_change:Number($('#adjustQty').value),
      reason:$('#adjustReason').value,note:$('#adjustNote').value
    });
    $('#adjustQty').value='';$('#adjustNote').value='';await loadIngredients();toast('Ingredient stock updated');
  }catch(err){toast(err.message,true)}
};

$('#supplierForm').onsubmit=async e=>{
  e.preventDefault();
  try{
    await admin('supplier_save',{
      name:$('#supplierName').value,contact_name:$('#supplierContact').value,
      phone:$('#supplierPhone').value,email:$('#supplierEmail').value,active:true
    });
    e.target.reset();await loadSuppliers();toast('Supplier saved');
  }catch(err){toast(err.message,true)}
};

$('#purchaseForm').onsubmit=async e=>{
  e.preventDefault();
  try{
    await admin('purchase_create',{
      supplier_id:$('#purchaseSupplier').value||null,staff_id:$('#purchaseStaff').value||null,
      reference_no:$('#purchaseReference').value||null,
      items:[{ingredient_id:$('#purchaseIngredient').value,quantity:Number($('#purchaseQty').value),unit_cost:Number($('#purchaseCost').value)}]
    });
    $('#purchaseQty').value='';$('#purchaseCost').value='';await Promise.all([loadSuppliers(),loadIngredients()]);toast('Purchase received');
  }catch(err){toast(err.message,true)}
};

$('#expenseForm').onsubmit=async e=>{
  e.preventDefault();
  try{
    await admin('expense_save',{category:$('#expenseCategory').value,amount:Number($('#expenseAmount').value),note:$('#expenseNote').value||null});
    e.target.reset();await Promise.all([loadExpenses(),loadSummary()]);toast('Expense recorded');
  }catch(err){toast(err.message,true)}
};

$('#businessSettingsForm').onsubmit=async e=>{
  e.preventDefault();
  try{
    await admin('business_settings_save',{settings:{
      store_name:$('#setStore').value,branch_name:$('#setBranch').value,receipt_address:$('#setAddress').value,
      receipt_phone:$('#setPhone').value,receipt_footer:$('#setFooter').value,
      require_open_shift:String($('#setRequireShift').checked),queue_enabled:String($('#setQueue').checked)
    }});
    toast('Business settings saved');
  }catch(err){toast(err.message,true)}
};


$('#refundLoadBtn').onclick=async()=>{
  const id=$('#refundOrderId').value.trim();
  if(!id){toast('Enter an order UUID.',true);return}
  try{
    const d=await admin('order_details',{order_id:id});
    refundOrder=d;
    const order=d.order,items=d.items||[];
    $('#refundDetails').innerHTML=
      '<div class="info"><span>Order</span><b>'+esc(order.order_no)+'</b></div>'+
      '<div class="info"><span>Total</span><b>'+money(order.total)+'</b></div>'+
      '<div class="info"><span>Status</span><b>'+esc(order.status)+'</b></div>'+
      '<div style="margin-top:10px">'+items.map(i=>
        '<label style="display:grid;grid-template-columns:auto 1fr 90px;gap:8px;align-items:center;padding:8px 0;border-bottom:1px solid var(--line)">'+
        '<input type="checkbox" class="refund-item-check" data-id="'+i.id+'" data-max="'+i.quantity+'">'+
        '<span><b>'+esc(i.product_name)+'</b><br><small>'+money(i.unit_price)+' each • sold '+i.quantity+'</small></span>'+
        '<input type="number" class="refund-item-qty" data-id="'+i.id+'" min="1" max="'+i.quantity+'" value="1">'+
        '</label>'
      ).join('')+'</div>';
    toast('Order loaded');
  }catch(e){refundOrder=null;$('#refundDetails').innerHTML='';toast(e.message,true)}
};

$('#refundForm').onsubmit=async e=>{
  e.preventDefault();
  if(!refundOrder){toast('Load an order first.',true);return}
  const selected=$('.refund-item-check:checked').map(ch=>{
    const qty=document.querySelector('.refund-item-qty[data-id="'+ch.dataset.id+'"]');
    return {order_item_id:ch.dataset.id,quantity:Number(qty?.value||1)};
  });
  if(!selected.length){toast('Select at least one item to refund.',true);return}
  try{
    const d=await admin('refund_order_admin',{
      order_id:refundOrder.order.id,
      items:selected,
      reason:$('#refundReason').value.trim(),
      staff_id:$('#refundStaff').value||null
    });
    toast('Refund processed: '+money(d.refund?.amount||0));
    refundOrder=null;$('#refundDetails').innerHTML='';$('#refundReason').value='';$('#refundOrderId').value='';
    await Promise.all([loadSummary(),loadAudit(),loadIngredients()]);
  }catch(err){toast(err.message,true)}
};

$('#backupBtn').onclick=async()=>{
  try{
    const data=await admin('backup_export');
    const blob=new Blob([JSON.stringify(data,null,2)],{type:'application/json'});
    const url=URL.createObjectURL(blob),a=document.createElement('a');
    a.href=url;a.download='DailyDash-Backup-'+new Date().toISOString().slice(0,10)+'.json';
    document.body.appendChild(a);a.click();a.remove();URL.revokeObjectURL(url);toast('Backup downloaded');
  }catch(err){toast(err.message,true)}
};

loadAll();