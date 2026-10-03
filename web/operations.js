const SUPABASE_URL='https://cpodvrwykhkndtwcsmgp.supabase.co';
const SUPABASE_KEY='sb_publishable_FeiWv8Dur_Qr3d0LF4RBhw_QSObAAHj';
const ADMIN_URL=SUPABASE_URL+'/functions/v1/dailydash-admin';
let pin=sessionStorage.getItem('dd_manager_pin')||'';
let ingredients=[],suppliers=[],staff=[],products=[],recipes=[],modifiersV21=[],customersV21=[],wasteV21=[],devicesV21=[],releasesV21=[],refundOrder=null;

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
  const [d,p]=await Promise.all([admin('ingredients'),admin('products')]);
  ingredients=d.ingredients||[];
  recipes=d.recipes||[];
  products=p.products||[];
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
  $('#recipeProduct').innerHTML=products.map(x=>'<option value="'+x.id+'">'+esc(x.name)+' — '+esc(x.category)+'</option>').join('');
  renderRecipeBuilder();
}

function ingredientOptionHtml(selected=''){
  return ingredients.map(x=>'<option value="'+x.id+'" '+(x.id===selected?'selected':'')+'>'+esc(x.name)+' ('+esc(x.unit)+')</option>').join('');
}

function recipeRow(ingredientId='',qty=''){
  return '<div class="recipe-row" style="display:grid;grid-template-columns:1fr 120px 42px;gap:8px;margin:8px 0">'+
    '<select class="recipe-ing" style="border:1px solid var(--line);border-radius:12px;padding:10px">'+ingredientOptionHtml(ingredientId)+'</select>'+
    '<input class="recipe-qty" type="number" step="0.001" min="0.001" value="'+esc(qty)+'" placeholder="Qty" style="border:1px solid var(--line);border-radius:12px;padding:10px">'+
    '<button type="button" class="tiny danger recipe-remove">×</button>'+
  '</div>';
}

function wireRecipeRows(){
  $('.recipe-remove').forEach(b=>b.onclick=()=>b.closest('.recipe-row').remove());
}

