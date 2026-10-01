import com.intellij.openapi.application.ApplicationStarter;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.ui.DialogWrapper;
import com.google.gson.*;
import com.lgguan.linuxdo.plugin.model.Post;
import com.lgguan.linuxdo.plugin.net.SessionEpoch;
import com.lgguan.linuxdo.plugin.net.HttpStatusException;
import com.lgguan.linuxdo.plugin.net.LinuxDoBrowser;
import com.lgguan.linuxdo.plugin.net.LinuxDoJSQuery;
import com.lgguan.linuxdo.plugin.model.TopicDetailResponse;
import com.lgguan.linuxdo.plugin.ui.toolwindow.DocViewerPanel;
import com.lgguan.linuxdo.plugin.service.*;
import com.lgguan.linuxdo.plugin.ui.dialog.*;
import kotlin.Unit;
import kotlin.jvm.functions.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Real IDEA windows, production composer and timers, isolated in-memory forum operations. */
public class IdeUiSmoke implements ApplicationStarter {
  private static final Gson GSON = new Gson();
  private static final String BODY = "保护好互联网的净土，让大家都能在社区平和的交流学习";
  private static final List<String> checks = new ArrayList<>();
  private static final List<Double> latencies = new CopyOnWriteArrayList<>();
  private static Project project;
  private static PrintWriter report;
  private static Path output;
  public String getCommandName() { return "linuxdo-ui-smoke"; }
  public boolean isHeadless() { return false; }
  public int getRequiredModality() { return NOT_IN_EDT; }
  public void main(List<String> args) {
    int exit = 1;
    try {
      output = Path.of(System.getProperty("linuxdo.host.report")).getParent();
      report = new PrintWriter(Files.newBufferedWriter(output.resolve("result.txt")), true);
      project = ProjectManager.getInstance().getDefaultProject();
      if (System.getProperty("linuxdo.draft.bridge") == null) run(); else runLive();
      report.println("IDE_UI_PASS=true");
      exit = 0;
    } catch (Throwable error) {
      error.printStackTrace();
      if (report != null) { report.println("ERROR=" + error); error.printStackTrace(report); }
    } finally {
      if (report != null) report.close();
    }
    System.exit(exit);
  }
  private static <T> T edt(Callable<T> action) throws Exception {
    if (SwingUtilities.isEventDispatchThread()) return action.call();
    FutureTask<T> task = new FutureTask<>(action);
    SwingUtilities.invokeLater(task);
    return task.get(15, TimeUnit.SECONDS);
  }
  private static void await(String name, Callable<Boolean> condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) { if (edt(condition)) return; Thread.sleep(40); }
    throw new AssertionError("Timed out: " + name);
  }
  private static void check(String name, boolean pass) {
    report.println(name + "=" + pass); report.flush();
    if (!pass) throw new AssertionError(name);
    checks.add(name);
  }
  private static Object field(Object owner, String name) throws Exception {
    Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
  }
  private static void invoke(Object owner, String name, Class<?>[] types, Object... args) throws Exception {
    Method m = owner.getClass().getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(owner, args);
  }
  private static JTextArea text(DialogWrapper dialog) throws Exception { return (JTextArea)field(dialog, "textArea"); }
  private static JLabel status(DialogWrapper dialog) throws Exception { return (JLabel)field(dialog, "draftStatus"); }
  private static boolean okEnabled(DialogWrapper dialog) throws Exception {
    Method method = DialogWrapper.class.getDeclaredMethod("getOKAction"); method.setAccessible(true);
    return ((Action)method.invoke(dialog)).isEnabled();
  }
  private static JsonObject data(String body, int floor, String action) {
    JsonObject data = new JsonObject();
    data.addProperty("reply", body); data.addProperty("action", action);
    data.addProperty("reply_to_post_number", floor); data.addProperty("postId", 9000L + floor);
    JsonObject user = new JsonObject(); user.addProperty("username", "web_author");
    data.add("reply_to_user", user);
    JsonObject extra = new JsonObject(); extra.addProperty("nested", "preserve"); data.add("unknown", extra);
    return data;
  }
  private static class Store implements InvocationHandler {
    volatile JsonObject data;
    volatile long sequence = 10;
    volatile boolean offline;
    volatile int attempts, saves, deletes;
    final DraftTransport transport;
    Store(JsonObject initial) {
      data = initial;
      transport = (DraftTransport)Proxy.newProxyInstance(DraftTransport.class.getClassLoader(), new Class<?>[]{DraftTransport.class}, this);
    }
    public Object invoke(Object proxy, Method method, Object[] args) throws Exception {
      String name = method.getName();
      if (name.equals("toString")) return "IsolatedDraftTransport";
      if (name.equals("hashCode")) return System.identityHashCode(proxy);
      if (name.equals("equals")) return proxy == args[0];
      Thread.sleep(180); // Deliberately slow I/O: the UI must remain responsive.
      synchronized (this) {
        if (offline) return kotlin.ResultKt.createFailure(new IOException("isolated offline response"));
        if (name.startsWith("read")) return new ForumDraft(sequence, data == null ? null : data.deepCopy());
        if (name.startsWith("save")) {
          attempts++;
          if (((Long)args[1]).longValue() != sequence) return kotlin.ResultKt.createFailure(new HttpStatusException(409));
          if (data != null) sequence++;
          data = ((JsonObject)args[2]).deepCopy(); saves++; return sequence;
        }
        if (name.startsWith("delete")) { if (((Long)args[1]).longValue() == sequence) { data = null; deletes++; } return Unit.INSTANCE; }
        throw new AssertionError("Unexpected transport operation " + name);
      }
    }
    synchronized String body() { return data == null ? null : data.get("reply").getAsString(); }
    synchronized void webEdit(String body, int floor) { sequence++; data = IdeUiSmoke.data(body, floor, "reply"); }
  }
  private static class Environment implements ReplyComposerEnvironment {
    volatile boolean loggedIn = true;
    volatile boolean sendFails;
    volatile int sends;
    final List<Function0<Unit>> listeners = new CopyOnWriteArrayList<>();
    public boolean isLoggedIn() { return loggedIn; }
    public void addAuthListener(Disposable owner, Function0<Unit> changed) {
      listeners.add(changed); Disposer.register(owner, () -> listeners.remove(changed));
    }
    public Post getPost(long postId) {
      return GSON.fromJson("{\"id\":" + postId + ",\"topic_id\":482293,\"post_number\":" + (postId - 9000) + ",\"username\":\"web_author\"}", Post.class);
    }
    public Post createReply(long topicId, String body, Integer floor, long version) {
      sends++;
      if (sendFails) throw new IllegalStateException("isolated send failure; no request issued");
      JsonObject response = new JsonObject(); response.addProperty("id", 99000); response.addProperty("topic_id", topicId);
      response.addProperty("raw", body); response.addProperty("post_number", 30); response.addProperty("username", "test_author");
      return GSON.fromJson(response, Post.class);
    }
  }
  private static DialogWrapper open(Store store, Environment environment, String quote) throws Exception {
    return openTransport(store.transport, environment, quote);
  }
  private static DialogWrapper openTransport(DraftTransport transport, Environment environment, String quote) throws Exception {
    ReplyDraftSession session = new ReplyDraftSession(482293, SessionEpoch.INSTANCE.getCurrent(), transport,
      (Function1<Long, Unit>)version -> { SessionEpoch.INSTANCE.requireCurrent(version); return Unit.INSTANCE; });
    return edt(() -> {
      Constructor<?> constructor = Arrays.stream(CommitReplyDialog.class.getDeclaredConstructors())
        .filter(c -> c.getParameterCount() == 9).findFirst().orElseThrow();
      constructor.setAccessible(true);
      DialogWrapper dialog = (DialogWrapper)constructor.newInstance(project, 482293L, 2, "requested_author", 9002L, quote, null, session, environment);
      dialog.show(); dialog.getWindow().setLocation(120, 100);
      return dialog;
    });
  }
  private static List<Component> components(Container root) {
    List<Component> out = new ArrayList<>();
    for (Component child : root.getComponents()) { out.add(child); if (child instanceof Container) out.addAll(components((Container)child)); }
    return out;
  }
  private static JButton button(Window window, String label) {
    if (window == null) return null;
    return components(window).stream().filter(c -> c instanceof JButton).map(c -> (JButton)c)
      .filter(b -> label.equals(b.getText())).findFirst().orElse(null);
  }
  private static void choose(String label) throws Exception {
    await("popup button " + label, () -> Arrays.stream(Window.getWindows()).anyMatch(w -> w.isShowing() && button(w, label) != null));
    edt(() -> {
      Window window = Arrays.stream(Window.getWindows()).filter(w -> w.isShowing() && button(w, label) != null).findFirst().orElseThrow();
      button(window, label).doClick(); return null;
    });
  }
  private static void closeChoice(DialogWrapper dialog, String choice) throws Exception {
    SwingUtilities.invokeLater(() -> { try { invoke(dialog, "doCancelAction", new Class<?>[0]); } catch (Exception e) { throw new RuntimeException(e); } });
    choose(choice);
  }
  private static void screenshot(DialogWrapper dialog, String name) throws Exception {
    Rectangle bounds = edt(() -> dialog.getWindow().getBounds());
    BufferedImage image = new Robot().createScreenCapture(bounds);
    ImageIO.write(image, "png", output.resolve(name + ".png").toFile());
  }
  private static void saveNow(DialogWrapper dialog) throws Exception {
    edt(() -> { invoke(dialog, "saveDraft", new Class<?>[]{Function0.class}, (Object)null); return null; });
  }
  private static class BrowserBridge implements InvocationHandler {
    final Path root = Path.of(System.getProperty("linuxdo.draft.bridge"));
    int counter;
    synchronized JsonObject request(String operation, Object[] args) throws Exception {
      JsonObject request = new JsonObject(); request.addProperty("operation", operation);
      if (args != null) {
        request.addProperty("key", (String)args[0]);
        if (args.length > 2) request.addProperty("sequence", (Long)args[1]);
        if (operation.equals("save")) request.add("data", (JsonObject)args[2]);
      }
      int id = ++counter;
      Path temp = root.resolve(id + ".tmp");
      Files.writeString(temp, request.toString());
      Files.move(temp, root.resolve(id + ".request.json"), StandardCopyOption.ATOMIC_MOVE);
      Path response = root.resolve(id + ".response.json");
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
      while (!Files.exists(response)) {
        if (System.nanoTime() > deadline) throw new IOException("Browser bridge timed out");
        Thread.sleep(40);
      }
      JsonObject result = JsonParser.parseString(Files.readString(response)).getAsJsonObject();
      if (result.has("error")) throw new IOException(result.get("error").getAsString());
      if (result.has("status") && result.get("status").getAsInt() != 200) throw new HttpStatusException(result.get("status").getAsInt());
      return result.getAsJsonObject("data");
    }
    public Object invoke(Object proxy, Method method, Object[] args) throws Exception {
      String name = method.getName();
      try {
        if (name.startsWith("read")) return ForumDraft.Companion.parse(request("read", args));
        if (name.startsWith("save")) return ForumDraft.Companion.savedSequence(request("save", args));
        if (name.startsWith("delete")) { ForumDraft.Companion.requireSuccess(request("delete", args)); return Unit.INSTANCE; }
        throw new AssertionError(name);
      } catch (Exception e) { return kotlin.ResultKt.createFailure(e); }
    }
    DraftTransport transport() { return (DraftTransport)Proxy.newProxyInstance(DraftTransport.class.getClassLoader(), new Class<?>[]{DraftTransport.class}, this); }
  }
  private static void runLive() throws Exception {
    BrowserBridge bridge = new BrowserBridge();
    Environment environment = new Environment() {
      public Post getPost(long postId) {
        try { return GSON.fromJson(bridge.request("post", null), Post.class); }
        catch (Exception error) { throw new RuntimeException(error); }
      }
    }; environment.sendFails = true;
    DialogWrapper dialog = openTransport(bridge.transport(), environment, null);
    await("real server draft restored", () -> text(dialog).isEnabled() && text(dialog).getText().equals(BODY));
    check("LIVE_IDE_RESTORES_FORUM_DRAFT", edt(() -> ((JLabel)field(dialog, "targetLabel")).getText().contains("#3")));
    edt(() -> { text(dialog).append("\n"); return null; });
    await("real autosave", () -> status(dialog).getText().equals("已同步到论坛"));
    screenshot(dialog, "live-forum-draft-restored");
    Window window = edt(dialog::getWindow);
    closeChoice(dialog, "保存草稿并关闭"); await("real save close", () -> !window.isShowing());
    JsonObject web = bridge.request("web", null);
    check("WEB_COMPOSER_RESTORES_IDE_SAVE", web.get("restored").getAsBoolean());
    check("WEB_COMPOSER_SAVES_FOR_IDE", web.get("saved").getAsBoolean());
    DialogWrapper reopened = openTransport(bridge.transport(), environment, null);
    await("web changes restored in IDE", () -> text(reopened).isEnabled() && text(reopened).getText().equals(BODY));
    check("IDE_REOPENS_WEB_COMPOSER_SAVE", edt(() -> ((JLabel)field(reopened, "targetLabel")).getText().contains("#3")));
    screenshot(reopened, "live-web-composer-handoff");
    Window second = edt(reopened::getWindow);
    closeChoice(reopened, "舍弃草稿"); await("real owned draft cleanup", () -> !second.isShowing());
    check("LIVE_HANDOFF_NEVER_SUBMITS_POST", environment.sends == 0);
    report.println("TRANSPORT=browser_authenticated_draft_only; production UI and draft session");
  }
  private static void run() throws Exception {
    check("REAL_IDE_APPLICATION", com.intellij.openapi.application.ApplicationManager.getApplication() != null);
    check("DESKTOP_AVAILABLE", !GraphicsEnvironment.isHeadless());
    Environment environment = new Environment();
    Store store = new Store(data(BODY + "\n已有网页草稿", 12, "reply"));
    DialogWrapper dialog = open(store, environment, null);
    await("draft restored", () -> text(dialog).getText().equals(store.body()) && text(dialog).isEnabled());
    check("RESTORED_BODY_AND_TARGET", edt(() -> ((JLabel)field(dialog, "targetLabel")).getText().contains("#12")));
    javax.swing.Timer heartbeat = edt(() -> {
      final long[] last = {System.nanoTime()};
      javax.swing.Timer timer = new javax.swing.Timer(20, event -> { long now = System.nanoTime(); latencies.add((now - last[0]) / 1000000.0); last[0] = now; });
      timer.start(); return timer;
    });
    String quote = DiscourseQuote.INSTANCE.format("quoted_author", 482293, 3, "中文引用\n```kotlin\nval x = 1\n```\n& <tag>");
    String original = edt(() -> text(dialog).getText());
    edt(() -> { text(dialog).select(1, 7); invoke(dialog, "insertQuote", new Class<?>[]{String.class}, quote); return null; });
    check("QUOTE_PRESERVES_EXISTING_SELECTION", edt(() -> text(dialog).getText().equals(original.substring(0, 7) + quote + original.substring(7))));
    String local = edt(() -> text(dialog).getText());
    await("two-second autosave", () -> local.equals(store.body()) && status(dialog).getText().equals("已同步到论坛"));
    check("AUTOSAVE_AND_UNKNOWN_FIELDS", store.saves == 1 && store.data.getAsJsonObject("unknown").get("nested").getAsString().equals("preserve"));
    JButton preview = edt(() -> components(dialog.getWindow()).stream().filter(c -> c instanceof JButton && c instanceof JComponent)
      .map(c -> (JButton)c).filter(b -> b.getToolTipText() != null && b.getToolTipText().contains("切换实时预览")).findFirst().orElseThrow());
    edt(() -> { preview.doClick(); return null; });
    check("PREVIEW_CAN_HIDE", edt(() -> !(Boolean)field(dialog, "isPreviewVisible")));
    edt(() -> { preview.doClick(); return null; });
    check("PREVIEW_CAN_RESTORE", edt(() -> (Boolean)field(dialog, "isPreviewVisible")));
    screenshot(dialog, "reply-restored-quote-preview");
    closeChoice(dialog, "继续编辑");
    check("CLOSE_CONTINUE_PRESERVES_BODY", edt(() -> dialog.getWindow().isShowing() && text(dialog).getText().equals(local)));
    Window firstWindow = edt(dialog::getWindow);
    edt(() -> { text(dialog).append("\n继续编辑的正文"); return null; });
    String saved = edt(() -> text(dialog).getText());
    closeChoice(dialog, "保存草稿并关闭");
    await("save and close", () -> !firstWindow.isShowing());
    check("CLOSE_SAVES_BEFORE_DISPOSE", saved.equals(store.body()));
    DialogWrapper reopened = open(store, environment, null);
    await("reopen restored", () -> text(reopened).isEnabled() && text(reopened).getText().equals(saved));
    check("REOPEN_RESTORES_LAST_SAVE", edt(() -> ((JLabel)field(reopened, "targetLabel")).getText().contains("#12")));
    Window reopenedWindow = edt(reopened::getWindow);
    closeChoice(reopened, "舍弃草稿");
    await("discard close", () -> !reopenedWindow.isShowing());
    check("DISCARD_CLEARS_OWNED_DRAFT", store.data == null && store.deletes == 1);

    Store failureStore = new Store(data(BODY, 2, "reply"));
    DialogWrapper failed = open(failureStore, environment, null);
    await("failure editor ready", () -> text(failed).isEnabled());
    failureStore.offline = true;
    edt(() -> { text(failed).append("\n离线期间的修改"); return null; });
    String offlineBody = edt(() -> text(failed).getText());
    saveNow(failed);
    await("offline error", () -> status(failed).getText().contains("同步失败"));
    check("OFFLINE_RETAINS_LOCAL_BODY", edt(() -> text(failed).getText().equals(offlineBody) && text(failed).isEnabled()));
    screenshot(failed, "reply-offline-retained");
    failureStore.offline = false;
    edt(() -> { ((JButton)field(failed, "draftRetry")).doClick(); return null; });
    await("offline retry saved", () -> offlineBody.equals(failureStore.body()) && status(failed).getText().equals("已同步到论坛"));
    check("OFFLINE_RETRY_SUCCEEDS", failureStore.saves == 1);
    Window failedWindow = edt(failed::getWindow);
    closeChoice(failed, "舍弃草稿"); await("offline editor discarded", () -> !failedWindow.isShowing());

    Store conflictStore = new Store(data(BODY, 2, "reply"));
    DialogWrapper conflict = open(conflictStore, environment, null);
    await("conflict editor ready", () -> text(conflict).isEnabled());
    conflictStore.webEdit(BODY + "\n其他客户端的版本", 6);
    edt(() -> { text(conflict).append("\n插件本地版本"); return null; }); saveNow(conflict);
    await("409 status", () -> status(conflict).getText().contains("草稿冲突"));
    int attempts = conflictStore.attempts;
    edt(() -> { text(conflict).append("\n冲突后继续编辑"); return null; });
    Thread.sleep(2300);
    check("409_PAUSES_AUTOSAVE", conflictStore.attempts == attempts);
    String chosen = edt(() -> text(conflict).getText());
    edt(() -> { ((JButton)field(conflict, "draftRetry")).doClick(); return null; });
    await("conflict selection", () -> Arrays.stream(Window.getWindows()).anyMatch(w -> w.isShowing() && button(w, "保留本地版本") != null));
    check("CONFLICT_SHOWS_BOTH_COMPLETE_BODIES", edt(() -> Arrays.stream(Window.getWindows()).filter(w -> w.isShowing() && button(w, "保留本地版本") != null)
      .anyMatch(w -> { List<String> bodies = new ArrayList<>(); components(w).stream().filter(c -> c instanceof JTextArea).forEach(c -> bodies.add(((JTextArea)c).getText())); return bodies.contains(chosen) && bodies.contains(conflictStore.body()); })));
    choose("保留本地版本");
    await("local chosen saved", () -> chosen.equals(conflictStore.body()) && status(conflict).getText().equals("已同步到论坛"));
    check("CONFLICT_LOCAL_CHOICE_SAVES_CURRENT_SEQUENCE", conflictStore.saves == 1);
    conflictStore.webEdit(BODY + "\n采用网页版本", 7);
    edt(() -> { text(conflict).append("\n再次发生冲突"); return null; }); saveNow(conflict);
    await("second conflict", () -> status(conflict).getText().contains("草稿冲突"));
    edt(() -> { ((JButton)field(conflict, "draftRetry")).doClick(); return null; });
    choose("采用服务器版本");
    await("server choice restored", () -> text(conflict).getText().equals(conflictStore.body()) && status(conflict).getText().contains("采用服务器"));
    check("CONFLICT_SERVER_CHOICE_RESTORES_TARGET", edt(() -> ((JLabel)field(conflict, "targetLabel")).getText().contains("#7")));
    screenshot(conflict, "reply-server-version");
    Window conflictWindow = edt(conflict::getWindow);
    closeChoice(conflict, "舍弃草稿"); await("conflict editor discarded", () -> !conflictWindow.isShowing());

    Store unsupportedStore = new Store(data("Existing post edit must remain intact", 2, "edit"));
    DialogWrapper unsupported = open(unsupportedStore, environment, quote);
    await("unsupported warning", () -> status(unsupported).getText().contains("其他类型"));
    check("UNSUPPORTED_DRAFT_NOT_WRITABLE", edt(() -> !text(unsupported).isEnabled() && !okEnabled(unsupported)));
    check("UNSUPPORTED_DRAFT_BROWSER_ENTRY", edt(() -> ((JButton)field(unsupported, "draftRetry")).getText().equals("在浏览器打开")));
    edt(() -> { unsupported.close(DialogWrapper.CANCEL_EXIT_CODE); return null; });
    check("UNSUPPORTED_DRAFT_UNCHANGED", unsupportedStore.saves == 0 && unsupportedStore.deletes == 0);

    Store switchedStore = new Store(data(BODY, 2, "reply"));
    DialogWrapper switched = open(switchedStore, environment, null);
    await("switch editor ready", () -> text(switched).isEnabled());
    edt(() -> { text(switched).append("\n账号切换前未同步内容"); SessionEpoch.INSTANCE.advance(); environment.listeners.forEach(Function0::invoke); return null; });
    Thread.sleep(2300);
    check("ACCOUNT_SWITCH_STOPS_AUTOSAVE", switchedStore.saves == 0 && edt(() -> !okEnabled(switched) && status(switched).getText().contains("已停止同步")));
    screenshot(switched, "reply-account-switch");
    edt(() -> { switched.close(DialogWrapper.CANCEL_EXIT_CODE); return null; });

    Store sendStore = new Store(data(BODY, 2, "reply"));
    Environment sendEnvironment = new Environment(); sendEnvironment.sendFails = true;
    DialogWrapper send = open(sendStore, sendEnvironment, null);
    await("send editor ready", () -> text(send).isEnabled() && okEnabled(send));
    SwingUtilities.invokeLater(() -> { try { invoke(send, "doOKAction", new Class<?>[0]); } catch (Exception e) { throw new RuntimeException(e); } });
    choose("OK");
    check("SIMULATED_SEND_FAILURE_RETAINS_BODY", edt(() -> text(send).getText().equals(BODY) && okEnabled(send)) && sendStore.body().equals(BODY) && sendStore.deletes == 0);
    Window sendWindow = edt(send::getWindow);
    sendEnvironment.sendFails = false;
    edt(() -> { invoke(send, "doOKAction", new Class<?>[0]); return null; });
    await("simulated successful send", () -> !sendWindow.isShowing());
    check("SIMULATED_SEND_SUCCESS_CLEANS_DRAFT", sendStore.data == null && sendEnvironment.sends == 2 && sendStore.deletes == 1);
    edt(() -> { heartbeat.stop(); return null; });
    List<Double> sorted = new ArrayList<>(latencies); Collections.sort(sorted);
    double p95 = sorted.get((int)Math.floor((sorted.size() - 1) * .95));
    report.println("EDT_HEARTBEAT_P95_MS=" + p95);
    check("RESPONSIVE_DURING_ASYNC_DRAFT_IO", p95 < 120);
    report.println("CHECKS=" + checks.size());
    report.println("REAL_FORUM_WRITES=0");
    reader();
  }
  private static JsonObject evaluate(LinuxDoBrowser browser, LinuxDoJSQuery query, BlockingQueue<String> replies, String expression) throws Exception {
    replies.clear();
    browser.getCefBrowser().executeJavaScript("(async()=>{try{var r=await (" + expression + ");" + query.inject("'probe:'+JSON.stringify(r)") +
      "}catch(e){" + query.inject("'probe:'+JSON.stringify({error:e.name})") + "}})()", browser.getCefBrowser().getURL(), 0);
    String value = replies.poll(10, TimeUnit.SECONDS);
    if (value == null) throw new AssertionError("IDE reader query timed out");
    JsonObject result = JsonParser.parseString(value).getAsJsonObject();
    if (result.has("error")) throw new AssertionError(result.toString());
    return result;
  }
  private static void reader() throws Exception {
    JsonObject topic = new JsonObject(); topic.addProperty("id", 999999); topic.addProperty("title", "IDE 文档阅读验收");
    topic.addProperty("highest_post_number", 12); topic.addProperty("posts_count", 12);
    JsonArray posts = new JsonArray(); JsonArray ids = new JsonArray();
    for (int floor = 1; floor <= 12; floor++) {
      JsonObject post = new JsonObject(); post.addProperty("id", floor); post.addProperty("topic_id", 999999);
      post.addProperty("post_number", floor); post.addProperty("username", "fixture_author");
      post.addProperty("cooked", "<p>中文正文第 " + floor + " 楼</p><pre><code>val sample = " + floor + "</code></pre><p>本地阅读验收</p>");
      posts.add(post); ids.add(floor);
    }
    JsonObject stream = new JsonObject(); stream.add("posts", posts); stream.add("stream", ids); topic.add("post_stream", stream);
    TopicDetailResponse detail = GSON.fromJson(topic, TopicDetailResponse.class);
    DocViewerPanel panel = edt(() -> new DocViewerPanel(project));
    JFrame frame = edt(() -> { JFrame f = new JFrame("Linux Do reader acceptance"); f.setContentPane(panel); f.setSize(960, 700); f.setLocation(120, 100); f.setVisible(true); return f; });
    LinuxDoJSQuery query = null;
    try {
      await("IDE native reader available", () -> field(panel, "jbCefBrowser") != null);
      LinuxDoBrowser browser = (LinuxDoBrowser)edt(() -> field(panel, "jbCefBrowser"));
      BlockingQueue<String> replies = new LinkedBlockingQueue<>();
      query = (LinuxDoJSQuery)edt(() -> field(panel, "jsQuery"));
      @SuppressWarnings("unchecked") Function1<String, LinuxDoJSQuery.Response> productionHandler =
        (Function1<String, LinuxDoJSQuery.Response>)field(query, "handler");
      query.addHandler(value -> {
        if (value.startsWith("probe:")) { replies.add(value.substring(6)); return null; }
        return productionHandler.invoke(value);
      });
      edt(() -> { Field f = DocViewerPanel.class.getDeclaredField("currentTopic"); f.setAccessible(true); f.set(panel, detail);
        invoke(panel, "renderTopic", new Class<?>[]{TopicDetailResponse.class, Integer.class}, detail, 3); return null; });
      Thread.sleep(1200);
      check("IDE_PRODUCTION_READER_RENDERS", evaluate(browser, query, replies, "({ok:document.querySelectorAll('.post-entry').length===12 && !!window.linuxDoPagination})").get("ok").getAsBoolean());
      evaluate(browser, query, replies, "(()=>{window.linuxDoPagination.jump(8);return {ok:true}})()");
      await("IDE receives successful jump origin", () -> ((Map<?,?>)field(panel, "returnFloors")).containsKey(999999L));
      check("IDE_NAVIGATION_RETURN_BRIDGE", edt(() -> ((Map<?,?>)field(panel, "returnFloors")).get(999999L) != null));
      check("IDE_RETURN_BUTTON_ENABLED", evaluate(browser, query, replies, "({ok:!document.querySelector('.topic-return-button').disabled})").get("ok").getAsBoolean());
      evaluate(browser, query, replies, "(()=>{document.querySelector('.topic-return-button').click();return {ok:true}})()");
      edt(() -> { frame.setSize(360, 700); return null; }); Thread.sleep(300);
      check("IDE_NARROW_NAVIGATION_NO_OVERFLOW", evaluate(browser, query, replies, "({ok:document.documentElement.scrollWidth<=window.innerWidth+1})").get("ok").getAsBoolean());
      ImageIO.write(new Robot().createScreenCapture(edt(frame::getBounds)), "png", output.resolve("reader-narrow-navigation.png").toFile());
    } finally {
      if (query != null) query.dispose();
      edt(() -> { panel.dispose(); frame.dispose(); return null; });
    }
    report.println("TOTAL_CHECKS=" + checks.size());
  }
}
