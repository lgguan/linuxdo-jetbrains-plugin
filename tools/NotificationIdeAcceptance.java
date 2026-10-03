import com.google.gson.*;
import com.intellij.notification.*;
import com.intellij.openapi.application.ApplicationStarter;
import com.intellij.openapi.application.WriteIntentReadAction;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.*;
import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState;
import com.lgguan.linuxdo.plugin.editor.*;
import com.lgguan.linuxdo.plugin.model.*;
import com.lgguan.linuxdo.plugin.net.*;
import com.lgguan.linuxdo.plugin.service.*;
import com.lgguan.linuxdo.plugin.ui.dialog.NotificationListPanel;
import com.lgguan.linuxdo.plugin.ui.toolwindow.*;
import kotlin.Unit;
import java.awt.*;
import java.awt.event.InputEvent;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import javax.imageio.ImageIO;

/** Actual project frames, FileEditorManager, popup actions and IDE notifications; all HTTP is memory-only. */
public final class NotificationIdeAcceptance implements ApplicationStarter {
  private static final Gson GSON=new Gson();
  private static PrintWriter report;
  private static Path output;
  private static int checks;
  private static final List<Project> projects=new ArrayList<>();
  private static final Fixture fixture=new Fixture();
  public String getCommandName(){return "linuxdo-notification-acceptance";}
  public boolean isHeadless(){return false;}
  public int getRequiredModality(){return NOT_IN_EDT;}
  public void main(List<String> args){
    int exit=1;
    try {
      output=Path.of(System.getProperty("linuxdo.host.report")).getParent();
      report=new PrintWriter(Files.newBufferedWriter(output.resolve("result.txt")),true);
      run(); report.println("TOTAL_CHECKS="+checks);report.println("IDE_UI_PASS=true");exit=0;
    } catch(Throwable error){error.printStackTrace();if(report!=null){report.println("ERROR="+error);error.printStackTrace(report);}
      try{for(Project p:projects)if(!p.isDisposed())shot(p,"failure-"+p.getName());}catch(Throwable ignored){}
    }
    finally {
      try {LinuxDoNotificationService.Companion.getInstance().stopPolling();
        for(Project p:projects)if(!p.isDisposed())edt(()->ProjectManagerEx.getInstanceEx().forceCloseProject(p));
        IsolatedCefRuntime runtime=IsolatedCefRuntime.Companion.currentOrNull();
        if(runtime!=null){runtime.dispose();((CompletableFuture<?>)field(runtime,"termination")).get(8,TimeUnit.SECONDS);}
      }catch(Throwable error){if(report!=null)report.println("ERROR=cleanup: "+error);exit=1;}
      if(report!=null)report.close();
    }
    System.exit(exit);
  }
  private static <T>T edt(Callable<T> action)throws Exception {
    FutureTask<T> task=new FutureTask<>(action);
    if(SwingUtilities.isEventDispatchThread())WriteIntentReadAction.run((Runnable)task);
    else SwingUtilities.invokeLater(()->WriteIntentReadAction.run((Runnable)task));
    return task.get(30,TimeUnit.SECONDS);
  }
  private static Object field(Object owner,String name)throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
  private static void assign(Object owner,String name,Object value)throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
  private static void await(String name,Callable<Boolean> condition)throws Exception {
    report.println("WAIT="+name);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
    while(System.nanoTime()<deadline){if(edt(condition))return;Thread.sleep(50);}throw new AssertionError("Timed out: "+name);
  }
  private static void check(String name,boolean pass){report.println(name+"="+pass);if(!pass)throw new AssertionError(name);checks++;}
  private static class Fixture implements okhttp3.Interceptor {
    final Map<Long,List<JsonObject>> rows=new ConcurrentHashMap<>();
    final AtomicLong account=new AtomicLong(7);
    final AtomicInteger pages=new AtomicInteger(),writes=new AtomicInteger();
    final List<String> written=new CopyOnWriteArrayList<>();
    volatile String holdPath="";
    volatile CountDownLatch entered=new CountDownLatch(0),release=new CountDownLatch(0);
    Fixture(){for(long a:List.of(7L,8L)){List<JsonObject> list=new CopyOnWriteArrayList<>();for(int id=200;id>125;id--)list.add(item(id));rows.put(a,list);}}
    JsonObject item(int id){
      long topic=switch(id){case 198->990403;case 197->990404;case 195->990102;case 194->990103;case 191->990104;default->990101;};
      int floor=switch(id){case 199->1;case 196->777;case 192->3;default->2;};
      int type=switch(id){case 190->43;case 189->12;case 188->1500;case 187->6;default->2;};
      JsonObject n=JsonParser.parseString("{\"id\":"+id+",\"notification_type\":"+type+",\"read\":false,\"topic_id\":"+topic+",\"post_number\":"+floor+",\"data\":{\"topic_title\":\"notification-"+id+"\",\"display_username\":\"fixture\"}}").getAsJsonObject();
      if(id==189){n.remove("topic_id");n.remove("post_number");n.getAsJsonObject("data").addProperty("badge_id",42);n.getAsJsonObject("data").addProperty("badge_name","fixture badge");}
      return n;
    }
    DiscourseNotification notification(long id){return GSON.fromJson(row(id),DiscourseNotification.class);}
    JsonObject row(long id){return rows.get(account.get()).stream().filter(n->n.get("id").getAsLong()==id).findFirst().orElseThrow();}
    boolean read(long id){return row(id).get("read").getAsBoolean();}
    void hold(String path){holdPath=path;entered=new CountDownLatch(1);release=new CountDownLatch(1);}
    void unblock(){holdPath="";release.countDown();}
    String topic(long topic,int around){
      JsonArray posts=new JsonArray(),stream=new JsonArray();
      for(int i=1;i<=3;i++){
        long id=topic*10+i;stream.add(id);
        if(around==0 && i==3)continue;
        JsonObject post=new JsonObject();post.addProperty("id",id);post.addProperty("topic_id",topic);post.addProperty("post_number",i);post.addProperty("username","fixture");
        post.addProperty("cooked","<p>Account "+account.get()+" native notification body #"+i+"</p><p>"+"模拟正文 ".repeat(25)+"</p><pre><code>val floor = "+i+"</code></pre>");posts.add(post);
      }
      JsonObject result=new JsonObject(),ps=new JsonObject();result.addProperty("id",topic);result.addProperty("title","Notification acceptance "+topic);result.addProperty("highest_post_number",3);result.addProperty("posts_count",3);
      result.add("details",JsonParser.parseString("{\"can_create_post\":false}"));ps.add("posts",posts);ps.add("stream",stream);result.add("post_stream",ps);return result.toString();
    }
    public okhttp3.Response intercept(okhttp3.Interceptor.Chain chain)throws IOException {
      okhttp3.Request request=chain.request();String path=request.url().encodedPath(),body="{}";int code=200;
      long user=account.get();List<JsonObject> current=rows.get(user);
      if(path.equals("/site.json"))body="{\"categories\":[],\"notification_types\":{\"mentioned\":1,\"replied\":2,\"private_message\":6,\"granted_badge\":12,\"reaction\":25,\"assigned\":34,\"boost\":43}}";
      else if(path.equals("/notifications.json")){
        pages.incrementAndGet();String filter=request.url().queryParameter("filter");int offset=Integer.parseInt(request.url().queryParameter("offset")),limit=Integer.parseInt(request.url().queryParameter("limit"));
        List<JsonObject> matching=current.stream().filter(r->filter==null || r.get("read").getAsBoolean()==filter.equals("read")).toList();JsonArray page=new JsonArray();matching.stream().skip(offset).limit(limit).forEach(n->page.add(n.deepCopy()));
        JsonObject data=new JsonObject();data.add("notifications",page);data.addProperty("total_rows_notifications",matching.size());data.addProperty("load_more_notifications","/notifications?offset="+(offset+limit)+"&limit="+limit+(filter==null?"":"&filter="+filter));body=data.toString();
      }else if(path.equals("/notifications/totals.json"))body="{\"unread_notifications\":"+current.stream().filter(n->!n.get("read").getAsBoolean()).count()+",\"unread_personal_messages\":8}";
      else if(path.equals("/notifications/mark-read")){
        okio.Buffer buffer=new okio.Buffer();request.body().writeTo(buffer);String form=buffer.readUtf8();writes.incrementAndGet();written.add(user+":"+form);
        for(JsonObject n:current)if(form.isEmpty() || form.equals("id="+n.get("id").getAsLong()))n.addProperty("read",true);body="{\"success\":\"OK\"}";
      }else if(path.equals("/session/csrf") || path.equals("/session/csrf.json"))body="{\"csrf\":\"isolated-notification-csrf\"}";
      else if(path.equals("/session/current.json"))body="{\"current_user\":{\"id\":"+user+",\"username\":\"fixture_"+user+"\"}}";
      else if(path.matches("/t/\\d+(?:/\\d+)?\\.json")){
        String[] parts=path.replace(".json","").split("/");long id=Long.parseLong(parts[2]);int around=parts.length>3?Integer.parseInt(parts[3]):0;
        if(id==990403 || id==990404)code=id==990403?403:404;else body=topic(id,around);
      }else if(path.equals("/categories.json"))body="{\"category_list\":{\"categories\":[]}}";
      else if(path.equals("/tags.json"))body="{\"tags\":[]}";
      else if(path.endsWith("/latest.json") || path.equals("/latest.json"))body="{\"topic_list\":{\"topics\":[]},\"users\":[]}";
      else if(!request.method().equals("GET")){code=503;body="{\"errors\":[\"Isolated acceptance rejects unrelated write\"]}";}
      if(path.equals(holdPath)){
        entered.countDown();try{if(!release.await(45,TimeUnit.SECONDS))throw new IOException("Fixture hold expired");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
      }
      return new okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(code).message("isolated notification acceptance").body(okhttp3.ResponseBody.create(body,okhttp3.MediaType.parse("application/json"))).build();
    }
  }
  private static Project openProject(String name)throws Exception {
    Path path=output.resolve(name);Files.createDirectories(path);
    Project p=ProjectManagerEx.getInstanceEx().openProject(path,OpenProjectTask.build().asNewProject().withForceOpenInNewFrame(true).withProjectName(name));
    if(p==null)throw new AssertionError("Project did not open: "+name);projects.add(p);
    await("project frame "+name,()->WindowManager.getInstance().getFrame(p)!=null && WindowManager.getInstance().getFrame(p).isShowing());
    edt(()->{JFrame f=WindowManager.getInstance().getFrame(p);f.setSize(1080,780);f.setLocation(40+projects.size()*30,40+projects.size()*20);return null;});return p;
  }
  private static IssueListPanel listPanel(Project p)throws Exception {
    ToolWindow window=edt(()->ToolWindowManager.getInstance(p).getToolWindow("API Docs"));if(window==null)throw new AssertionError("API Docs tool window missing");
    edt(()->{window.show();return null;});await("API Docs content",()->window.getContentManager().getContentCount()>0);
    return edt(()->((LinuxDoDocMainPanel)window.getContentManager().getContent(0).getComponent()).getIssueListPanel());
  }
  private static void front(Project p)throws Exception {edt(()->{JFrame f=WindowManager.getInstance().getFrame(p);f.setAlwaysOnTop(true);f.toFront();f.requestFocus();return null;});await("active project frame",()->WindowManager.getInstance().getFrame(p).isActive());}
  private static void click(Component component)throws Exception {
    Point center=edt(()->{Point point=component.getLocationOnScreen();point.translate(component.getWidth()/2,component.getHeight()/2);return point;});
    Robot robot=new Robot();robot.setAutoDelay(80);robot.mouseMove(center.x,center.y);robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);robot.waitForIdle();
  }
  private static NotificationListPanel findPopup(Container root){
    if(root instanceof NotificationListPanel panel && panel.isShowing())return panel;
    for(Component child:root.getComponents())if(child instanceof Container c){NotificationListPanel panel=findPopup(c);if(panel!=null)return panel;}return null;
  }
  private static NotificationListPanel popup(Project p,IssueListPanel panel)throws Exception {
    edt(()->{for(Window w:Window.getWindows())if(w.isShowing()){NotificationListPanel found=findPopup(w);if(found!=null && found.getOnClose()!=null)found.getOnClose().invoke();}return null;});
    await("previous popup closed",()->{for(Window w:Window.getWindows())if(w.isShowing()&&findPopup(w)!=null)return false;return true;});
    front(p);click((Component)field(panel,"notificationButton"));
    AtomicReference<NotificationListPanel> result=new AtomicReference<>();await("notification popup",()->{for(Window w:Window.getWindows())if(w.isShowing()){NotificationListPanel found=findPopup(w);if(found!=null){result.set(found);return true;}}return false;});return result.get();
  }
  private static void selectNotification(NotificationListPanel panel,long id,boolean open)throws Exception {
    AtomicReference<Rectangle> bounds=new AtomicReference<>();await("notification row "+id,()->{JList<DiscourseNotification> list=panel.getList();for(int i=0;i<list.getModel().getSize();i++)if(list.getModel().getElementAt(i).getId()==id){list.setSelectedIndex(i);list.ensureIndexIsVisible(i);bounds.set(list.getCellBounds(i,i));return true;}return false;});
    if(open){Point point=edt(()->{Point pt=panel.getList().getLocationOnScreen();Rectangle r=bounds.get();pt.translate(r.x+80,r.y+r.height/2);return pt;});Robot robot=new Robot();robot.mouseMove(point.x,point.y);robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);}
  }
  private static LinuxDoTopicFileEditor editor(Project p,long topic)throws Exception {
    for(var file:FileEditorManager.getInstance(p).getOpenFiles())if(LinuxDoTopicVirtualFile.INSTANCE.accepts(file)&&LinuxDoTopicVirtualFile.INSTANCE.topicId(file)==topic)
      for(FileEditor e:FileEditorManager.getInstance(p).getEditors(file))if(e instanceof LinuxDoTopicFileEditor result)return result;return null;
  }
  private static JsonObject evaluate(LinuxDoTopicFileEditor editor,String expression)throws Exception {
    LinuxDoBrowser browser=(LinuxDoBrowser)field(editor.getComponent(),"jbCefBrowser");if(browser==null)throw new AssertionError("Browser missing");
    BlockingQueue<String> replies=new LinkedBlockingQueue<>();LinuxDoJSQuery query=(LinuxDoJSQuery)field(editor.getComponent(),"jsQuery");
    @SuppressWarnings("unchecked") kotlin.jvm.functions.Function1<String,LinuxDoJSQuery.Response> handler=(kotlin.jvm.functions.Function1<String,LinuxDoJSQuery.Response>)field(query,"handler");
    try{query.addHandler(text->{if(text.startsWith("probe:")){replies.add(text.substring(6));return null;}return handler.invoke(text);});browser.getCefBrowser().executeJavaScript("(()=>{"+query.inject("'probe:'+JSON.stringify("+expression+")")+"})()",browser.getCefBrowser().getURL(),0);String json=replies.poll(5,TimeUnit.SECONDS);if(json==null)throw new AssertionError("Reader probe timed out");return JsonParser.parseString(json).getAsJsonObject();}
    finally{query.addHandler(handler);}
  }
  private static Notification balloon(Project p,long id)throws Exception {
    LinuxDoNotificationService.Companion.getInstance().pushIdeNotification(fixture.notification(id),p);AtomicReference<Notification> result=new AtomicReference<>();
    await("IDE notification "+id,()->{for(Notification n:NotificationsManager.getNotificationsManager().getNotificationsOfType(Notification.class,p))if(n.getTitle().equals("Linux Do 新通知")&&n.getContent().contains("notification-"+id)&&!n.isExpired()){result.set(n);return true;}return false;});return result.get();
  }
  private static void balloonAction(Project p,Notification notification,int action)throws Exception {
    front(p);edt(()->{AnAction a=notification.getActions().get(action);Notification.fire(notification,a,key->CommonDataKeys.PROJECT.getName().equals(key)?p:Notification.KEY.getName().equals(key)?notification:null);return null;});
  }
  private static void shot(Project p,String name)throws Exception {
    front(p);Thread.sleep(300);Rectangle bounds=edt(()->WindowManager.getInstance().getFrame(p).getBounds());ImageIO.write(new Robot().createScreenCapture(bounds),"png",output.resolve(name+".png").toFile());report.println("SCREENSHOT="+name+".png");
  }
  private static void run()throws Exception {
    // Skip only first-launch UI tours in this isolated acceptance profile.
    Registry.get("ide.experimental.ui.onboarding").setValue(false);
    LinuxDoSettingsState settings=LinuxDoSettingsState.Companion.getInstance();settings.setNetworkMode("JAVA_ONLY");settings.setEnableNotificationPolling(false);settings.setAutoReportReadTimings(false);
    PersistentCookieJar cookies=new PersistentCookieJar(false);cookies.injectCookie("_t","synthetic-notification-account","linux.do");
    LinuxDoAuthService auth=LinuxDoAuthService.Companion.getInstance();assign(auth,"credentials",cookies);
    Field client=LinuxDoHttpClient.class.getDeclaredField("client");client.setAccessible(true);client.set(null,LinuxDoHttpClient.INSTANCE.getClient().newBuilder().cookieJar(cookies).addInterceptor(fixture).build());
    edt(()->{auth.setCurrentUserDirectly(GSON.fromJson("{\"id\":7,\"username\":\"fixture_7\"}",UserInfo.class));return null;});
    LinuxDoNotificationService service=LinuxDoNotificationService.Companion.getInstance();service.stopPolling();service.resetCircuitBreaker();
    Project first=openProject("NotificationProjectA"),second=openProject("NotificationProjectB");
    check("TWO_REAL_PROJECT_FRAMES",first.isOpen()&&second.isOpen()&&WindowManager.getInstance().getFrame(first)!=WindowManager.getInstance().getFrame(second));
    IssueListPanel firstList=listPanel(first),secondList=listPanel(second);await("account count in both windows",()->service.getUnreadCount()==83 && ((JButton)field(firstList,"notificationButton")).getText().equals("83") && ((JButton)field(secondList,"notificationButton")).getText().equals("83"));
    check("ACCOUNT_TOTAL_SYNCED_TO_BOTH_PROJECTS",true);
    fixture.hold("/t/990101.json");int before=fixture.writes.get();NotificationListPanel firstPopup=popup(first,firstList);selectNotification(firstPopup,200,true);
    check("POPUP_TOPIC_REQUEST_STARTED",fixture.entered.await(20,TimeUnit.SECONDS));
    await("FileEditorManager registers notification editor",()->editor(first,990101)!=null);
    check("POPUP_OPENS_THROUGH_FILE_EDITOR_MANAGER",true);
    check("POPUP_DOES_NOT_MARK_DURING_BODY_LOAD",fixture.writes.get()==before&&!fixture.read(200));fixture.unblock();await("popup display then mark-read",()->fixture.read(200));
    LinuxDoTopicFileEditor original=edt(()->editor(first,990101));
    check("POPUP_NEW_EDITOR_TARGET_IS_VISIBLE",evaluate(original,"({ok:document.querySelector('#floor-progress').value==='2' && document.querySelector('#floor-2 .post-content').getBoundingClientRect().top<innerHeight})").get("ok").getAsBoolean());
    await("read count synchronizes both frames",()->service.getUnreadCount()==82 && ((JButton)field(firstList,"notificationButton")).getText().equals("82") && ((JButton)field(secondList,"notificationButton")).getText().equals("82"));check("SUCCESSFUL_READ_SYNCED_TO_BOTH_PROJECTS",true);
    NotificationListPanel reused=popup(first,firstList);selectNotification(reused,199,true);await("reused editor read",()->fixture.read(199));
    check("POPUP_REUSES_EXISTING_FILE_EDITOR",edt(()->editor(first,990101)==original)&&evaluate(original,"({ok:document.querySelector('#floor-progress').value==='1'})").get("ok").getAsBoolean());
    // More scenarios are kept in one process so they use the same application service and real project frames.
    runFailures(first,firstList,service);
    runSharedRequests(first,firstList,second,secondList,service);
    runNavigationCancellation(first,original);
    runBossAndAccount(first,firstList,second,secondList,auth,service);
    runBrowserFailure(first,service);
    shot(first,"notification-project-a");shot(second,"notification-project-b");
    check("ONLY_MEMORY_HTTP_WRITES",fixture.written.stream().allMatch(s->s.startsWith("7:")||s.startsWith("8:")));
    report.println("WRITE_REQUESTS="+fixture.written);report.println("REAL_FORUM_WRITES=0");
  }
  private static void runFailures(Project p,IssueListPanel list,LinuxDoNotificationService service)throws Exception {
    NotificationListPanel types=popup(p,list);selectNotification(types,190,false);
    check("SITE_BOOST_43_USES_BOOST_LABEL_AND_TOPIC",types.getList().getSelectedValue().getTypeActionLabel().equals("发送了微回复")&&NotificationTypes.INSTANCE.topicTarget(types.getList().getSelectedValue())==990101L);
    selectNotification(types,189,false);
    check("BADGE_USES_BADGE_LABEL_AND_WEB_TARGET_WITHOUT_READ",types.getList().getSelectedValue().getTypeActionLabel().equals("授予你新徽章")&&NotificationTypes.INSTANCE.topicTarget(types.getList().getSelectedValue())==null&&NotificationTypes.INSTANCE.webTarget(types.getList().getSelectedValue(),"https://linux.do").equals("https://linux.do/badges/42")&&!fixture.read(189));
    selectNotification(types,188,false);check("UNKNOWN_TYPE_USES_GENERIC_LABEL_WITHOUT_AUTO_TOPIC",types.getList().getSelectedValue().getTypeActionLabel().equals("发来通知")&&NotificationTypes.INSTANCE.topicTarget(types.getList().getSelectedValue())==null);
    selectNotification(types,187,false);check("PRIVATE_MESSAGE_USES_MESSAGE_LABEL_AND_TOPIC",types.getList().getSelectedValue().getTypeActionLabel().equals("发来私信")&&NotificationTypes.INSTANCE.topicTarget(types.getList().getSelectedValue())==990101L);
    selectNotification(types,190,true);await("boost notification confirms display",()->fixture.read(190));check("BOOST_NOTIFICATION_OPENS_ACTUAL_READER_THEN_MARKS",true);
    NotificationListPanel messages=popup(p,list);selectNotification(messages,187,true);await("private message notification confirms display",()->fixture.read(187));check("PRIVATE_MESSAGE_NOTIFICATION_OPENS_ACTUAL_READER_THEN_MARKS",true);
    for(long id:new long[]{198,197,196}){
      assign(service,"lastReadError",null);int before=fixture.writes.get();NotificationListPanel panel=popup(p,list);selectNotification(panel,id,true);await("failed target "+id,()->service.getLastReadError()!=null);
      check("TARGET_"+id+"_FAILURE_PRESERVES_UNREAD",fixture.writes.get()==before&&!fixture.read(id));
    }
    NotificationListPanel panel=popup(p,list);selectNotification(panel,188,false);int before=fixture.writes.get();click((Component)field(panel,"manualReadBtn"));await("unknown notification manual read",()->fixture.read(188));check("UNKNOWN_TYPE_HAS_MANUAL_READ_WITHOUT_AUTO_NAVIGATION",fixture.writes.get()==before+1);
  }
  private static void runSharedRequests(Project a,IssueListPanel al,Project b,IssueListPanel bl,LinuxDoNotificationService service)throws Exception {
    NotificationListPanel first=popup(a,al);await("head request settled",()->!(Boolean)field(first,"loading"));fixture.hold("/notifications.json");int before=fixture.pages.get();click((Component)field(first,"refreshBtn"));check("MANUAL_REFRESH_PENDING",fixture.entered.await(10,TimeUnit.SECONDS));
    NotificationListPanel second=popup(b,bl);Thread.sleep(200);check("TWO_PROJECT_REFRESHES_SHARE_ONE_REQUEST",fixture.pages.get()==before+1);fixture.unblock();await("second refresh completes",()->!(Boolean)field(second,"loading"));
  }
  private static void runNavigationCancellation(Project p,LinuxDoTopicFileEditor original)throws Exception {
    Notification n=balloon(p,191);fixture.hold("/t/990104.json");int before=fixture.writes.get();balloonAction(p,n,0);
    check("CANCELLED_TAB_REQUEST_STARTED",fixture.entered.await(15,TimeUnit.SECONDS));
    await("pending editor registered",()->editor(p,990104)!=null&&field(editor(p,990104).getComponent(),"openRequest")!=null);
    LinuxDoTopicFileEditor pending=edt(()->editor(p,990104));
    edt(()->{FileEditorManager.getInstance(p).openFile(original.getFile(),true);return null;});
    await("another tab selected",()->FileEditorManager.getInstance(p).getSelectedEditor()==original);
    check("TAB_DESELECT_CANCELS_PENDING_CONFIRMATION",edt(()->field(pending.getComponent(),"openRequest")==null));
    fixture.unblock();await("cancelled tab body finishes",()->field(pending.getComponent(),"currentTopic")!=null);
    edt(()->{FileEditorManager.getInstance(p).openFile(pending.getFile(),true);return null;});
    await("cancelled tab visible again",()->pending.getComponent().isShowing());Thread.sleep(400);
    check("TAB_RESELECTION_CANNOT_MARK_CANCELLED_NOTIFICATION",fixture.writes.get()==before&&!fixture.read(191)&&!n.isExpired());
    balloonAction(p,n,0);await("new action after tab cancellation",()->fixture.read(191)&&n.isExpired());check("TAB_CANCELLATION_CAN_RETRY_WITH_NEW_REQUEST",true);
  }
  private static JButton retryBrowserButton(Container root) {
    for(Component child:root.getComponents()){
      if(child instanceof JButton b&&b.getText().equals("重试正文浏览器"))return b;
      if(child instanceof Container c){JButton result=retryBrowserButton(c);if(result!=null)return result;}
    }return null;
  }
  private static void runBrowserFailure(Project p,LinuxDoNotificationService service)throws Exception {
    Notification n=balloon(p,192);fixture.hold("/t/990101/3.json");int before=fixture.writes.get();balloonAction(p,n,0);
    check("NATIVE_BROWSER_FAILURE_TARGET_PENDING",fixture.entered.await(15,TimeUnit.SECONDS));
    LinuxDoTopicFileEditor editor=edt(()->editor(p,990101));LinuxDoBrowser browser=(LinuxDoBrowser)edt(()->field(editor.getComponent(),"jbCefBrowser"));
    Object runtime=browser.getRuntime(),app=field(runtime,"app"),server=browser.getRuntime().call(app,"getServer",new Object[0]);
    Method disconnect=server.getClass().getDeclaredMethod("onCefHandlersThreadFinished");disconnect.setAccessible(true);disconnect.invoke(server);
    await("native browser failure offers retry",()->retryBrowserButton(editor.getComponent())!=null);fixture.unblock();
    check("NATIVE_BROWSER_FAILURE_PRESERVES_UNREAD",fixture.writes.get()==before&&!fixture.read(192)&&!n.isExpired());
    click(edt(()->retryBrowserButton(editor.getComponent())));
    await("native browser recovery",()->field(editor.getComponent(),"jbCefBrowser")!=null&&field(editor.getComponent(),"jbCefBrowser")!=browser&&((LinuxDoBrowser)field(editor.getComponent(),"jbCefBrowser")).getRuntime().isUsable());
    check("BROWSER_RECOVERY_ALONE_DOES_NOT_MARK",fixture.writes.get()==before&&!fixture.read(192));
    balloonAction(p,n,0);await("retry notification after native browser recovery",()->fixture.read(192)&&n.isExpired());check("NATIVE_BROWSER_RECOVERY_RETRY_CONFIRMS_TARGET",true);
  }
  private static void runBossAndAccount(Project a,IssueListPanel al,Project b,IssueListPanel bl,LinuxDoAuthService auth,LinuxDoNotificationService service)throws Exception {
    Notification n=balloon(a,195);fixture.hold("/t/990102.json");int before=fixture.writes.get();balloonAction(a,n,0);check("BALLOON_USES_PENDING_READER",fixture.entered.await(15,TimeUnit.SECONDS));
    edt(()->{LinuxDoBossKeyService.Companion.getInstance(a).toggle();return null;});fixture.unblock();await("boss key closes forum tabs",()->FileEditorManager.getInstance(a).getOpenFiles().length==0);
    check("BOSS_KEY_CANCELLATION_PRESERVES_UNREAD",!fixture.read(195)&&fixture.writes.get()==before&&!n.isExpired());
    balloonAction(a,n,0);Thread.sleep(300);check("BALLOON_WHILE_HIDDEN_CANNOT_MARK",!fixture.read(195)&&fixture.writes.get()==before);
    edt(()->{LinuxDoBossKeyService.Companion.getInstance(a).toggle();return null;});await("boss key restores file",()->editor(a,990102)!=null);
    check("BOSS_RESTORE_ALONE_DOES_NOT_MARK",!fixture.read(195)&&fixture.writes.get()==before);balloonAction(a,n,0);await("balloon retry after restore marks",()->fixture.read(195)&&n.isExpired());check("BALLOON_RETRY_AFTER_BOSS_RESTORE_CONFIRMS_DISPLAY",true);
    Notification old=balloon(a,193);Notification pending=balloon(a,194);fixture.hold("/t/990103.json");before=fixture.writes.get();balloonAction(a,pending,0);check("OLD_ACCOUNT_TOPIC_PENDING",fixture.entered.await(15,TimeUnit.SECONDS));
    fixture.account.set(8);edt(()->{auth.setCurrentUserDirectly(GSON.fromJson("{\"id\":8,\"username\":\"fixture_8\"}",UserInfo.class));return null;});fixture.unblock();await("new account count",()->service.getUnreadCount()==83);
    balloonAction(a,old,0);balloonAction(a,old,1);Thread.sleep(300);
    check("OLD_ACCOUNT_BALLOON_ACTIONS_ARE_INERT",fixture.writes.get()==before&&!fixture.read(193)&&old.isExpired());
    check("ACCOUNT_SWITCH_PENDING_OPEN_CANNOT_MARK_NEW_ACCOUNT",!fixture.read(194)&&!fixture.rows.get(7L).stream().filter(r->r.get("id").getAsLong()==194).findFirst().orElseThrow().get("read").getAsBoolean());
    await("both windows show new account counts",()->((JButton)field(al,"notificationButton")).getText().equals("83")&&((JButton)field(bl,"notificationButton")).getText().equals("83"));check("ACCOUNT_SWITCH_SYNCHRONIZES_BOTH_PROJECTS",true);
  }
}
