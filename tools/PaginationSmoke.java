import com.lgguan.linuxdo.plugin.net.*;
import com.intellij.openapi.util.Disposer;
import com.google.gson.*;
import java.nio.file.*;
import java.io.*;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;

/** Deterministic pagination and refresh in an isolated native browser, using packaged JavaScript. */
public class PaginationSmoke {
  static JsonElement eval(LinuxDoBrowser b,String js) throws Exception {
    Object client=b.getRuntime().call(b.getRawBrowser(),"getDevToolsClient");
    JsonObject args=new JsonObject();args.addProperty("expression",js);args.addProperty("returnByValue",true);
    JsonObject r=JsonParser.parseString((String)((CompletableFuture<?>)b.getRuntime().call(client,"executeDevToolsMethod","Runtime.evaluate",args.toString())).get(15,TimeUnit.SECONDS)).getAsJsonObject();
    if(r.has("exceptionDetails"))throw new AssertionError(r.get("exceptionDetails").toString());
    return r.getAsJsonObject("result").get("value");
  }
  static void check(LinuxDoBrowser b,PrintWriter out,String name,String js) throws Exception {
    boolean ok=eval(b,js).getAsBoolean();out.println(name+"="+ok);out.flush();if(!ok)throw new AssertionError(name);
  }
  static void await(LinuxDoBrowser b,String js) throws Exception {
    for(int i=0;i<100;i++){try{if(eval(b,js).getAsBoolean())return;}catch(Exception ignored){}Thread.sleep(100);}
    throw new AssertionError("Timed out: "+js);
  }
  static InputStream DocSource() { return PaginationSmoke.class.getResourceAsStream("/web/topic-pagination.js"); }
  public static void run() throws Exception {
    PrintWriter out = new PrintWriter(System.out, true);
    {
      LinuxDoBrowser b=new LinuxDoBrowser(IsolatedCefRuntime.Companion.get());
      try {
        String source;
        try (InputStream input = DocSource()) { source = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
        String setup="window.refreshCalls=[];window.linuxDoRefreshPosts=key=>refreshCalls.push(key);window.calls=[];window.fragment=(ids)=>ids.map(id=>'<div class=\"post-entry\" data-post-id=\"'+id+'\" data-post-number=\"'+id+'\" style=\"height:100px\">floor '+id+'</div>').join('');window.linuxDoPage={key:'test',stream:Array.from({length:160},(_,i)=>String(i+1))};window.linuxDoLoadPosts=(key,direction,ids)=>calls.push({key,direction,ids});document.querySelector('.doc-container').innerHTML=fragment(Array.from({length:20},(_,i)=>i+81));";
        SwingUtilities.invokeAndWait(()->{b.getComponent().setSize(800,600);b.createImmediately();});
        b.loadHTML("<!doctype html><meta charset=\"utf-8\"><style>body{margin:0}</style><div class='doc-container'></div><script>"+setup+source+"</script>");
        await(b,"window.calls && calls.length===1");
        check(b,out,"UPWARD_BATCH","calls[0].direction==='before' && calls[0].ids[0]==='61' && calls[0].ids.length===20");
        eval(b,"window.anchor=document.querySelector('[data-post-id=\"81\"]');window.anchorTop=anchor.getBoundingClientRect().top;linuxDoPagination.receive('stale','before','',false)");
        check(b,out,"STALE_RESPONSE_IGNORED","document.querySelectorAll('.post-entry').length===20 && document.querySelector('#load-posts-before').disabled");
        eval(b,"linuxDoPagination.receive('test','before',fragment(calls[0].ids.slice().reverse()),false)");
        check(b,out,"PREPEND_ANCHOR_STABLE","Math.abs(anchor.getBoundingClientRect().top-anchorTop)<2");
        check(b,out,"PREPEND_SORTED","document.querySelector('.post-entry').dataset.postId==='61' && document.querySelectorAll('.post-entry').length===40");
        eval(b,"dispatchEvent(new WheelEvent('wheel'));window.scrollTo(0,document.body.scrollHeight);dispatchEvent(new Event('scroll'))");
        await(b,"calls.length===2");
        check(b,out,"DOWNWARD_BATCH","calls[1].direction==='after' && calls[1].ids[0]==='101' && calls[1].ids.length===20");
        eval(b,"linuxDoPagination.receive('test','after','',true)");
        out.println("RETRY_STATE="+eval(b,"JSON.stringify({text:document.querySelector('#load-posts-after').textContent,disabled:document.querySelector('#load-posts-after').disabled})")); check(b,out,"RETRY_VISIBLE","document.querySelector('#load-posts-after').textContent.includes('重试') && !document.querySelector('#load-posts-after').disabled");
        eval(b,"dispatchEvent(new Event('scroll'))");Thread.sleep(300);
        check(b,out,"FAILURE_NO_RETRY_LOOP","calls.length===2");
        eval(b,"document.querySelector('#load-posts-after').click();document.querySelector('#load-posts-after').click()");
        check(b,out,"SINGLE_FLIGHT_RETRY","calls.length===3 && JSON.stringify(calls[2].ids)===JSON.stringify(calls[1].ids)");
        eval(b,"linuxDoPagination.receive('test','after',fragment(calls[2].ids.concat(['101']).reverse()),false)");
        check(b,out,"APPEND_DEDUP_SORTED","(()=>{let ids=Array.from(document.querySelectorAll('.post-entry')).map(e=>+e.dataset.postId);return ids.length===60 && new Set(ids).size===60 && ids.every((id,i)=>id===61+i)})()");
        eval(b,"dispatchEvent(new WheelEvent('wheel'));scrollTo(0,document.body.scrollHeight);dispatchEvent(new Event('scroll'))");await(b,"calls.length===4");
        eval(b,"linuxDoPagination.receive('test','after','',false);document.querySelector('#load-posts-after').click()");await(b,"calls.length===5");
        check(b,out,"UNAVAILABLE_POSTS_ADVANCE","calls[4].ids[0]==='141'");
        eval(b,"linuxDoPagination.receive('test','after',fragment(calls[4].ids),false)");
        check(b,out,"END_OF_STREAM","document.querySelector('#load-posts-after').hidden && getComputedStyle(document.querySelector('#load-posts-after')).display==='none'");
        check(b,out,"REFRESH_AVAILABLE_AT_END","!document.querySelector('#refresh-posts-bottom').disabled");
        b.onRefreshRequested(() -> {
          b.getCefBrowser().executeJavaScript("linuxDoPagination.refresh()", "", 0);
          return kotlin.Unit.INSTANCE;
        });
        // A native F5 must check the stream rather than navigating away from the generated page.
        SwingUtilities.invokeAndWait(() -> {
          b.getCefBrowser().setFocus(true);
          for (java.awt.event.KeyListener listener : b.getComponent().getKeyListeners()) {
            listener.keyPressed(new java.awt.event.KeyEvent(b.getComponent(), java.awt.event.KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(), 0, java.awt.event.KeyEvent.VK_F5, java.awt.event.KeyEvent.CHAR_UNDEFINED));
            listener.keyReleased(new java.awt.event.KeyEvent(b.getComponent(), java.awt.event.KeyEvent.KEY_RELEASED,
                System.currentTimeMillis(), 0, java.awt.event.KeyEvent.VK_F5, java.awt.event.KeyEvent.CHAR_UNDEFINED));
          }
        });
        await(b,"refreshCalls.length===1");
        check(b,out,"F5_REFRESH","refreshCalls[0]==='test'");
        eval(b,"linuxDoPagination.refresh();linuxDoPagination.refreshed('old',[161],false)");
        check(b,out,"REFRESH_SINGLE_FLIGHT_AND_STALE_GUARD","refreshCalls.length===1 && document.querySelector('#refresh-posts-bottom').disabled");
        eval(b,"linuxDoPagination.refreshed('test',[],true)");
        check(b,out,"REFRESH_FAILURE_RETAINS_POSTS","document.querySelectorAll('.post-entry').length===80 && document.querySelector('[role=status]').textContent.includes('失败')");
        eval(b,"document.querySelector('#refresh-posts-bottom').click();dispatchEvent(new WheelEvent('wheel'));window.scrollTo(0,document.body.scrollHeight);dispatchEvent(new Event('scroll'));window.lastAnchor=document.querySelector('[data-post-id=\"160\"]');window.lastTop=lastAnchor.getBoundingClientRect().top;linuxDoPagination.refreshed('test',Array.from({length:185},(_,i)=>String(i+1)),false)");
        await(b,"calls.length===6");
        check(b,out,"NEW_REPLY_BATCH_BOUNDED","calls[5].direction==='after' && calls[5].ids[0]==='161' && calls[5].ids.length===20");
        check(b,out,"REFRESH_ANCHOR_STABLE","Math.abs(lastAnchor.getBoundingClientRect().top-lastTop)<2");
        eval(b,"linuxDoPagination.refresh();linuxDoPagination.refresh();linuxDoPagination.receive('test','after',fragment(calls[5].ids),false)");
        check(b,out,"REFRESH_QUEUED_DURING_PAGINATION","refreshCalls.length===3 && document.querySelector('#refresh-posts-bottom').disabled");
        check(b,out,"NEW_REPLY_APPEND_ANCHOR_STABLE","Math.abs(lastAnchor.getBoundingClientRect().top-lastTop)<2 && document.querySelector('[data-post-id=\"180\"]')!==null");
        eval(b,"linuxDoPagination.refreshed('test',Array.from({length:185},(_,i)=>String(i+1)),false)");
        check(b,out,"NO_NEW_REPLIES_MESSAGE","document.querySelector('[role=status]').textContent.includes('暂无新回复')");
        eval(b,"dispatchEvent(new WheelEvent('wheel'));scrollTo(0,document.body.scrollHeight);dispatchEvent(new Event('scroll'))");await(b,"calls.length===7");
        eval(b,"linuxDoPagination.receive('test','after',fragment(calls[6].ids),false)");
        check(b,out,"NEW_STREAM_END","document.querySelector('[data-post-id=\"185\"]')!==null && document.querySelector('#load-posts-after').hidden && !document.querySelector('#refresh-posts-bottom').disabled");
        eval(b,"window.scrollTo(0,2500);window.middleTop=scrollY;linuxDoPagination.refresh();linuxDoPagination.refreshed('test',Array.from({length:187},(_,i)=>String(i+1)),false)");
        check(b,out,"REFRESH_MIDDLE_PRESERVES_POSITION","Math.abs(scrollY-middleTop)<2 && !document.querySelector('#load-posts-after').hidden");
        eval(b,"window.originalEntry=document.querySelector('.post-entry');linuxDoPagination.showReply('old','300',fragment(['300']))");
        check(b,out,"OLD_PAGE_REPLY_IGNORED","!document.querySelector('[data-post-id=\"300\"]')");
        eval(b,"linuxDoPagination.showReply('test','300',fragment(['300']))");
        check(b,out,"PUBLISHED_REPLY_JUMP","(()=>{const el=document.querySelector('[data-post-id=\"300\"]');const r=el.getBoundingClientRect();return r.top>=0 && r.top<innerHeight && el.classList.contains('highlight-flash') && originalEntry===document.querySelector('.post-entry')})()");
        eval(b,"linuxDoPagination.showReply('test','300',fragment(['300']))");
        check(b,out,"PUBLISHED_REPLY_DEDUP","document.querySelectorAll('[data-post-id=\"300\"]').length===1");
        Thread.sleep(300);
        check(b,out,"REPLY_DOES_NOT_BACKFILL_GAPS","calls.length===7 && document.querySelector('#load-posts-after').hidden");
        eval(b,"linuxDoPagination.refresh();linuxDoPagination.showReply('test','301',fragment(['301']));linuxDoPagination.refreshed('test',Array.from({length:187},(_,i)=>String(i+1)).concat(['300','301']),false)");
        check(b,out,"REFRESH_DURING_PUBLICATION","document.querySelectorAll('[data-post-id=\"301\"]').length===1 && document.querySelector('#load-posts-after').hidden");
        // Test navigation with explicit responses, independent of edge prefetch.
        eval(b,"window.linuxDoLoadPosts=null;window.jumpCalls=[];window.linuxDoJumpFloor=(key,id,floor)=>jumpCalls.push({key,id,floor});linuxDoPagination.refresh();linuxDoPagination.refreshed('test',Array.from({length:500},(_,i)=>String(i+1)),false,500);linuxDoPagination.jump(141)");
        check(b,out,"LOADED_FLOOR_JUMP_NO_REQUEST","jumpCalls.length===0 && document.querySelector('#floor-progress').value==='141'");
        eval(b,"linuxDoPagination.jump('0');linuxDoPagination.jump('abc')");
        check(b,out,"INVALID_FLOOR_NO_REQUEST","jumpCalls.length===0 && document.querySelector('[role=status]').textContent.includes('正整数')");
        eval(b,"window.slider=document.querySelector('#floor-progress');[210,220,250].forEach(floor=>{slider.value=floor;slider.dispatchEvent(new Event('input'))})");
        check(b,out,"SLIDER_PREVIEW_NO_REQUEST","jumpCalls.length===0 && document.querySelector('#floor-progress-label').textContent.includes('250')");
        eval(b,"slider.dispatchEvent(new Event('change'))");await(b,"jumpCalls.length===1");
        check(b,out,"SLIDER_RELEASE_REQUESTS_TARGET","jumpCalls[0].floor===250");
        eval(b,"linuxDoPagination.jumped('old',jumpCalls[0].id,[],fragment(['250']),false,500)");
        check(b,out,"STALE_FLOOR_RESPONSE_IGNORED","!document.querySelector('[data-post-id=\"250\"]')");
        eval(b,"window.allIds=Array.from({length:500},(_,i)=>String(i+1));linuxDoPagination.jumped('test',jumpCalls[0].id,allIds,fragment(['249','250','251']),false,500)");
        check(b,out,"UNLOADED_FLOOR_JUMP","document.querySelector('#floor-progress').value==='250' && document.querySelector('[data-post-id=\"250\"]').getBoundingClientRect().top<innerHeight && originalEntry===document.querySelector('.post-entry')");
        eval(b,"window.beforeFailureCount=document.querySelectorAll('.post-entry').length;linuxDoPagination.jump(499)");await(b,"jumpCalls.length===2");eval(b,"linuxDoPagination.jumped('test',jumpCalls[1].id,[], '',true,500)");
        check(b,out,"FLOOR_FAILURE_RETAINS_BODY","document.querySelectorAll('.post-entry').length===beforeFailureCount && document.querySelector('[role=status]').textContent.includes('重试')");
        eval(b,"linuxDoPagination.jump(400)");await(b,"jumpCalls.length===3");eval(b,"linuxDoPagination.jump(420);linuxDoPagination.jump(430);linuxDoPagination.jumped('test',jumpCalls[2].id,allIds,fragment(['400']),false,500)");
        await(b,"jumpCalls.length===4");
        check(b,out,"RAPID_JUMPS_COALESCE","jumpCalls.length===4 && jumpCalls[3].floor===430");
        eval(b,"linuxDoPagination.jumped('test',jumpCalls[3].id,allIds,fragment(['429','430','431']),false,500)");
        check(b,out,"LATEST_FLOOR_SELECTED","document.querySelector('#floor-progress').value==='430'");
        eval(b,"linuxDoPagination.jump(450)");await(b,"jumpCalls.length===5");eval(b,"linuxDoPagination.showReply('test','501',fragment(['501']));linuxDoPagination.jumped('test',jumpCalls[4].id,allIds.concat(['501']),fragment(['450']),false,501)");
        check(b,out,"REPLY_WINS_OVER_PENDING_JUMP","document.querySelector('#floor-progress').value==='501' && document.querySelector('[data-post-id=\"501\"]').getBoundingClientRect().top<innerHeight");
        out.println("PAGINATION_REFRESH_PASS=true");
      }finally{Disposer.dispose(b);}
    }
  }
}