function renderRecipeBuilder(){
  const productId=$('#recipeProduct')?.value||products[0]?.id||'';
  const current=recipes.filter(r=>r.product_id===productId);
  $('#recipeRows').innerHTML=current.length
    ? current.map(r=>recipeRow(r.ingredient_id,r.qty)).join('')
    : (ingredients.length?recipeRow(ingredients[0].id,''):'<div class="empty">Add ingredients first.</div>');
  wireRecipeRows();
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


async function loadV21Summary(){
  const d=await admin('v21_summary');
  $('#sumCustomers').textContent=d.customers||0;
  $('#sumWaste').textContent=money(d.waste_cost_today||0);
  $('#sumReorder').textContent=d.reorder_alerts||0;
  $('#sumDevices').textContent=(d.online_devices||0)+' / '+(d.active_devices||0);
}

async function loadModifiersV21(){
  const [m,p,i]=await Promise.all([admin('modifiers'),admin('products'),admin('ingredients')]);
  modifiersV21=m.modifiers||[];
  products=p.products||products;
  ingredients=i.ingredients||ingredients;

  $('#modifierProduct').innerHTML=products.map(x=>
    '<option value="'+x.id+'">'+esc(x.name)+' — '+esc(x.category)+'</option>'
  ).join('');
  $('#modifierIngredient').innerHTML='<option value="">None</option>'+ingredients.map(x=>
    '<option value="'+x.id+'">'+esc(x.name)+' ('+esc(x.unit)+')</option>'
  ).join('');

  $('#modifierTable').innerHTML=table(
    ['Product','Group','Option','Price','Ingredient','Status','Actions'],
    modifiersV21.map(x=>[
      '<b>'+esc(x.dailydash_products?.name||x.product_id)+'</b><br><small>'+esc(x.dailydash_products?.category||'')+'</small>',
      esc(x.group_name)+'<br><small>'+esc(x.group_type)+(x.required?' • required':'')+'</small>',
      '<b>'+esc(x.name)+'</b>'+(x.is_default?'<br><small>Default</small>':''),
      (Number(x.price_delta)>=0?'+':'')+money(x.price_delta),
      x.dailydash_ingredients?.name
        ? esc(x.dailydash_ingredients.name)+'<br><small>'+Number(x.ingredient_qty||0)+' '+esc(x.dailydash_ingredients.unit||'')+'</small>'
        : '—',
      '<span class="status '+(x.active?'':'voided')+'">'+(x.active?'Active':'Disabled')+'</span>',
      '<div class="actions"><button class="tiny details" data-mod-edit="'+x.id+'">Edit</button>'+
      '<button class="tiny '+(x.active?'danger':'details')+'" data-mod-toggle="'+x.id+'" data-active="'+(!x.active)+'">'+(x.active?'Disable':'Enable')+'</button>'+
      '<button class="tiny danger" data-mod-delete="'+x.id+'">Delete</button></div>'
    ])
  );

  $$('[data-mod-edit]').forEach(b=>b.onclick=()=>{
    const x=modifiersV21.find(v=>v.id===b.dataset.modEdit); if(!x)return;
    $('#modifierId').value=x.id;
    $('#modifierProduct').value=x.product_id;
    $('#modifierGroup').value=x.group_name;
    $('#modifierType').value=x.group_type;
    $('#modifierName').value=x.name;
    $('#modifierPrice').value=x.price_delta;
    $('#modifierMax').value=x.max_select||1;
    $('#modifierIngredient').value=x.ingredient_id||'';
    $('#modifierIngredientQty').value=x.ingredient_qty||0;
    $('#modifierDefault').checked=!!x.is_default;
    $('#modifierRequired').checked=!!x.required;
    document.querySelector('[data-tab="modifiers"]').click();
  });

  $$('[data-mod-toggle]').forEach(b=>b.onclick=async()=>{
    try{
      await admin('modifier_toggle',{id:b.dataset.modToggle,active:b.dataset.active==='true'});
      await loadModifiersV21();toast('Modifier updated');
    }catch(e){toast(e.message,true)}
  });

  $$('[data-mod-delete]').forEach(b=>b.onclick=async()=>{
    if(!confirm('Delete this modifier option? Existing order history remains unchanged.'))return;
    try{
      await admin('modifier_delete',{id:b.dataset.modDelete});
      await loadModifiersV21();toast('Modifier deleted');
    }catch(e){toast(e.message,true)}
  });
}

async function loadCustomersV21(q=''){
  const d=await admin('customers',{q});
  customersV21=d.customers||[];
  $('#pointsCustomer').innerHTML=customersV21.map(x=>
    '<option value="'+x.id+'">'+esc(x.name)+' • '+esc(x.phone)+' • '+Number(x.points_balance||0)+' pts</option>'
  ).join('');

  $('#customerTable').innerHTML=table(
    ['Customer','Phone','Tier','Points','Lifetime spend','Actions'],
    customersV21.map(x=>[
      '<b>'+esc(x.name)+'</b>'+(x.birthday?'<br><small>Birthday '+esc(x.birthday)+'</small>':''),
      esc(x.phone),
      '<span class="pill">'+esc(x.tier||'Member')+'</span>',
      '<b>'+Number(x.points_balance||0).toLocaleString()+'</b>',
      money(x.lifetime_spend||0),
      '<button class="tiny details" data-customer-edit="'+x.id+'">Edit</button>'
    ])
  );

  $$('[data-customer-edit]').forEach(b=>b.onclick=()=>{
    const x=customersV21.find(v=>v.id===b.dataset.customerEdit); if(!x)return;
    $('#customerId').value=x.id;
    $('#customerName').value=x.name||'';
    $('#customerPhone').value=x.phone||'';
    $('#customerBirthday').value=x.birthday||'';
    $('#customerNotes').value=x.notes||'';
  });
}

function renderWasteItemOptions(){
  const kind=$('#wasteKind').value;
  if(kind==='product'){
    $('#wasteItem').innerHTML=products.map(x=>
      '<option value="'+x.id+'">'+esc(x.name)+' — '+esc(x.category)+'</option>'
    ).join('');
  }else{
    $('#wasteItem').innerHTML=ingredients.map(x=>
      '<option value="'+x.id+'">'+esc(x.name)+' ('+esc(x.unit)+')</option>'
    ).join('');
  }
}

async function loadWasteV21(){
  const [w,p,i,st]=await Promise.all([admin('waste'),admin('products'),admin('ingredients'),admin('staff')]);
  wasteV21=w.waste||[];
  products=p.products||products;
  ingredients=i.ingredients||ingredients;
  staff=st.staff||staff;

  $('#wasteStaff').innerHTML='<option value="">System / Manager</option>'+staff.filter(x=>x.is_active).map(x=>
    '<option value="'+x.id+'">'+esc(x.display_name)+'</option>'
  ).join('');
  renderWasteItemOptions();

  $('#wasteTable').innerHTML=table(
    ['Date','Item','Qty','Reason','Cost','Staff','Note'],
    wasteV21.map(x=>[
      dt(x.created_at),
      esc(x.dailydash_ingredients?.name||x.dailydash_products?.name||'Unknown'),
      Number(x.quantity||0).toLocaleString()+' '+esc(x.unit||''),
      esc(String(x.reason||'').replaceAll('_',' ')),
      money(x.estimated_cost||0),
      esc(x.dailydash_staff?.display_name||'System'),
      esc(x.note||'')
    ])
  );
}

async function loadReorderV21(){
  const d=await admin('smart_reorder');
  const rows=d.suggestions||[];
  $('#reorderTable').innerHTML=table(
    ['Ingredient','Stock','Avg/day','Days left','Suggested reorder','Est. cost'],
    rows.map(x=>[
      '<b>'+esc(x.name)+'</b><br><small>'+esc(x.unit)+'</small>',
      Number(x.stock_qty||0).toLocaleString(),
      Number(x.avg_daily||0).toLocaleString(undefined,{maximumFractionDigits:2}),
      x.days_remaining==null?'No recent usage':Number(x.days_remaining).toFixed(1)+' days',
      Number(x.suggested_qty||0)>0
        ? '<b class="danger-text">'+Number(x.suggested_qty).toLocaleString()+' '+esc(x.unit)+'</b>'
        : '<span class="status">Enough</span>',
      money(Math.round(Number(x.suggested_qty||0)*Number(x.cost_per_unit||0)))
    ])
  );
}

async function loadDevicesV21(){
  const d=await admin('devices');
  devicesV21=d.devices||[];
  $('#deviceTable').innerHTML=table(
    ['Device','Version','Current staff','Last seen','Status','Actions'],
    devicesV21.map(x=>{
      const online=Date.now()-new Date(x.last_seen_at).getTime()<10*60*1000;
      return [
        '<b>'+esc(x.display_name)+'</b><br><small>'+esc(x.device_code)+'</small>',
        esc(x.app_version||'Unknown'),
        esc(x.dailydash_staff?.display_name||'—'),
        dt(x.last_seen_at)+(online?' <span class="pill">Online</span>':''),
        '<span class="status '+(x.is_active?'':'voided')+'">'+(x.is_active?'Enabled':'Disabled')+'</span>',
        '<div class="actions"><button class="tiny details" data-device-rename="'+x.id+'">Rename</button>'+
        '<button class="tiny '+(x.is_active?'danger':'details')+'" data-device-toggle="'+x.id+'" data-active="'+(!x.is_active)+'">'+(x.is_active?'Disable':'Enable')+'</button></div>'
      ];
    })
  );

  $$('[data-device-toggle]').forEach(b=>b.onclick=async()=>{
    try{
      await admin('device_toggle',{id:b.dataset.deviceToggle,active:b.dataset.active==='true'});
      await loadDevicesV21();toast('Device access updated');
    }catch(e){toast(e.message,true)}
  });
  $$('[data-device-rename]').forEach(b=>b.onclick=async()=>{
    const x=devicesV21.find(v=>v.id===b.dataset.deviceRename); if(!x)return;
    const name=prompt('Device name',x.display_name||'DailyDash POS'); if(!name)return;
    try{
      await admin('device_save',{id:x.id,display_name:name,notes:x.notes||null});
      await loadDevicesV21();toast('Device renamed');
    }catch(e){toast(e.message,true)}
  });
}

async function loadReleasesV21(){
  const d=await admin('app_releases');
  releasesV21=d.releases||[];
  $('#releaseTable').innerHTML=table(
    ['Version','Published','Required','Status','Changelog'],
    releasesV21.map(x=>[
      '<b>v'+esc(x.version_name)+'</b><br><small>Code '+Number(x.version_code)+'</small>',
      dt(x.published_at),
      x.required?'<span class="status low">Required</span>':'Optional',
      x.active?'<span class="status">Active</span>':'Disabled',
      '<small>'+esc(x.changelog||'')+'</small>'
    ])
  );
}

async function loadAll(){
  try{
    await Promise.all([
      loadSummary(),loadV21Summary(),loadDiscounts(),loadModifiersV21(),loadCustomersV21(),
      loadIngredients(),loadSuppliers(),loadExpenses(),loadWasteV21(),loadReorderV21(),
      loadDevicesV21(),loadReleasesV21(),loadShifts(),loadAudit(),loadSettings()
    ]);
  }catch(e){toast(e.message,true)}
}

$$('.ops-tab').forEach(b=>b.onclick=()=>{
  $$('.ops-tab').forEach(x=>x.classList.remove('active'));b.classList.add('active');
  $$('.ops-panel').forEach(x=>x.classList.remove('active'));$('#'+b.dataset.tab).classList.add('active');
});
$('#opsRefresh').onclick=loadAll;


$('#modifierForm').onsubmit=async e=>{
  e.preventDefault();
  try{
    await admin('modifier_save',{
      id:$('#modifierId').value||null,
      product_id:$('#modifierProduct').value,
      group_name:$('#modifierGroup').value,
      group_type:$('#modifierType').value,
      name:$('#modifierName').value,
      price_delta:Number($('#modifierPrice').value||0),
      max_select:Number($('#modifierMax').value||1),
      ingredient_id:$('#modifierIngredient').value||null,
      ingredient_qty:Number($('#modifierIngredientQty').value||0),
      is_default:$('#modifierDefault').checked,
      required:$('#modifierRequired').checked,
      active:true
    });
    $('#modifierId').value='';
    $('#modifierName').value='';
    $('#modifierPrice').value='0';
    $('#modifierIngredientQty').value='0';
    $('#modifierDefault').checked=false;
    await loadModifiersV21();
    toast('Modifier saved');
  }catch(err){toast(err.message,true)}
};

$('#customerForm').onsubmit=async e=>{
  e.preventDefault();
  try{
    await admin('customer_save',{
      id:$('#customerId').value||null,
      name:$('#customerName').value,
      phone:$('#customerPhone').value,
      birthday:$('#customerBirthday').value||null,
      notes:$('#customerNotes').value||null,
      active:true
    });
    e.target.reset();$('#customerId').value='';
    await loadCustomersV21();
    toast('Customer saved');
  }catch(err){toast(err.message,true)}
};

$('#pointsForm').onsubmit=async e=>{
  e.preventDefault();
  try{
    await admin('loyalty_adjust',{
      customer_id:$('#pointsCustomer').value,
      points_change:Number($('#pointsChange').value||0),
      note:$('#pointsNote').value||'Manager adjustment'
    });
    $('#pointsChange').value='';$('#pointsNote').value='';
    await loadCustomersV21($('#customerSearch').value);
    toast('Loyalty points updated');
  }catch(err){toast(err.message,true)}
};

$('#customerSearch').oninput=()=>{
  clearTimeout(window.ddCustomerSearchTimer);
  window.ddCustomerSearchTimer=setTimeout(()=>loadCustomersV21($('#customerSearch').value).catch(e=>toast(e.message,true)),250);
};

$('#wasteKind').onchange=renderWasteItemOptions;
$('#wasteForm').onsubmit=async e=>{
  e.preventDefault();
  const kind=$('#wasteKind').value;
  try{
    await admin('waste_save',{
      staff_id:$('#wasteStaff').value||null,
      ingredient_id:kind==='ingredient'?$('#wasteItem').value:null,
      product_id:kind==='product'?$('#wasteItem').value:null,
      quantity:Number($('#wasteQty').value||0),
      reason:$('#wasteReason').value,
      note:$('#wasteNote').value||null
    });
    $('#wasteQty').value='';$('#wasteNote').value='';
    await Promise.all([loadWasteV21(),loadIngredients(),loadReorderV21(),loadV21Summary()]);
    toast('Waste recorded');
  }catch(err){toast(err.message,true)}
};

$('#reorderRefresh').onclick=()=>loadReorderV21().catch(e=>toast(e.message,true));

$('#releaseForm').onsubmit=async e=>{
  e.preventDefault();
  try{
    await admin('app_release_save',{
      version_code:Number($('#releaseCode').value),
      version_name:$('#releaseName').value,
      update_url:$('#releaseUrl').value||null,
      changelog:$('#releaseChangelog').value,
      required:$('#releaseRequired').checked,
      active:$('#releaseActive').checked
    });
    await loadReleasesV21();
    toast('Release metadata published');
  }catch(err){toast(err.message,true)}
};

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


$('#recipeProduct').onchange=renderRecipeBuilder;
$('#recipeAddRow').onclick=()=>{
  if(!ingredients.length){toast('Add ingredients first.',true);return}
  $('#recipeRows').insertAdjacentHTML('beforeend',recipeRow(ingredients[0].id,''));
  wireRecipeRows();
};
$('#recipeSave').onclick=async()=>{
  const productId=$('#recipeProduct').value;
  const items=$('.recipe-row').map(row=>({
    ingredient_id:row.querySelector('.recipe-ing').value,
    qty:Number(row.querySelector('.recipe-qty').value||0)
  })).filter(x=>x.ingredient_id&&x.qty>0);

  const ids=items.map(x=>x.ingredient_id);
  if(new Set(ids).size!==ids.length){toast('Do not add the same ingredient twice.',true);return}
  try{
    await admin('recipe_save',{product_id:productId,items});
    toast('Recipe saved');
    await loadIngredients();
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