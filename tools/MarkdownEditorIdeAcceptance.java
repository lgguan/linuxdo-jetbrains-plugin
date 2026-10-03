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
public final class MarkdownEditorIdeAcceptance implements ApplicationStarter {
  private static final Gson GSON=new Gson();
  private static PrintWriter report;
  private static Path output;
  private static int checks;
  private static final List<Project> projects=new ArrayList<>();
  private static final Fixture fixture=new Fixture();
  public String getCommandName(){return "linuxdo-editor-acceptance";}
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
    volatile String postBody="Existing post body";
    volatile JsonObject editInput;
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
    JsonObject post(){JsonObject p=json("{\"id\":9901012,\"topic_id\":990101,\"post_number\":2,\"username\":\"fixture\",\"can_edit\":true,\"version\":1}");p.addProperty("raw",postBody);p.addProperty("cooked","<p>Existing post body</p>");return p;}
    static JsonObject json(String value){return JsonParser.parseString(value).getAsJsonObject();}
    void hold(String path){holdPath=path;entered=new CountDownLatch(1);release=new CountDownLatch(1);}
    void unblock(){holdPath="";release.countDown();}
    public okhttp3.Response intercept(okhttp3.Interceptor.Chain chain)throws IOException {
      var request=chain.request();String path=request.url().encodedPath();String body="{}";int code=200;
      if(!request.method().equals("GET")){
        writes.incrementAndGet();
        if(path.equals("/drafts.json") && request.method().equals("POST")) {
          okio.Buffer encoded=new okio.Buffer();request.body().writeTo(encoded);Map<String,String> form=new HashMap<>();
          for(String part:encoded.readUtf8().split("&")){String[] pair=part.split("=",2);form.put(java.net.URLDecoder.decode(pair[0],java.nio.charset.StandardCharsets.UTF_8),java.net.URLDecoder.decode(pair.length==2?pair[1]:"",java.nio.charset.StandardCharsets.UTF_8));}
          String key=form.get("draft_key"),data=form.get("data");
          drafts.put(key,json(data));body="{\"draft_sequence\":2,\"success\":true}";
        } else if(path.equals("/posts/9901012.json")&&request.method().equals("PUT")) {
          okio.Buffer buffer=new okio.Buffer();request.body().writeTo(buffer);editInput=json(buffer.readUtf8()).getAsJsonObject("post");postBody=editInput.get("raw").getAsString();
        } else {code=503;}
      }
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
      }else if(path.equals("/posts/9901012.json"))body=post().toString();
      else if(path.equals("/t/990101/posts.json"))body="{\"post_stream\":{\"posts\":["+post()+"]}}";
      else if(path.equals("/session/csrf.json"))body="{\"csrf\":\"synthetic-editor-token\"}";
      else if(path.matches("/t/[0-9]+(?:/[0-9]+)?\\.json")){
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

  private static javax.swing.JTextArea text(Object dialog)throws Exception {
    return (JTextArea)field(dialog,dialog.getClass().getSimpleName().equals("EditPostDialog")?"text":"textArea");
  }
  private static Object support(Object dialog)throws Exception {
    if(dialog.getClass().getSimpleName().equals("EditPostDialog"))return field(dialog,"editor");
    Method m=dialog.getClass().getDeclaredMethod("getEditorSupport");m.setAccessible(true);return m.invoke(dialog);
  }
  private static void key(JTextArea text,int code,int modifiers) {
    Object name=text.getInputMap().get(KeyStroke.getKeyStroke(code,modifiers));
    text.getActionMap().get(name).actionPerformed(new java.awt.event.ActionEvent(text,0,"native-acceptance"));
  }
  private static List<Component> components(Container root){List<Component> out=new ArrayList<>();for(Component c:root.getComponents()){out.add(c);if(c instanceof Container)out.addAll(components((Container)c));}return out;}
  private static JButton control(Object dialog,String name)throws Exception {
    Window window=((com.intellij.openapi.ui.DialogWrapper)dialog).getWindow();return components(window).stream().filter(c->c instanceof JButton&&name.equals(c.getName())).map(c->(JButton)c).findFirst().orElseThrow();
  }
  private static Window insertWindow(String name)throws Exception {
    for(Window w:Window.getWindows())if(w.isShowing()&&components(w).stream().anyMatch(c->name.equals(c.getName())))return w;return null;
  }
  private static void menu(Object dialog,String label)throws Exception {
    control(dialog,"composer-more").doClick();JPopupMenu popup=Arrays.stream(MenuSelectionManager.defaultManager().getSelectedPath()).map(MenuElement::getComponent).filter(c->c instanceof JPopupMenu).map(c->(JPopupMenu)c).findFirst().orElseThrow();
    JMenuItem item=Arrays.stream(popup.getComponents()).filter(c->c instanceof JMenuItem&&label.equals(((JMenuItem)c).getText())).map(c->(JMenuItem)c).findFirst().orElseThrow();item.doClick();
  }
  private static void insertChecks(Object dialog)throws Exception {
    JTextArea text=text(dialog);String original=edt(text::getText);int count=fixture.writes.get();
    SwingUtilities.invokeLater(()->WriteIntentReadAction.run((Runnable)()->{try{control(dialog,"composer-link").doClick();}catch(Exception e){throw new RuntimeException(e);}}));
    await("link dialog",()->insertWindow("composer-link-url")!=null);Window window=edt(()->insertWindow("composer-link-url"));
    edt(()->{for(Component c:components(window))if(c instanceof JTextField f&&"composer-link-url".equals(c.getName()))f.setText("https://example.com/a(b)");return null;});
    edt(()->{components(window).stream().filter(c->c instanceof JButton&&"Cancel".equals(((JButton)c).getText())).map(c->(JButton)c).findFirst().orElseThrow().doClick();return null;});
    await("cancel link",()->!window.isShowing());check("LINK_CANCEL_NO_EDIT_OR_WRITE",original.equals(edt(text::getText))&&fixture.writes.get()==count);
    SwingUtilities.invokeLater(()->WriteIntentReadAction.run((Runnable)()->{try{menu(dialog,"代码块…");}catch(Exception e){throw new RuntimeException(e);}}));
    await("code language dialog",()->insertWindow("composer-code-language")!=null);Window code=edt(()->insertWindow("composer-code-language"));
    edt(()->{for(Component c:components(code))if(c instanceof JTextField f&&"composer-code-language".equals(c.getName()))f.setText("kotlin");components(code).stream().filter(c->c instanceof JButton&&"应用".equals(((JButton)c).getText())).map(c->(JButton)c).findFirst().orElseThrow().doClick();return null;});
    await("code applied",()->!code.isShowing());check("CODE_DIALOG_APPLIES_LANGUAGE",edt(()->text.getText().contains("```kotlin")));
    edt(()->{control(dialog,"composer-undo").doClick();return null;});check("CODE_DIALOG_SINGLE_UNDO",original.equals(edt(text::getText)));
  }

  private static void physicalKeyboard(Object dialog,String label)throws Exception {
    JTextArea text=text(dialog);Window window=((com.intellij.openapi.ui.DialogWrapper)dialog).getWindow();
    edt(()->{window.setAlwaysOnTop(true);window.toFront();window.requestFocus();text.getInputContext().endComposition();text.getInputContext().selectInputMethod(Locale.US);text.enableInputMethods(false);text.setText("- item");text.setCaretPosition(6);text.requestFocusInWindow();return null;});
    click(text);edt(()->{text.setCaretPosition(6);return null;});
    await("actual editor focus",text::hasFocus);Robot robot=new Robot();robot.setAutoDelay(60);
    robot.keyPress(java.awt.event.KeyEvent.VK_TAB);robot.keyRelease(java.awt.event.KeyEvent.VK_TAB);robot.waitForIdle();
    check(label+"_PHYSICAL_TAB_INDENT",edt(()->text.getText().equals("  - item")&&text.hasFocus()));
    robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL);robot.keyPress(java.awt.event.KeyEvent.VK_TAB);robot.keyRelease(java.awt.event.KeyEvent.VK_TAB);robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL);robot.waitForIdle();
    await("control tab leaves editor",()->!text.hasFocus());check(label+"_CTRL_TAB_FOCUS_ESCAPE",edt(()->text.getText().equals("  - item")));
    edt(()->{text.setText("plain");window.toFront();window.requestFocus();return null;});click(text);await("ordinary editor focus",text::hasFocus);
    robot.keyPress(java.awt.event.KeyEvent.VK_TAB);robot.keyRelease(java.awt.event.KeyEvent.VK_TAB);robot.waitForIdle();await("plain tab leaves editor",()->!text.hasFocus());
    check(label+"_ORDINARY_TAB_NAVIGATION",edt(()->text.getText().equals("plain")));
    edt(()->{text.enableInputMethods(true);return null;});
  }
  private static void linkEditCheck(Object dialog,String label)throws Exception {
    JTextArea text=text(dialog);edt(()->{text.setText("[label](https://example.com/old)");text.setCaretPosition(3);return null;});
    SwingUtilities.invokeLater(()->WriteIntentReadAction.run((Runnable)()->{try{control(dialog,"composer-link").doClick();}catch(Exception e){throw new RuntimeException(e);}}));
    await("existing link dialog",()->insertWindow("composer-link-url")!=null);Window window=edt(()->insertWindow("composer-link-url"));
    check(label+"_LINK_BACKFILLED",edt(()->components(window).stream().anyMatch(c->c instanceof JTextField&&"composer-link-url".equals(c.getName())&&((JTextField)c).getText().equals("https://example.com/old"))));
    edt(()->{for(Component c:components(window))if(c instanceof JTextField f&&"composer-link-url".equals(c.getName()))f.setText("https://example.com/new");components(window).stream().filter(c->c instanceof JButton&&"应用".equals(((JButton)c).getText())).map(c->(JButton)c).findFirst().orElseThrow().doClick();return null;});
    await("existing link applied",()->!window.isShowing());check(label+"_LINK_REPLACED_WITHOUT_NESTING",edt(()->text.getText().equals("[label](<https://example.com/new>)")));
  }
  private static void systemIme(Object dialog,String label)throws Exception {
    JTextArea text=text(dialog);Window window=((com.intellij.openapi.ui.DialogWrapper)dialog).getWindow();
    java.awt.im.InputContext context=edt(text::getInputContext);Locale previous=edt(context::getLocale);
    AtomicInteger events=new AtomicInteger();int writesBefore=fixture.writes.get();
    java.awt.event.InputMethodListener observer=new java.awt.event.InputMethodListener(){
      public void inputMethodTextChanged(java.awt.event.InputMethodEvent e){events.incrementAndGet();}
      public void caretPositionChanged(java.awt.event.InputMethodEvent e){}
    };
    edt(()->{text.addInputMethodListener(observer);window.setAlwaysOnTop(true);window.toFront();text.setText("- ");text.setCaretPosition(2);text.requestFocusInWindow();return null;});
    await("system IME editor focus",text::hasFocus);Robot robot=new Robot();robot.setAutoDelay(100);
    try {
      check(label+"_SYSTEM_CHINESE_IME_SELECTED",edt(()->context.selectInputMethod(Locale.SIMPLIFIED_CHINESE)));
      for(int attempt=0;attempt<2;attempt++){
        if(attempt>0){edt(()->{context.endComposition();text.setText("- ");text.setCaretPosition(2);return null;});robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL);robot.keyPress(java.awt.event.KeyEvent.VK_SPACE);robot.keyRelease(java.awt.event.KeyEvent.VK_SPACE);robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL);}
        for(int code:new int[]{java.awt.event.KeyEvent.VK_N,java.awt.event.KeyEvent.VK_I,java.awt.event.KeyEvent.VK_H,java.awt.event.KeyEvent.VK_A,java.awt.event.KeyEvent.VK_O}){robot.keyPress(code);robot.keyRelease(code);}
        robot.waitForIdle();Thread.sleep(300);
        if(edt(()->(Boolean)field(support(dialog),"composing")))break;
      }
      check(label+"_SYSTEM_CANDIDATE_COMPOSITION",events.get()>0&&edt(()->(Boolean)field(support(dialog),"composing")));
      Rectangle bounds=edt(window::getBounds);ImageIO.write(robot.createScreenCapture(bounds),"png",output.resolve("editor-"+label.toLowerCase()+"-ime.png").toFile());
      robot.keyPress(java.awt.event.KeyEvent.VK_ENTER);robot.keyRelease(java.awt.event.KeyEvent.VK_ENTER);robot.waitForIdle();
      await("system candidate confirmed",()->!(Boolean)field(support(dialog),"composing"));
      check(label+"_SYSTEM_ENTER_NO_LIST_CONTINUATION",edt(()->text.getText().startsWith("- ")&&!text.getText().contains("\n")&&text.getText().length()>2));
      check(label+"_SYSTEM_ENTER_NO_SUBMIT",edt(window::isShowing)&&fixture.writes.get()==writesBefore);
      // Let the native WM_IME_ENDCOMPOSITION finish before starting a second candidate sequence.
      Thread.sleep(300);
      for(int attempt=0;attempt<2;attempt++){
        edt(()->{context.endComposition();window.toFront();text.setText("- ");text.setCaretPosition(2);text.requestFocusInWindow();return null;});
        await("system IME focus before Tab",text::hasFocus);
        if(attempt>0){robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL);robot.keyPress(java.awt.event.KeyEvent.VK_SPACE);robot.keyRelease(java.awt.event.KeyEvent.VK_SPACE);robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL);}
        for(int code:new int[]{java.awt.event.KeyEvent.VK_N,java.awt.event.KeyEvent.VK_I,java.awt.event.KeyEvent.VK_H,java.awt.event.KeyEvent.VK_A,java.awt.event.KeyEvent.VK_O}){robot.keyPress(code);robot.keyRelease(code);}robot.waitForIdle();Thread.sleep(300);
        if(edt(()->(Boolean)field(support(dialog),"composing")))break;
      }
      check(label+"_SYSTEM_CANDIDATE_BEFORE_TAB",edt(()->(Boolean)field(support(dialog),"composing")));
      robot.keyPress(java.awt.event.KeyEvent.VK_TAB);robot.keyRelease(java.awt.event.KeyEvent.VK_TAB);robot.waitForIdle();
      check(label+"_SYSTEM_TAB_NO_LIST_INDENT",edt(()->text.getText().startsWith("- ")));
      check(label+"_SYSTEM_TAB_NO_SUBMIT",edt(window::isShowing)&&fixture.writes.get()==writesBefore);
    } finally {
      edt(()->{context.endComposition();text.removeInputMethodListener(observer);if(previous!=null)context.selectInputMethod(previous);return null;});
    }
  }
  private static void dialogShot(Object dialog,String label)throws Exception {
    Window window=((com.intellij.openapi.ui.DialogWrapper)dialog).getWindow();
    edt(()->{window.toFront();window.requestFocus();return null;});Thread.sleep(200);
    Rectangle bounds=edt(window::getBounds);ImageIO.write(new Robot().createScreenCapture(bounds),"png",output.resolve("editor-"+label.toLowerCase()+".png").toFile());
    report.println("SCREENSHOT=editor-"+label.toLowerCase()+".png");
  }
  private static void helpers(Object dialog,String label)throws Exception {
    JTextArea text=text(dialog);Object support=support(dialog);
    if(!label.equals("EDIT"))edt(()->{assign(dialog,label.equals("TOPIC")?"suppressDraft":"suppressDraftChanges",true);return null;});
    String original=edt(text::getText);
    edt(()->{text.select(0,text.getDocument().getLength());control(dialog,"composer-bold").doClick();return null;});
    check(label+"_BOLD_AND_SELECTION",edt(()->text.getText().equals("**"+original+"**")&&text.getSelectedText().equals(original)));
    edt(()->{control(dialog,"composer-bold").doClick();return null;});check(label+"_TOGGLE",original.equals(edt(text::getText)));
    edt(()->{control(dialog,"composer-undo").doClick();return null;});check(label+"_UNDO_ONE_OPERATION",edt(()->text.getText().equals("**"+original+"**")&&text.getSelectedText().equals(original)));
    edt(()->{control(dialog,"composer-redo").doClick();return null;});check(label+"_REDO_SELECTION",edt(()->text.getText().equals(original)&&text.getSelectedText().equals(original)));
    edt(()->{text.setText("> - [x] item");text.setCaretPosition(text.getDocument().getLength());key(text,java.awt.event.KeyEvent.VK_ENTER,0);return null;});
    check(label+"_ENTER_TASK",edt(()->text.getText().equals("> - [x] item\n> - [ ] ")));
    edt(()->{key(text,java.awt.event.KeyEvent.VK_ENTER,0);return null;});check(label+"_EMPTY_EXIT",edt(()->text.getText().endsWith("\n> ")));
    edt(()->{text.setText("- item");text.setCaretPosition(6);key(text,java.awt.event.KeyEvent.VK_TAB,0);return null;});check(label+"_TAB_INDENT",edt(()->text.getText().equals("  - item")));
    edt(()->{key(text,java.awt.event.KeyEvent.VK_TAB,InputEvent.SHIFT_DOWN_MASK);return null;});check(label+"_TAB_OUTDENT",edt(()->text.getText().equals("- item")));
    edt(()->{key(text,java.awt.event.KeyEvent.VK_ENTER,InputEvent.SHIFT_DOWN_MASK);return null;});check(label+"_SHIFT_ENTER_PLAIN",edt(()->text.getText().equals("- item\n")));
    edt(()->{text.setText("- item");text.setCaretPosition(6);var event=new java.awt.event.InputMethodEvent(text,java.awt.event.InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,new java.text.AttributedString("候选").getIterator(),0,null,null);for(var l:text.getInputMethodListeners())l.inputMethodTextChanged(event);key(text,java.awt.event.KeyEvent.VK_ENTER,0);key(text,java.awt.event.KeyEvent.VK_TAB,0);return null;});
    check(label+"_IME_COMPOSITION_PRESERVED",edt(()->text.getText().equals("- item")));
    edt(()->{var event=new java.awt.event.InputMethodEvent(text,java.awt.event.InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,null,0,null,null);for(var l:text.getInputMethodListeners())l.inputMethodTextChanged(event);text.setText(original);text.select(0,original.length());return null;});
    edt(()->{Method reset=support.getClass().getDeclaredMethod("resetUndo");reset.invoke(support);return null;});
    insertChecks(dialog);
    physicalKeyboard(dialog,label);
    if(Boolean.getBoolean("linuxdo.editor.system.ime"))systemIme(dialog,label);
    linkEditCheck(dialog,label);
    edt(()->{text.setText(original);text.select(0,original.length());return null;});
    var colors=com.intellij.openapi.editor.colors.EditorColorsManager.getInstance();var previous=edt(colors::getGlobalScheme);
    var light=Arrays.stream(colors.getAllSchemes()).filter(c->!com.intellij.ui.ColorUtil.isDark(c.getDefaultBackground())).findFirst().orElseThrow();
    edt(()->{colors.setGlobalScheme(light);return null;});await("editor light theme",()->text.getBackground().equals(light.getDefaultBackground()));
    check(label+"_THEME_PRESERVES_SELECTION",edt(()->original.equals(text.getSelectedText())));
    edt(()->{colors.setGlobalScheme(previous);return null;});await("editor theme restored",()->text.getBackground().equals(previous.getDefaultBackground()));
    Window owner=((com.intellij.openapi.ui.DialogWrapper)dialog).getWindow();Dimension size=edt(owner::getSize);
    edt(()->{owner.setSize(440,640);return null;});Thread.sleep(150);
    check(label+"_NARROW_TOOLBAR_PRESENT",edt(()->control(dialog,"composer-more").isShowing()));
    edt(()->{owner.setSize(size);return null;});dialogShot(dialog,label);
    edt(()->{text.setText(original);text.select(0,original.length());text.setEnabled(false);control(dialog,"composer-bold").doClick();return null;});check(label+"_DISABLED_NO_EDIT",original.equals(edt(text::getText)));
    edt(()->{text.setEnabled(true);return null;});
    if(!label.equals("EDIT"))edt(()->{assign(dialog,label.equals("TOPIC")?"suppressDraft":"suppressDraftChanges",false);return null;});
  }
  private static void run()throws Exception {
    Registry.get("ide.experimental.ui.onboarding").setValue(false);
    var settings=LinuxDoSettingsState.Companion.getInstance();settings.setNetworkMode("JAVA_ONLY");settings.setEnableNotificationPolling(false);settings.setAutoReportReadTimings(false);
    var cookies=new PersistentCookieJar(false);cookies.injectCookie("_t","synthetic-personal-account","linux.do");
    var auth=LinuxDoAuthService.Companion.getInstance();assign(auth,"credentials",cookies);
    Field client=LinuxDoHttpClient.class.getDeclaredField("client");client.setAccessible(true);client.set(null,LinuxDoHttpClient.INSTANCE.getClient().newBuilder().cookieJar(cookies).addInterceptor(fixture).build());
    edt(()->{auth.setCurrentUserDirectly(GSON.fromJson("{\"id\":7,\"username\":\"fixture\"}",UserInfo.class));return null;});
    LinuxDoNotificationService.Companion.getInstance().stopPolling();
    Project first=openProject("EditorProjectA"),second=openProject("EditorProjectB");
    check("TWO_REAL_PROJECT_FRAMES",first.isOpen()&&second.isOpen());
    edt(()->{com.lgguan.linuxdo.plugin.ui.dialog.CreateTopicDialog.Companion.openDraft(first,"new_topic_suffix");return null;});
    await("topic restored",()->draftEditor(com.lgguan.linuxdo.plugin.ui.dialog.CreateTopicDialog.class,"new_topic_suffix")!=null&&(Boolean)field(draftEditor(com.lgguan.linuxdo.plugin.ui.dialog.CreateTopicDialog.class,"new_topic_suffix"),"draftReady"));
    Object topic=edt(()->draftEditor(com.lgguan.linuxdo.plugin.ui.dialog.CreateTopicDialog.class,"new_topic_suffix"));
    edt(()->{com.lgguan.linuxdo.plugin.ui.dialog.CreateTopicDialog.Companion.openDraft(second,"new_topic_suffix");return null;});
    check("CROSS_PROJECT_SAME_DRAFT_REUSES_EDITOR",editors(com.lgguan.linuxdo.plugin.ui.dialog.CreateTopicDialog.class).size()==1);
    check("RESTORE_NO_WRITES",fixture.writes.get()==0);helpers(topic,"TOPIC");
    final int before=fixture.writes.get();edt(()->{JTextArea t=text(topic);t.select(0,t.getDocument().getLength());control(topic,"composer-bold").doClick();return null;});
    await("topic autosave",()->fixture.writes.get()==before+1&&!((Boolean)field(topic,"draftBusy")));Thread.sleep(2400);
    check("TOPIC_ONE_DEBOUNCED_SAVE_ACTUAL_KEY",fixture.writes.get()==before+1&&fixture.drafts.get("new_topic_suffix").get("reply").getAsString().startsWith("**")&&!fixture.drafts.get("new_topic").get("reply").getAsString().startsWith("**"));
    edt(()->{((com.intellij.openapi.ui.DialogWrapper)topic).close(com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE);com.lgguan.linuxdo.plugin.ui.dialog.CommitReplyDialog.Companion.openDraft(second,990101);return null;});
    await("reply restored",()->draftEditor(com.lgguan.linuxdo.plugin.ui.dialog.CommitReplyDialog.class,"topic_990101")!=null&&(Boolean)field(draftEditor(com.lgguan.linuxdo.plugin.ui.dialog.CommitReplyDialog.class,"topic_990101"),"draftReady"));
    Object reply=edt(()->draftEditor(com.lgguan.linuxdo.plugin.ui.dialog.CommitReplyDialog.class,"topic_990101"));helpers(reply,"REPLY");
    final int replyBefore=fixture.writes.get();edt(()->{JTextArea t=text(reply);t.select(0,t.getDocument().getLength());control(reply,"composer-italic").doClick();return null;});await("reply autosave",()->fixture.writes.get()==replyBefore+1&&!((Boolean)field(reply,"draftBusy")));
    check("REPLY_ONE_DEBOUNCED_SAVE",fixture.drafts.get("topic_990101").get("reply").getAsString().startsWith("*"));
    edt(()->{((com.intellij.openapi.ui.DialogWrapper)reply).close(com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE);return null;});
    Class<?> editClass=Class.forName("com.lgguan.linuxdo.plugin.ui.dialog.EditPostDialog");
    Object edit=edt(()->{Constructor<?> c=editClass.getDeclaredConstructors()[0];c.setAccessible(true);
      var response=GSON.fromJson("{\"id\":990101,\"title\":\"Editor fixture\",\"archetype\":\"regular\"}",TopicDetailResponse.class);
      Object d=c.newInstance(first,response,GSON.fromJson(fixture.post(),Post.class),new TopicOperationService(),SessionEpoch.INSTANCE.getCurrent(),(kotlin.jvm.functions.Function0<Boolean>)()->true,(kotlin.jvm.functions.Function1<OperationResult,Unit>)r->Unit.INSTANCE);
      ((com.intellij.openapi.ui.DialogWrapper)d).setModal(false);((com.intellij.openapi.ui.DialogWrapper)d).show();return d;});
    helpers(edit,"EDIT");int editBefore=fixture.writes.get();Thread.sleep(2200);check("EDIT_HAS_NO_AUTOSAVE",fixture.writes.get()==editBefore);
    edt(()->{JTextArea t=text(edit);t.select(0,t.getDocument().getLength());control(edit,"composer-bold").doClick();((JTextField)field(edit,"reason")).setText("fixture reason");return null;});
    SwingUtilities.invokeLater(()->WriteIntentReadAction.run((Runnable)()->{try{Method m=editClass.getDeclaredMethod("doOKAction");m.setAccessible(true);m.invoke(edit);}catch(Exception e){throw new RuntimeException(e);}}));
    await("edit saved",()->((com.intellij.openapi.ui.DialogWrapper)edit).isDisposed());
    check("EDIT_EXPLICIT_SAVE_PRESERVES_BASELINE_AND_REASON",fixture.writes.get()==editBefore+1&&fixture.editInput.get("original_text").getAsString().equals("Existing post body")&&fixture.editInput.get("edit_reason").getAsString().equals("fixture reason"));
    report.println(Boolean.getBoolean("linuxdo.editor.system.ime")?"INPUT_METHOD_CHECK=installed Windows Chinese IME; physical candidate Enter and Tab in three production dialogs":"INPUT_METHOD_CHECK=synthetic composition events delivered to actual IDEA editor; OS candidate UI not automated");
    report.println("REAL_FORUM_WRITES=0");report.println("SIMULATED_WRITES="+fixture.writes.get());
  }
}
