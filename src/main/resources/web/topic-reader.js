(function () {
  'use strict';
  const config = window.linuxDoPage;
  if (!config || window.linuxDoReaderBound) return;
  window.linuxDoReaderBound = true;
  let sequence = 0, activePanel = null, panelVersion = 0;
  const requests = new Map(), pendingPosts = new Set();
  const toast = message => window.showDocToast?.(message);
  function call(action, postId, input = {}) {
    return new Promise(resolve => {
      const id = String(++sequence);
      requests.set(id, resolve);
      if (!window.intellijBridge?.readerAction) { requests.delete(id); resolve({error:'操作尚未就绪'}); return; }
      window.intellijBridge.readerAction(config.key, id, action, String(postId || 0), input);
    });
  }
  window.linuxDoReaderResult = function(key, id, result) {
    if (key !== config.key) return;
    const finish = requests.get(id); requests.delete(id);
    if (result.html) window.linuxDoPagination?.patch(result.html);
    if (result.topic) Object.assign(config, result.topic);
    refreshTools();
    if (result.topicUnavailable) { document.querySelectorAll('.floor-actions button,.floor-actions [onclick]').forEach(el=>{el.disabled=true;el.removeAttribute('onclick');});toast(result.message); }
    finish?.(result);
  };
  function button(label, action) {
    const b = document.createElement('button'); b.type='button'; b.textContent=label; b.className='topic-nav-button';
    b.onclick=action; return b;
  }
  function panel(title) {
    tools.open=false;
    activePanel?.remove(); panelVersion++;
    const box = document.createElement('section'); box.className='topic-reader-panel'; box.setAttribute('aria-label',title);
    const heading = document.createElement('strong'); heading.textContent=title;
    const close=button('关闭',()=>{box.remove(); activePanel=null; panelVersion++;});
    box.append(heading,close); document.body.append(box); activePanel=box; return box;
  }
  function text(box, message) { const p=document.createElement('p'); p.textContent=message; box.append(p); return p; }
  function field(box, label, value='', type='text') {
    const wrap=document.createElement('label'); wrap.textContent=label;
    const input=document.createElement('input'); input.type=type; input.value=value; wrap.append(input); box.append(wrap); return input;
  }
  function finish(result) { toast(result.error || result.message || '操作已完成'); }
  async function mutate(post, action, input={}) {
    const id=post.dataset.postId;
    if (pendingPosts.has(id)) return;
    pendingPosts.add(id); post.classList.add('post-operation-pending');
    try { const result=await call(action,id,input); finish(result); return result; }
    finally { pendingPosts.delete(id); post.classList.remove('post-operation-pending'); }
  }
  window.toggleLikeUi = function(el, postId, like) {
    const post=el.closest('.post-entry'); if (!post || pendingPosts.has(String(postId))) return;
    // No eager text replacement: authoritative success/failure patches only this post's actions.
    mutate(post,'like',{like});
  };
  function jumpButton(box, item, quote=true, topic=config.topic) {
    const row=document.createElement('div'); row.className='topic-reader-result';
    row.append(button('#'+item.floor+' @'+(item.author || ''),()=>topic===config.topic?window.linuxDoPagination?.jump(item.floor):window.intellijBridge?.handleLinkClick('https://linux.do/t/'+topic+'/'+item.floor)));
    text(row,item.text || '');
    if (quote) row.append(button('引用',()=>window.intellijBridge?.quoteReply(item.floor,item.text)));
    box.append(row);
  }
  function search() {
    const box=panel('帖内搜索');
    const input=field(box,'搜索内容'); input.maxLength=500;
    const local=document.createElement('div'), remote=document.createElement('div');
    let page=1, term='', busy=false;
    const find=()=>{
      local.replaceChildren(); const value=input.value.trim().toLocaleLowerCase();
      if (!value) return;
      document.querySelectorAll('.post-entry').forEach(post=>{
        const body=post.querySelector('.post-content').textContent;
        const at=body.toLocaleLowerCase().indexOf(value);
        if(at>=0) jumpButton(local,{floor:Number(post.dataset.postNumber),author:post.dataset.author,text:body.slice(Math.max(0,at-60),at+160)},false);
      });
      if (!local.children.length) text(local,'已加载的内容中没有匹配结果');
    };
    const more=button('下一页',()=>remoteSearch(false)); more.hidden=true;
    async function remoteSearch(reset) {
      if(busy || !input.value.trim()) return;
      busy=true; const version=panelVersion;
      if(reset){page=1; term=input.value.trim(); remote.replaceChildren();}
      more.disabled=true;
      const result=await call('search',0,{query:term,page}); busy=false;
      if(version!==panelVersion) return;
      if(result.error){text(remote,result.error);more.hidden=false;more.disabled=false;more.textContent='重试当前页';return;}
      else { result.items?.forEach(item=>jumpButton(remote,item,false)); if(!result.items?.length) text(remote,'没有匹配结果'); page++; }
      more.hidden=!result.more; more.disabled=false;more.textContent='下一页';
    }
    box.append(button('查找已加载内容',find),button('搜索整个话题',()=>remoteSearch(true)),local,remote,more);
    text(box,'整个话题搜索使用服务器分页，每次只读取一页结果。'); input.focus();
  }
  async function filter(author,popular=false) {
    if(!window.linuxDoPagination?.beginFilter()) { toast('正文正在加载，请稍后重新筛选'); return; }
    const result=await call('filter',0,{author:author || '',popular});
    window.linuxDoPagination.endFilter();
    if(result.error) finish(result);
    else if(!window.linuxDoPagination?.filter(result.ids,result.fragment)) toast('正文正在加载，请稍后重新筛选');
    else { config.filtered=!!author||popular;refreshTools();toast(result.message || '已按服务器结果筛选，保留原始楼层编号'); }
  }
  function directory() {
    const box=panel('当前可用正文目录');
    document.querySelectorAll('.post-entry .post-content h1,.post-entry .post-content h2,.post-entry .post-content h3,.post-entry .post-content h4,.post-entry .post-content h5,.post-entry .post-content h6').forEach(h=>{
      const floor=h.closest('.post-entry').dataset.postNumber;
      box.append(button('#'+floor+' · '+h.textContent,()=>h.scrollIntoView({block:'start',behavior:'smooth'})));
    });
    if(box.children.length===2) text(box,'当前正文没有标题；加载其他楼层后可再次打开目录。');
  }
  function appearance() {
    const box=panel('正文阅读设置');
    const font=field(box,'字号（0 = 沿用 IDE）',config.fontSize || 0,'number'); font.min='0';font.max='32';
    const line=field(box,'行距',config.lineHeight || 1.7,'number'); line.min='1.2';line.max='2.5';line.step='.1';
    const width=field(box,'阅读宽度',config.width || 980,'number'); width.min='480';width.max='1600';
    box.append(button('保存',async()=>{
      const result=await call('appearance',0,{font:Number(font.value),line:Number(line.value),width:Number(width.value)});
      if(!result.error){document.body.style.fontSize=(Number(font.value)||config.defaultFontSize)+'px';document.body.style.lineHeight=line.value;document.querySelector('.doc-container').style.maxWidth=width.value+'px';}
      finish(result);
    }));
  }
  async function bookmarks() {
    const box=panel('我的书签'); let page=0,busy=false;
    const more=button('加载下一页',load);
    async function load(){
      if(busy)return;busy=true;more.disabled=true; const version=panelVersion;
      const result=await call('bookmarks',0,{page});busy=false;
      if(version!==panelVersion)return;
      if(result.error){text(box,result.error);more.hidden=false;more.disabled=false;more.textContent='重试当前页';return;}
      else result.items?.forEach(item=>{const row=document.createElement('div');row.append(button(item.title+' · #'+item.floor,()=>window.intellijBridge?.handleLinkClick('https://linux.do/t/'+item.topic+'/'+item.floor)));text(row,item.name || '');box.insertBefore(row,more);});
      more.hidden=!result.more;more.disabled=false;more.textContent='加载下一页';page++;
    }
    box.append(more);load();
  }
  async function subscription(){
    const box=panel('话题通知');
    text(box,'当前：'+({3:'关注',2:'跟踪',1:'常规',0:'免打扰'}[config.notificationLevel] || '服务器状态尚未提供'));
    [ ['关注',3],['跟踪',2],['常规',1],['免打扰',0] ].forEach(([name,level])=>{
      const b=button(name,async()=>{b.disabled=true;const result=await call('subscription',0,{level});b.disabled=false;finish(result);if(!result.error)text(box,'已设为'+name);});
      b.disabled=config.notificationLevel===null || config.notificationLevel===undefined || !config.loggedIn;box.append(b);
    });
  }
  const tools=document.createElement('details'); tools.className='topic-reader-tools';
  const summary=document.createElement('summary');summary.title='阅读工具';summary.setAttribute('aria-label','阅读工具');
  summary.innerHTML='<svg class="reader-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" aria-hidden="true" focusable="false"><path d="M4 6h16M4 12h16M4 18h16M8 3v6M16 9v6M10 15v6"/></svg>';tools.append(summary);
  const menu=document.createElement('div');menu.className='topic-reader-menu';tools.append(menu);
  const toolItems=[];
  function group(name){const box=document.createElement('section'),heading=document.createElement('strong');heading.textContent=name;box.append(heading);menu.append(box);return box;}
  function tool(box,label,action,available=()=>true){const b=button(label,action);box.append(b);toolItems.push({button:b,available});return b;}
  function refreshTools(){
    toolItems.forEach(item=>item.button.hidden=!item.available());
    menu.querySelectorAll('section').forEach(box=>box.hidden=![...box.querySelectorAll('button')].some(b=>!b.hidden));
  }
  window.refreshDocReaderTools=refreshTools;
  const navigation=group('阅读'),filters=group('筛选'),account=group('话题');
  const multiple=()=>window.linuxDoPagination?.lastFloor()>1;
  const signedIn=()=>config.loggedIn===true;
  async function edge(last){if(!config.filtered){window.linuxDoPagination?.jump(last?window.linuxDoPagination.lastFloor():1);return;}const result=await call('edge',0,{last});if(result.error)finish(result);else window.linuxDoPagination?.jump(result.floor);}
  tool(navigation,'首楼',()=>edge(false),multiple);
  tool(navigation,'末楼',()=>edge(true),multiple);
  tool(navigation,'未读',()=>window.linuxDoPagination?.jump(config.unreadFloor),()=>signedIn()&&Number(config.unreadFloor)>0&&Number(config.unreadFloor)<=window.linuxDoPagination?.lastFloor());
  tool(navigation,'帖内搜索',search);
  tool(navigation,'目录',directory,()=>!!document.querySelector('.post-entry .post-content :is(h1,h2,h3,h4,h5,h6)'));
  tool(navigation,'阅读设置',appearance);
  tool(filters,'只看楼主',()=>filter(config.author),()=>multiple()&&!!config.author);
  tool(filters,'指定作者',()=>{const box=panel('按作者筛选');const name=field(box,'用户名');box.append(button('查看',()=>filter(name.value.trim())));},multiple);
  tool(filters,'热门回复',()=>filter('',true),multiple);
  tool(filters,'全部楼层',()=>filter(''),()=>config.filtered===true);
  tool(account,'通知',subscription,()=>signedIn()&&config.notificationLevel!==null&&config.notificationLevel!==undefined);
  tool(account,'我的书签',bookmarks,signedIn);
  if(config.canVote===true || config.userVoted===true) {
    const label=()=> (config.userVoted?'撤回话题投票 ':'话题投票 ')+(config.voteCount ?? 0);
    const vote=button(label(),async()=>{vote.disabled=true;const result=await call('topicVote',0,{});finish(result);vote.disabled=!(config.canVote||config.userVoted)||!config.loggedIn;vote.textContent=label();vote.title='投票余额 '+(config.votesLeft ?? '服务器未提供');});
    vote.title='投票余额 '+(config.votesLeft ?? '服务器未提供');account.append(vote);toolItems.push({button:vote,available:()=>signedIn()&&(config.canVote===true||config.userVoted===true)});
  }
  (document.querySelector('.doc-header')||document.body).append(tools);
  refreshTools();
  new MutationObserver(refreshTools).observe(document.querySelector('.doc-container'),{childList:true,subtree:true});
  document.addEventListener('click',e=>{
    if(!e.target.closest('.topic-reader-tools'))tools.open=false;
    document.querySelectorAll('.post-actions-menu[open]').forEach(el=>{if(!el.contains(e.target)||e.target.closest('button'))el.open=false;});
  });
  document.addEventListener('keydown',e=>{if(e.key==='Escape'){tools.open=false;document.querySelectorAll('.post-actions-menu[open]').forEach(el=>el.open=false);}});
  function positionMenu(el){
    const box=el.querySelector('.post-actions-menu-items,.topic-reader-menu'),r=el.querySelector('summary').getBoundingClientRect();
    box.style.left=Math.max(12,Math.min(innerWidth-box.offsetWidth-12,r.right-box.offsetWidth))+'px';
    box.style.top=Math.max(12,Math.min(innerHeight-box.offsetHeight-12,r.bottom+box.offsetHeight+12<=innerHeight?r.bottom+6:r.top-box.offsetHeight-6))+'px';
  }
  document.addEventListener('toggle',e=>{
    const el=e.target;if(!el.matches('.post-actions-menu,.topic-reader-tools')||!el.open)return;
    document.querySelectorAll('.post-actions-menu[open],.topic-reader-tools[open]').forEach(other=>{if(other!==el)other.open=false;});
    positionMenu(el);
  },true);
  let menuFrame=null;
  function repositionMenus(){
    if(menuFrame!==null)return;
    menuFrame=requestAnimationFrame(()=>{
      menuFrame=null;
      document.querySelectorAll('.post-actions-menu[open],.topic-reader-tools[open]').forEach(el=>{
        const r=el.querySelector('summary').getBoundingClientRect();
        if(r.bottom<0||r.top>innerHeight)el.open=false;
        else positionMenu(el);
      });
    });
  }
  addEventListener('resize',repositionMenus);
  addEventListener('scroll',repositionMenus,{passive:true});
  document.addEventListener('click',async e=>{
    const control=e.target.closest('[data-reader-action]');
    if(control && !control.disabled){
      const post=control.closest('.post-entry');if(!post)return;
      const action=control.dataset.readerAction;
      if(['edit','delete','recover','accept','unaccept','postVote'].includes(action)){await mutate(post,action,control.dataset.direction?{direction:control.dataset.direction}:{});return;}
      const box=panel(control.textContent+' · #'+post.dataset.postNumber),version=panelVersion;
      control.disabled=true;const result=await call(action+'Info',post.dataset.postId,{});control.disabled=false;
      if(version!==panelVersion)return;if(result.error){text(box,result.error);return;}
      if(action==='bookmark'){
        const name=field(box,'收藏名称',result.name || '');name.maxLength=100;
        const reminder=field(box,'提醒时间（本地时间，可空）',result.reminder?new Date(Date.parse(result.reminder)-new Date().getTimezoneOffset()*60000).toISOString().slice(0,16):'','datetime-local');
        box.append(button('保存收藏',()=>mutate(post,'bookmark',{name:name.value,reminder:reminder.value?new Date(reminder.value).toISOString():''})));
        if(result.bookmarked)box.append(button('取消收藏',()=>mutate(post,'unbookmark')));
      }else if(action==='flag'){
        const select=document.createElement('select'); const explanation=text(box,'选择举报原因');
        result.types?.forEach(type=>{const option=document.createElement('option');option.value=String(type.id);option.textContent=type.name;select.append(option);});box.append(select);
        const message=document.createElement('textarea');message.maxLength=4000;message.setAttribute('aria-label','举报说明');box.append(message);
        const submit=button('确认举报',()=>mutate(post,'flag',{type:Number(select.value),message:message.value}));
        const update=()=>{const type=result.types?.find(t=>String(t.id)===select.value);explanation.textContent=type?(type.description||'')+(type.requireMessage?'（必须填写说明）':''):'服务器未提供可用的举报类型';submit.disabled=!type||(type.requireMessage&&!message.value.trim());};select.onchange=message.oninput=update;update();box.append(submit);
      }else if(action==='reaction'){
        text(box,'当前回应：'+(result.current||'无')+'；再次选择当前回应可取消。');
        result.reactions?.forEach(reaction=>box.append(button(reaction,()=>mutate(post,'reaction',{reaction}))));
      }else if(action==='history'){
        const before=document.createElement('pre'),after=document.createElement('pre'),status=text(box,'');text(box,'修改前');box.append(before);text(box,'修改后');box.append(after);
        let current=result,busy=false;
        async function read(revision){if(busy)return;busy=true;earlier.disabled=later.disabled=true;const history=await call('historyInfo',post.dataset.postId,{revision});busy=false;if(version!==panelVersion)return;if(history.error)finish(history);else current=history;show();}
        const earlier=button('较早版本',()=>read(current.previous)),later=button('较新版本',()=>read(current.next));box.append(earlier,later);
        function show(){before.textContent=current.before||'';after.textContent=current.after||'';status.textContent='版本 '+current.version+' · '+(current.reason||'无编辑说明')+(current.unavailable?'；服务器未提供可见差异':'');earlier.disabled=!current.previous;later.disabled=!current.next;}show();
      }else if(action==='replies'){
        let after=1;
        const show=result=>{result.items?.forEach(item=>{jumpButton(box,item);after=Math.max(after,item.floor);});};show(result);
        const more=button('加载更多回复',async()=>{more.disabled=true;const next=await call('repliesInfo',post.dataset.postId,{after});more.disabled=false;if(next.error)finish(next);else{show(next);more.hidden=!next.more;}});more.hidden=!result.more;box.append(more);
      }else if(action==='reactionUsers'){
        let page=0;
        const show=result=>result.items?.forEach(item=>text(box,'@'+item.username+' · '+(item.reaction||'')));show(result);
        const more=button('加载更多回应者',async()=>{more.disabled=true;const next=await call('reactionUsersInfo',post.dataset.postId,{page:++page});more.disabled=false;if(next.error)finish(next);else{show(next);more.hidden=!next.more;}});more.hidden=!result.more;box.append(more);
      }
      return;
    }
    const quote=e.target.closest('aside.quote .quote-controls,[data-context-floor]');
    if(quote){
      const aside=quote.closest('aside.quote'),floor=Number(aside?.dataset.post || quote.dataset.contextFloor),topic=Number(aside?.dataset.topic)||config.topic;
      e.preventDefault();e.stopPropagation();
      const box=panel('引用上下文 · #'+floor), version=panelVersion;
      const result=await call('context',0,{floor,topic});if(version!==panelVersion)return;
      if(result.error)text(box,result.error);else result.items?.forEach(item=>jumpButton(box,item,topic===config.topic,topic));
      box.append(button('定位原帖',()=>window.intellijBridge?.handleLinkClick('https://linux.do/t/'+topic+'/'+floor)));
    }
    const author=e.target.closest('[data-reader-author]');
    if(author){const box=panel('公开资料'),version=panelVersion;const result=await call('profile',0,{username:author.dataset.readerAuthor});if(version!==panelVersion)return;if(result.error)text(box,result.error);else {const labels={username:'用户名',name:'姓名',title:'称号',bio_cooked:'简介',created_at:'加入时间',trust_level:'信任等级'};Object.entries(result).forEach(([key,value])=>text(box,(labels[key]||key)+'：'+value));}}
  });
  const pollPermissions=new Map();
  function polls(root){
    (root.matches?.('.post-entry')?[root]:root.querySelectorAll('.post-entry')).forEach(post=>{
      const serialized=post.dataset.polls;
      if(!serialized || post.dataset.pollsRendered===serialized+'|'+post.dataset.pollVotes)return;
      post.dataset.pollsRendered=serialized+'|'+post.dataset.pollVotes;
      post.querySelector('.reader-polls')?.remove();
      const data=JSON.parse(serialized);if(!data.length)return;
      const box=document.createElement('div');box.className='reader-polls';post.append(box);
      const votes=JSON.parse(post.dataset.pollVotes || 'null') || {};
      const permission=pollPermissions.get(post.dataset.postId);
      if(!permission && data.some(poll=>poll.status==='open')){
        const prepare=button('参与投票',async()=>{
          prepare.disabled=true;
          const result=await call('pollInfo',post.dataset.postId,{});
          if(result.error){finish(result);prepare.disabled=false;return;}
          pollPermissions.set(post.dataset.postId,{undo:result.undo,groups:result.groups,readOnly:result.readOnly});
          if(pollPermissions.size>400)pollPermissions.delete(pollPermissions.keys().next().value);
          const current=document.querySelector('.post-entry[data-post-id="'+post.dataset.postId+'"]');
          if(current){delete current.dataset.pollsRendered;polls(current);}
        });
        prepare.disabled=!config.loggedIn;prepare.title=config.loggedIn?'读取论坛允许的投票方式':'请先登录';box.append(prepare);
      }
      data.forEach(poll=>{
        const form=document.createElement('form');form.className='reader-poll';
        const description=text(form,(poll.title || poll.name)+' · '+(poll.status==='open'?'投票进行中':'已关闭')+' · '+poll.voters+' 人投票');
        const groups=String(poll.groups||'').split(',').filter(Boolean);
        const allowed=!!permission && !permission.readOnly && config.loggedIn && poll.status==='open' && (!groups.length || groups.some(group=>permission.groups?.includes(group)));
        const reason=!config.loggedIn?'请先登录':poll.status!=='open'?'问卷已关闭':!permission?'点击参与投票以读取论坛权限':permission.readOnly?'论坛处于只读状态':!allowed?'不在允许投票的用户组':'';
        const options=[];const rows=document.createElement('div');form.append(rows);
        poll.options?.forEach(option=>{
          const row=document.createElement('label');const input=document.createElement('input');input.type=poll.type==='multiple'?'checkbox':'radio';input.name=poll.name;input.value=option.id;input.checked=votes[poll.name]?.includes(option.id)||false;input.disabled=!allowed;
          const plain=new DOMParser().parseFromString(option.html || String(option.id),'text/html').body.textContent;
          const label=document.createElement('span');label.textContent=plain+(option.votes!==undefined?' · '+option.votes+' 票':'');
          if(poll.type==='ranked_choice'){
            row.dataset.option=option.id;const up=button('↑',()=>{if(row.previousElementSibling)rows.insertBefore(row,row.previousElementSibling);}),down=button('↓',()=>{if(row.nextElementSibling)rows.insertBefore(row.nextElementSibling,row);});up.disabled=down.disabled=!allowed;row.append(label,up,down);
          }else row.append(input,label);
          rows.append(row);options.push(input);
        });
        const submit=button('提交投票',()=>{const chosen=poll.type==='ranked_choice'?Array.from(rows.children).map(row=>row.dataset.option):options.filter(input=>input.checked).map(input=>input.value);mutate(post,'poll',{name:poll.name,options:chosen});});
        const validate=()=>{const count=options.filter(input=>input.checked).length;const valid=poll.type==='ranked_choice'||(poll.type==='multiple'?count>=Number(poll.min||1)&&count<=Number(poll.max||options.length):count===1);submit.disabled=!allowed||!valid;submit.title=reason||(!valid?'请选择问卷要求的选项数量':'');};options.forEach(input=>input.onchange=validate);validate();form.append(submit);
        if(votes[poll.name]?.length){const undo=button('撤回投票',()=>mutate(post,'unpoll',{name:poll.name}));undo.disabled=!allowed||!permission?.undo;undo.title=reason||(!permission?.undo?'论坛不允许撤回投票':'');form.append(undo);}
        const types={regular:'单选',multiple:'多选',number:'数字',ranked_choice:'排序'},visibility={always:'始终显示',on_vote:'投票后显示',on_close:'关闭后显示',staff_only:'仅工作人员'};
        text(form,'类型：'+(types[poll.type]||poll.type)+(poll.type==='multiple'?'；选择 '+(poll.min||1)+' 至 '+(poll.max||options.length)+' 项':'')+'；结果可见性：'+(visibility[poll.results]||poll.results||'由论坛决定'));
        if(poll.ranked_choice_outcome)text(form,'排序结果：'+JSON.stringify(poll.ranked_choice_outcome));
        form.onsubmit=e=>e.preventDefault();box.append(form);
      });
    });
  }
  window.linuxDoPolls=polls;polls(document);
  let pending=false;
  new MutationObserver(()=>{if(pending)return;pending=true;requestAnimationFrame(()=>{pending=false;polls(document);});}).observe(document.querySelector('.doc-container'),{childList:true,subtree:true});
})();
