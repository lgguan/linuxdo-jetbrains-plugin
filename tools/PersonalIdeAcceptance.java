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
public final class PersonalIdeAcceptance implements ApplicationStarter {
  private static final Gson GSON=new Gson();
  private static PrintWriter report;
  private static Path output;
  private static int checks;
  private static final List<Project> projects=new ArrayList<>();
  private static final Fixture fixture=new Fixture();
  public String getCommandName(){return "linuxdo-personal-acceptance";}
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
  private static void shot(Project p,String name)throws Exception {
    front(p);edt(()->{for(Notification n:NotificationsManager.getNotificationsManager().getNotificationsOfType(Notification.class,p))n.expire();for(Notification n:NotificationsManager.getNotificationsManager().getNotificationsOfType(Notification.class,null))n.expire();return null;});Thread.sleep(1000);Rectangle bounds=edt(()->WindowManager.getInstance().getFrame(p).getBounds());ImageIO.write(new Robot().createScreenCapture(bounds),"png",output.resolve(name+".png").toFile());report.println("SCREENSHOT="+name+".png");
  }
  private static class Fixture implements okhttp3.Interceptor {
    final AtomicInteger pages=new AtomicInteger(), writes=new AtomicInteger();
    final List<String> draftReads=new CopyOnWriteArrayList<>();
    final Map<String,JsonObject> drafts=new ConcurrentHashMap<>();
    volatile String holdPath="";volatile int failure,topicFailure;volatile boolean shifted;
    volatile CountDownLatch entered=new CountDownLatch(0),release=new CountDownLatch(0);
    Fixture(){
      drafts.put("new_topic",json("{\"action\":\"createTopic\",\"title\":\"Default draft\",\"reply\":\"Existing draft body\",\"archetypeId\":\"regular\"}"));
      drafts.put("new_topic_suffix",json("{\"action\":\"createTopic\",\"title\":\"Suffix draft\",\"reply\":\"Suffix draft body\",\"archetypeId\":\"regular\"}"));
      drafts.put("topic_990101",json("{\"action\":\"reply\",\"reply\":\"Reply draft body\",\"reply_to_post_number\":2,\"reply_to_user\":{\"username\":\"fixture\"},\"postId\":9901012}"));
      drafts.put("topic_990102",json("{\"action\":\"reply\",\"archetypeId\":\"private_message\"}"));
      drafts.put("topic_990103",json("{\"action\":\"edit\",\"reply\":\"edit draft\"}"));
    }
    static JsonObject json(String value){return JsonParser.parseString(value).getAsJsonObject();}
    void hold(String path){holdPath=path;entered=new CountDownLatch(1);release=new CountDownLatch(1);}
    void unblock(){holdPath="";release.countDown();}
    public okhttp3.Response intercept(okhttp3.Interceptor.Chain chain)throws IOException {
      var request=chain.request();String path=request.url().encodedPath();String body="{}";int code=200;
      if(!request.method().equals("GET")){writes.incrementAndGet();code=503;}
      else if(path.equals("/user_actions.json")){
        pages.incrementAndGet();int offset=Integer.parseInt(request.url().queryParameter("offset"));JsonArray rows=new JsonArray();
        for(int i=offset;i<Math.min(offset+30,65);i++){int n=i+(shifted?100:0);JsonObject r=json("{\"topic_id\":990101,\"post_number\":2,\"title\":\"Personal row "+n+"\",\"excerpt\":\"<b>Plain summary</b><script>danger()</script>\",\"created_at\":\"2026-10-03\"}");if(request.url().queryParameter("filter").equals("4"))r.addProperty("topic_id",990101+n);else {r.addProperty("post_id",9901010+n);r.addProperty("post_number",n+1);}rows.add(r);}
        if(request.url().queryParameter("filter").equals("5")&&offset==0)rows.get(0).getAsJsonObject().remove("post_number");
        JsonObject result=new JsonObject();result.add("user_actions",rows);body=result.toString();if(failure>0){code=failure;failure=0;}
      }else if(path.endsWith("/bookmarks.json")){
        pages.incrementAndGet();int page=Integer.parseInt(request.url().queryParameter("page"));JsonArray rows=new JsonArray();
        for(int i=page*30;i<Math.min(page*30+30,35);i++)rows.add(json("{\"id\":"+(i+1)+",\"topic_id\":990101,\"bookmarkable_type\":\"Post\",\"linked_post_number\":2,\"title\":\"Bookmark "+i+"\",\"name\":\"Reminder\"}"));
        JsonObject result=new JsonObject();result.add("bookmarks",rows);if(page==0)result.addProperty("more_bookmarks_url",path.replace(".json","")+"?page=1");body="{\"user_bookmark_list\":"+result+"}";
      }else if(path.equals("/drafts.json")){
        pages.incrementAndGet();JsonArray rows=new JsonArray();for(var e:drafts.entrySet()){JsonObject row=new JsonObject();row.addProperty("draft_key",e.getKey());row.addProperty("sequence",1);row.add("data",e.getValue());rows.add(row);}
        rows.add(json("{\"draft_key\":\"unknown\",\"data\":\"{\"}"));body="{\"drafts\":"+rows+"}";
      }else if(path.startsWith("/drafts/")){
        String key=path.substring(8,path.length()-5);draftReads.add(key);JsonObject response=new JsonObject();response.addProperty("draft_sequence",1);response.add("draft",drafts.get(key));body=response.toString();
      }else if(path.matches("/t/[0-9]+(?:/[0-9]+)?\\.json")){
        long topic=Long.parseLong(path.split("/")[2].replace(".json",""));JsonArray posts=new JsonArray(),stream=new JsonArray();
        for(int i=1;i<=3;i++){long id=topic*10+i;stream.add(id);posts.add(json("{\"id\":"+id+",\"topic_id\":"+topic+",\"post_number\":"+i+",\"username\":\"fixture\",\"cooked\":\"<p>Personal content reader floor "+i+"</p>\"}"));}
        body="{\"id\":"+topic+",\"archetype\":\"regular\",\"title\":\"Personal reader\",\"posts_count\":3,\"highest_post_number\":3,\"details\":{\"can_create_post\":true},\"post_stream\":{\"posts\":"+posts+",\"stream\":"+stream+"}}";
        if(topicFailure>0){code=topicFailure;topicFailure=0;}
      }else if(path.equals("/posts/9901010.json"))body="{\"id\":9901010,\"topic_id\":990101,\"post_number\":2,\"username\":\"fixture\"}";
      else if(path.equals("/site.json"))body="{\"categories\":[],\"notification_types\":{}}";
      else if(path.equals("/categories.json"))body="{\"category_list\":{\"categories\":[]}}";
      else if(path.equals("/latest.json"))body="{\"topic_list\":{\"topics\":[{\"id\":990101,\"title\":\"Forum retained row\"}]},\"users\":[]}";
      else if(path.equals("/latest"))body="<script id='data-preloaded'>{\"siteSettings\":{\"min_topic_title_length\":5,\"min_post_length\":16,\"max_post_length\":10000,\"min_personal_message_post_length\":3},\"currentUser\":{\"id\":7,\"username\":\"fixture\"}}</script>";
      else if(path.equals("/notifications.json"))body="{\"notifications\":[]}";
      else if(path.equals("/notifications/totals.json"))body="{\"unread_notifications\":0,\"unread_personal_messages\":0}";
      else if(path.equals("/session/current.json"))body="{\"current_user\":{\"id\":7,\"username\":\"fixture\"}}";
      if(path.equals(holdPath)){entered.countDown();try{if(!release.await(45,TimeUnit.SECONDS))throw new IOException("Fixture hold expired");}catch(InterruptedException e){throw new IOException(e);}}
      return new okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(code).message("memory personal acceptance").body(okhttp3.ResponseBody.create(body,okhttp3.MediaType.parse("application/json"))).build();
    }
  }
  @SuppressWarnings("unchecked") private static JList<PersonalContentItem> rows(PersonalContentPanel panel)throws Exception{return (JList<PersonalContentItem>)field(panel,"list");}
  private static void settled(PersonalContentKind kind)throws Exception {await("personal "+kind+" settled",()->!PersonalContentService.Companion.getInstance().state(kind).getLoading());}
  private static void openDraftRow(PersonalContentPanel panel,String key)throws Exception {
    edt(()->{var list=rows(panel);for(int i=0;i<list.getModel().getSize();i++)if(key.equals(list.getModel().getElementAt(i).getDraftKey())){list.setSelectedIndex(i);list.getActionMap().get("open-personal").actionPerformed(null);return null;}throw new AssertionError("Draft missing "+key);});
  }
  private static List<Object> editors(Class<?> type)throws Exception {Field f=type.getDeclaredField("editors");f.setAccessible(true);return new ArrayList<>(((Map<?,?>)f.get(null)).values());}
  private static Object draftEditor(Class<?> type,String key)throws Exception {
    for(Object d:editors(type)){Object session=field(d,"draftSession");if(session.getClass().getMethod("getKey").invoke(session).equals(key))return d;}return null;
  }
  private static void run()throws Exception {
    Registry.get("ide.experimental.ui.onboarding").setValue(false);
    var settings=LinuxDoSettingsState.Companion.getInstance();settings.setNetworkMode("JAVA_ONLY");settings.setEnableNotificationPolling(false);settings.setAutoReportReadTimings(false);
    var cookies=new PersistentCookieJar(false);cookies.injectCookie("_t","synthetic-personal-account","linux.do");
    var auth=LinuxDoAuthService.Companion.getInstance();assign(auth,"credentials",cookies);
    Field client=LinuxDoHttpClient.class.getDeclaredField("client");client.setAccessible(true);client.set(null,LinuxDoHttpClient.INSTANCE.getClient().newBuilder().cookieJar(cookies).addInterceptor(fixture).build());
    edt(()->{auth.setCurrentUserDirectly(GSON.fromJson("{\"id\":7,\"username\":\"fixture\"}",UserInfo.class));return null;});
    LinuxDoNotificationService.Companion.getInstance().stopPolling();
    var first=openProject("PersonalProjectA");var second=openProject("PersonalProjectB");
    var a=listPanel(first);var b=listPanel(second);var pa=a.getPersonalContentPanel();var pb=b.getPersonalContentPanel();var service=PersonalContentService.Companion.getInstance();
    check("TWO_REAL_PROJECT_FRAMES",first.isOpen()&&second.isOpen()&&WindowManager.getInstance().getFrame(first)!=WindowManager.getInstance().getFrame(second));
    await("forum row",()->((JList<?>)field(a,"topicList")).getModel().getSize()>0);
    var forumButton=edt(()->(JToggleButton)field(a,"forumViewButton"));
    var personalButton=edt(()->(JToggleButton)field(a,"personalViewButton"));
    check("MODULE_ICONS_HAVE_ACCESSIBLE_NAMES",edt(()->forumButton.getIcon()!=null&&personalButton.getIcon()!=null
      &&(forumButton.getText()==null||forumButton.getText().isEmpty())&&(personalButton.getText()==null||personalButton.getText().isEmpty())
      &&forumButton.getAccessibleContext().getAccessibleName().equals("论坛")&&personalButton.getAccessibleContext().getAccessibleName().equals("我的")));
    check("FORUM_SELECTED_BY_DEFAULT",edt(()->forumButton.isSelected()&&!personalButton.isSelected()&&!a.getPersonalView()));
    shot(first,"module-forum");
    edt(()->{((JList<?>)field(a,"topicList")).setSelectedIndex(0);((JTextField)field(a,"searchField")).setText("retain query");return null;});
    fixture.hold("/user_actions.json");int before=fixture.pages.get();front(first);click(personalButton);edt(()->{b.selectPersonalView(true);return null;});
    check("PERSONAL_CLICK_SYNCS_SELECTION_AND_CONTENT",edt(()->!forumButton.isSelected()&&personalButton.isSelected()
      &&a.getPersonalView()&&pa.isVisible()&&!((JPanel)field(a,"forumControls")).isVisible()));
    check("PERSONAL_READ_STARTED",fixture.entered.await(10,TimeUnit.SECONDS));Thread.sleep(200);check("TWO_WINDOWS_ONE_PAGE_REQUEST",fixture.pages.get()==before+1);
    fixture.unblock();settled(PersonalContentKind.TOPICS);check("TOPICS_FIRST_PAGE",service.state(PersonalContentKind.TOPICS).getItems().size()==30);
    shot(first,"module-personal");
    edt(()->{rows(pa).setSelectedIndex(12);rows(pa).ensureIndexIsVisible(12);return null;});
    edt(()->{service.loadMore(PersonalContentKind.TOPICS);return null;});settled(PersonalContentKind.TOPICS);check("TOPICS_SECOND_PAGE",service.state(PersonalContentKind.TOPICS).getItems().size()==60);
    fixture.failure=403;edt(()->{service.loadMore(PersonalContentKind.TOPICS);return null;});settled(PersonalContentKind.TOPICS);
    check("FAILED_PAGE_RETAINS_ROWS",service.state(PersonalContentKind.TOPICS).getItems().size()==60&&service.state(PersonalContentKind.TOPICS).getFailedQuery().getOffset()==60);
    edt(()->{service.loadMore(PersonalContentKind.TOPICS);return null;});settled(PersonalContentKind.TOPICS);check("FAILED_PAGE_RETRY",service.state(PersonalContentKind.TOPICS).getItems().size()==65);
    edt(()->{rows(pa).setSelectedIndex(12);rows(pa).ensureIndexIsVisible(12);return null;});
    int y=edt(()->((JScrollPane)field(pa,"scroll")).getViewport().getViewPosition().y);
    edt(()->{service.refresh(PersonalContentKind.TOPICS);return null;});settled(PersonalContentKind.TOPICS);
    check("REFRESH_SELECTION_AND_SCROLL",edt(()->rows(pa).getSelectedIndex()==12&&((JScrollPane)field(pa,"scroll")).getViewport().getViewPosition().y==y));
    edt(()->{a.selectPersonalView(false);return null;});check("FORUM_FILTER_AND_SELECTION_RETAINED",edt(()->((JTextField)field(a,"searchField")).getText().equals("retain query")&&((JList<?>)field(a,"topicList")).getSelectedIndex()==0));
    check("PROGRAMMATIC_MODULE_SWITCH_SYNCS_HIGHLIGHT",edt(()->forumButton.isSelected()&&!personalButton.isSelected()
      &&forumButton.getToolTipText().contains("当前模块")&&((JPanel)field(a,"forumControls")).isVisible()));
    edt(()->{a.selectPersonalView(true);pa.selectKind(PersonalContentKind.REPLIES);return null;});settled(PersonalContentKind.REPLIES);check("SAME_TOPIC_MULTIPLE_REPLIES",service.state(PersonalContentKind.REPLIES).getItems().size()==30);
    edt(()->{rows(pa).setSelectedIndex(0);rows(pa).getActionMap().get("open-personal").actionPerformed(null);return null;});
    await("reply missing floor resolved",()->editor(first,990101)!=null);
    check("MISSING_REPLY_FLOOR_RESOLVES_POST_METADATA",edt(()->Arrays.stream(FileEditorManager.getInstance(first).getOpenFiles()).anyMatch(file->LinuxDoTopicVirtualFile.INSTANCE.accepts(file)&&LinuxDoTopicVirtualFile.INSTANCE.topicId(file)==990101&&Integer.valueOf(2).equals(LinuxDoTopicVirtualFile.INSTANCE.targetPostNumber(file)))));
    int requests=fixture.pages.get();edt(()->{((JTextField)field(pa,"filter")).setText("no match");return null;});
    check("LOCAL_FILTER_WITH_EXPLICIT_RANGE",fixture.pages.get()==requests&&edt(()->rows(pa).getModel().getSize()==0&&((JTextArea)field(pa,"status")).getText().contains("可继续加载")));
    edt(()->{pa.selectKind(PersonalContentKind.BOOKMARKS);return null;});settled(PersonalContentKind.BOOKMARKS);
    front(first);Point rowPoint=edt(()->{var list=rows(pa);Rectangle r=list.getCellBounds(0,0);Point point=list.getLocationOnScreen();point.translate(80,r.height/2);return point;});
    Robot mouse=new Robot();mouse.mouseMove(rowPoint.x,rowPoint.y);mouse.mousePress(InputEvent.BUTTON1_DOWN_MASK);mouse.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    await("production bookmark editor",()->editor(first,990101)!=null);
    var reader=edt(()->editor(first,990101));await("native body",()->field(reader.getComponent(),"currentTopic")!=null&&field(reader.getComponent(),"jbCefBrowser")!=null);
    boolean visible=false;for(int i=0;i<25&&!visible;i++){try{visible=evaluate(reader,"({ok:!!document.querySelector('#floor-2 .post-content')&&document.querySelector('#floor-progress').value==='2'})").get("ok").getAsBoolean();}catch(Exception ignored){}if(!visible)Thread.sleep(200);}
    check("LINKED_POST_NUMBER_OPENS_NATIVE_FLOOR",visible);
    edt(()->{((JTextField)field(pa,"filter")).setText("Bookmark 0");return null;});
    int editorCount=edt(()->FileEditorManager.getInstance(first).getOpenFiles().length);
    edt(()->{var list=rows(pa);Rectangle bounds=list.getCellBounds(0,0);list.dispatchEvent(new java.awt.event.MouseEvent(list,java.awt.event.MouseEvent.MOUSE_CLICKED,System.currentTimeMillis(),0,20,bounds.height+30,1,false,java.awt.event.MouseEvent.BUTTON1));return null;});
    check("BLANK_LIST_AREA_DOES_NOT_OPEN_LAST_ROW",edt(()->FileEditorManager.getInstance(first).getOpenFiles().length==editorCount));
    edt(()->{((JTextField)field(pa,"filter")).setText("");return null;});
    edt(()->{service.loadMore(PersonalContentKind.BOOKMARKS);return null;});settled(PersonalContentKind.BOOKMARKS);check("BOOKMARK_SERVER_CONTINUATION",service.state(PersonalContentKind.BOOKMARKS).getItems().size()==35);
    fixture.hold("/user_actions.json");edt(()->{pa.selectKind(PersonalContentKind.REPLIES);service.refresh(PersonalContentKind.REPLIES);return null;});check("TAB_REFRESH_PENDING",fixture.entered.await(10,TimeUnit.SECONDS));
    edt(()->{pa.selectKind(PersonalContentKind.BOOKMARKS);return null;});fixture.unblock();settled(PersonalContentKind.REPLIES);
    check("LATE_TAB_RESULT_CANNOT_REPLACE_ACTIVE_LIST",edt(()->pa.getKind()==PersonalContentKind.BOOKMARKS&&rows(pa).getModel().getElementAt(0).getKey().startsWith("bookmark:")));
    edt(()->{pa.selectKind(PersonalContentKind.DRAFTS);return null;});settled(PersonalContentKind.DRAFTS);
    check("CORRUPT_DRAFT_KEPT_WITH_WARNING",service.state(PersonalContentKind.DRAFTS).getItems().size()==6&&service.state(PersonalContentKind.DRAFTS).getWarning()!=null);
    shot(first,"personal-project-a");shot(second,"personal-project-b");
    openDraftRow(pa,"new_topic_suffix");await("suffix editor loaded",()->draftEditor(com.lgguan.linuxdo.plugin.ui.dialog.CreateTopicDialog.class,"new_topic_suffix")!=null&&(Boolean)field(draftEditor(com.lgguan.linuxdo.plugin.ui.dialog.CreateTopicDialog.class,"new_topic_suffix"),"draftReady"));
    Object suffix=edt(()->draftEditor(com.lgguan.linuxdo.plugin.ui.dialog.CreateTopicDialog.class,"new_topic_suffix"));
    openDraftRow(pa,"new_topic");await("independent default editor",()->(Boolean)field(draftEditor(com.lgguan.linuxdo.plugin.ui.dialog.CreateTopicDialog.class,"new_topic"),"draftReady"));
    check("DIFFERENT_TOPIC_DRAFT_KEYS_HAVE_INDEPENDENT_EDITORS",editors(com.lgguan.linuxdo.plugin.ui.dialog.CreateTopicDialog.class).size()==2);
    edt(()->{((JTextArea)field(suffix,"textArea")).setText("Unsaved local input preserved across projects");return null;});
    edt(()->{com.lgguan.linuxdo.plugin.ui.dialog.CreateTopicDialog.Companion.openDraft(second,"new_topic_suffix");return null;});
    check("SAME_KEY_FOCUSES_WITHOUT_REPLACING_INPUT",editors(com.lgguan.linuxdo.plugin.ui.dialog.CreateTopicDialog.class).size()==2&&edt(()->((JTextArea)field(suffix,"textArea")).getText().startsWith("Unsaved")));
    // Cancel only the test editor's pending autosave to keep this run strictly read-only.
    edt(()->{((com.intellij.util.Alarm)field(suffix,"draftAlarm")).cancelAllRequests();((com.intellij.openapi.ui.DialogWrapper)suffix).close(1);return null;});
    openDraftRow(pa,"new_topic");openDraftRow(pa,"topic_990101");
    await("default draft loaded",()->(Boolean)field(draftEditor(com.lgguan.linuxdo.plugin.ui.dialog.CreateTopicDialog.class,"new_topic"),"draftReady"));
    await("reply draft loaded",()->(Boolean)field(draftEditor(com.lgguan.linuxdo.plugin.ui.dialog.CommitReplyDialog.class,"topic_990101"),"draftReady"));
    Object reply=edt(()->draftEditor(com.lgguan.linuxdo.plugin.ui.dialog.CommitReplyDialog.class,"topic_990101"));
    check("REPLY_TARGET_RESTORED",field(reply,"target").getClass().getMethod("getFloor").invoke(field(reply,"target")).equals(2));
    check("ACTUAL_DRAFT_KEYS_READ",fixture.draftReads.containsAll(List.of("new_topic","new_topic_suffix","topic_990101")));
    Thread.sleep(2300);check("RESTORE_HAS_NO_WRITE",fixture.writes.get()==0);
    edt(()->{for(Object d:editors(com.lgguan.linuxdo.plugin.ui.dialog.CreateTopicDialog.class))((com.intellij.openapi.ui.DialogWrapper)d).close(1);for(Object d:editors(com.lgguan.linuxdo.plugin.ui.dialog.CommitReplyDialog.class))((com.intellij.openapi.ui.DialogWrapper)d).close(1);return null;});
    fixture.drafts.remove("new_topic");openDraftRow(pa,"new_topic");
    await("disappeared draft blocked",()->(Boolean)field(draftEditor(com.lgguan.linuxdo.plugin.ui.dialog.CreateTopicDialog.class,"new_topic"),"draftBlocked"));
    check("DISAPPEARED_DRAFT_CANNOT_BECOME_EMPTY_AUTOSAVE",!(Boolean)field(draftEditor(com.lgguan.linuxdo.plugin.ui.dialog.CreateTopicDialog.class,"new_topic"),"draftReady"));
    edt(()->{((com.intellij.openapi.ui.DialogWrapper)draftEditor(com.lgguan.linuxdo.plugin.ui.dialog.CreateTopicDialog.class,"new_topic")).close(1);return null;});
    fixture.drafts.put("new_topic_suffix",Fixture.json("{\"action\":\"edit\",\"reply\":\"changed type\"}"));openDraftRow(pa,"new_topic_suffix");
    await("changed draft blocked",()->(Boolean)field(draftEditor(com.lgguan.linuxdo.plugin.ui.dialog.CreateTopicDialog.class,"new_topic_suffix"),"draftBlocked"));
    check("CHANGED_DRAFT_TYPE_CANNOT_AUTOSAVE",!(Boolean)field(draftEditor(com.lgguan.linuxdo.plugin.ui.dialog.CreateTopicDialog.class,"new_topic_suffix"),"draftReady"));
    edt(()->{((com.intellij.openapi.ui.DialogWrapper)draftEditor(com.lgguan.linuxdo.plugin.ui.dialog.CreateTopicDialog.class,"new_topic_suffix")).close(1);return null;});
    for(int status:new int[]{403,404}){
      fixture.topicFailure=status;openDraftRow(pa,"topic_990101");
      await("reply target error "+status,()->draftEditor(com.lgguan.linuxdo.plugin.ui.dialog.CommitReplyDialog.class,"topic_990101")!=null&&!((Boolean)field(draftEditor(com.lgguan.linuxdo.plugin.ui.dialog.CommitReplyDialog.class,"topic_990101"),"draftBusy")));
      Object denied=edt(()->draftEditor(com.lgguan.linuxdo.plugin.ui.dialog.CommitReplyDialog.class,"topic_990101"));
      check("REPLY_TARGET_"+status+"_PREVENTS_AUTOSAVE",!(Boolean)field(denied,"draftReady"));
      edt(()->{((com.intellij.openapi.ui.DialogWrapper)denied).close(1);return null;});
    }
    int prior=fixture.pages.get();edt(()->{LinuxDoBossKeyService.Companion.getInstance(first).toggle();pa.selectKind(PersonalContentKind.TOPICS);pa.refreshCurrent();return null;});Thread.sleep(250);
    check("BOSS_HIDDEN_STARTS_NO_PERSONAL_REQUEST",fixture.pages.get()==prior);
    edt(()->{LinuxDoBossKeyService.Companion.getInstance(first).toggle();return null;});
    await("boss restore controls usable",()->((JButton)field(pa,"refresh")).isEnabled());
    check("BOSS_RESTORE_REENABLES_CONTROLS_WITHOUT_READ",fixture.pages.get()==prior);
    fixture.hold("/user_actions.json");edt(()->{pa.refreshCurrent();return null;});check("OLD_ACCOUNT_REQUEST_PENDING",fixture.entered.await(10,TimeUnit.SECONDS));
    edt(()->{auth.credentialsPending();return null;});check("PENDING_ACCOUNT_CLEARS_BOTH_WINDOWS",edt(()->rows(pa).getModel().getSize()==0&&rows(pb).getModel().getSize()==0));
    fixture.unblock();Thread.sleep(300);check("OLD_CALLBACK_CANNOT_REFILL",service.state(PersonalContentKind.TOPICS).getItems().isEmpty());
    int signedOutPages=fixture.pages.get();cookies.clearAll();edt(()->{auth.credentialsPending();pa.refreshCurrent();return null;});
    check("SIGNED_OUT_ENTRY_PROMPTS_WITHOUT_READ",edt(()->((JTextArea)field(pa,"status")).getText().contains("登录后")&&fixture.pages.get()==signedOutPages));
    cookies.injectCookie("_t","synthetic-reconfirmed-account","linux.do");
    edt(()->{auth.setCurrentUserDirectly(GSON.fromJson("{\"id\":8,\"username\":\"fixture\"}",UserInfo.class));return null;});
    await("confirmed account current tab loads",()->service.state(PersonalContentKind.TOPICS).getLoaded());
    check("CONFIRMED_LOGIN_LOADS_ONLY_CURRENT_TAB",!service.state(PersonalContentKind.REPLIES).getLoaded()&&!service.state(PersonalContentKind.BOOKMARKS).getLoaded()&&!service.state(PersonalContentKind.DRAFTS).getLoaded());
    check("NO_REAL_OR_SIMULATED_WRITES",fixture.writes.get()==0);report.println("REAL_FORUM_WRITES=0");
  }
}
