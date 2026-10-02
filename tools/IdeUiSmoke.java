import com.intellij.openapi.application.ApplicationStarter;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.ui.DialogWrapper;
import com.google.gson.*;
import com.lgguan.linuxdo.plugin.model.Post;
import com.lgguan.linuxdo.plugin.model.*;
import com.lgguan.linuxdo.plugin.net.SessionEpoch;
import com.lgguan.linuxdo.plugin.net.HttpStatusException;
import com.lgguan.linuxdo.plugin.net.LinuxDoBrowser;
import com.lgguan.linuxdo.plugin.net.LinuxDoJSQuery;
import com.lgguan.linuxdo.plugin.model.TopicDetailResponse;
import com.lgguan.linuxdo.plugin.ui.toolwindow.DocViewerPanel;
import com.lgguan.linuxdo.plugin.ui.toolwindow.IssueListPanel;
import com.lgguan.linuxdo.plugin.api.DiscourseApiClient;
import com.lgguan.linuxdo.plugin.net.LinuxDoHttpClient;
import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState;
import com.lgguan.linuxdo.plugin.common.Constants;
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
      if (Boolean.getBoolean("linuxdo.showcase")) showcase();
      else if (Boolean.getBoolean("linuxdo.reader.only")) reader();
      else if (System.getProperty("linuxdo.draft.bridge") == null) run(); else runLive();
      report.println("IDE_UI_PASS=true");
      exit = 0;
    } catch (Throwable error) {
      error.printStackTrace();
      if (report != null) { report.println("ERROR=" + error); error.printStackTrace(report); }
    } finally {
      try {
        com.lgguan.linuxdo.plugin.net.IsolatedCefRuntime runtime=com.lgguan.linuxdo.plugin.net.IsolatedCefRuntime.Companion.currentOrNull();
        if(runtime!=null) { runtime.dispose();((CompletableFuture<?>)field(runtime,"termination")).get(8,TimeUnit.SECONDS); }
      } catch(Throwable cleanup) { if(report!=null)report.println("ERROR=Native cleanup: "+cleanup);exit=1; }
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
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(System.getProperty("linuxdo.draft.bridge") == null ? 15 : 90);
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
  private static Object invoke(Object owner, String name, Class<?>[] types, Object... args) throws Exception {
    Method m = owner.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(owner, args);
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
    volatile IOException failure;
    volatile int attempts, saves, deletes;
    volatile String lastReadKey;
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
        if (failure != null) return kotlin.ResultKt.createFailure(failure);
        if (name.startsWith("read")) { lastReadKey=(String)args[0]; return new ForumDraft(sequence, data == null ? null : data.deepCopy()); }
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
      .filter(b -> label.equals(b.getText()) || label.equals(b.getName())).findFirst().orElse(null);
  }
  private static void composerMenu(Window window, String control, String item) {
    button(window, control).doClick();
    JPopupMenu popup = Arrays.stream(MenuSelectionManager.defaultManager().getSelectedPath())
      .map(MenuElement::getComponent).filter(c -> c instanceof JPopupMenu).map(c -> (JPopupMenu)c).findFirst().orElseThrow();
    Arrays.stream(popup.getComponents()).filter(c -> c instanceof JMenuItem).map(c -> (JMenuItem)c)
      .filter(c -> item.equals(c.getText())).findFirst().orElseThrow().doClick();
    MenuSelectionManager.defaultManager().clearSelectedPath();
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
  private static void tagPopupScreenshot(DialogWrapper dialog, String name) throws Exception {
    Rectangle bounds=edt(() -> {
      JComponent content=((com.intellij.openapi.ui.popup.JBPopup)field(dialog,"tagPopup")).getContent();
      return dialog.getWindow().getBounds().union(new Rectangle(content.getLocationOnScreen(),content.getSize()));
    });
    ImageIO.write(new Robot().createScreenCapture(bounds),"png",output.resolve(name+".png").toFile());
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
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(operation.equals("web") ? 90 : 45);
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
    check("REPLY_INITIAL_SIZE_EXPANDED", edt(() -> dialog.getWindow().getWidth()>=1000 && dialog.getWindow().getHeight()>=640));
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
    check("PREVIEW_DEFAULT_COLLAPSED", edt(() -> !(Boolean)field(dialog, "isPreviewVisible") && field((ComposerPreviewView)invoke(dialog,"getPreviewView",new Class<?>[0]),"browser")==null));
    edt(() -> { preview.doClick(); return null; });
    check("PREVIEW_OPENS_ON_DEMAND", edt(() -> (Boolean)field(dialog,"isPreviewVisible") && preview.isSelected()));
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
    failureStore.failure = com.lgguan.linuxdo.plugin.net.HttpFailure.INSTANCE.classify(429, java.util.Map.of(), "Cloudflare verification required");
    edt(() -> { text(failed).append("\n验证前的修改"); return null; }); saveNow(failed);
    String verificationBody = edt(() -> text(failed).getText());
    await("reply verification prompt", () -> status(failed).getText().contains("人机验证"));
    check("REPLY_CLOUDFLARE_429_PROMPTS_VERIFICATION_AND_RETAINS_BODY", edt(() -> text(failed).getText().equals(verificationBody) && ((JButton)field(failed,"draftRetry")).getText().equals("验证后重试")) && failureStore.saves == 1);
    failureStore.failure = null;
    edt(() -> { ((JButton)field(failed,"draftRetry")).doClick(); return null; });
    await("reply verification retry", () -> status(failed).getText().equals("已同步到论坛"));
    check("REPLY_VERIFICATION_RETRY_SYNCS_RETAINED_BODY", verificationBody.equals(failureStore.body()));
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
    browsing();
    topics();
    if(Boolean.getBoolean("linuxdo.preview.live")) {
      String cooked=ForumPreviewService.INSTANCE.cook("[quote=\"neo, post:3, topic:482293\"]\n中文引用\n[/quote]\n\n[details=展开]\n详情\n[/details]\n\n|中文|值|\n|---|---|\n|数据|1|\n\n```kotlin\nval code = \"**原样**\"\n```",SessionEpoch.INSTANCE.getCurrent());
      check("LIVE_READ_ONLY_FORUM_ENGINE_PREVIEW",cooked.contains("class=\"quote") && cooked.contains("<details") && cooked.contains("<table") && cooked.contains("**原样**"));
      report.println("LIVE_PREVIEW_POST_AND_DRAFT_WRITES_BLOCKED=true");
    }
    reader();
  }
  private static JsonObject listTopic(long id, String title) {
    JsonObject topic = new JsonObject(); topic.addProperty("id",id); topic.addProperty("title",title);
    topic.addProperty("category_id",4); topic.addProperty("posts_count",100);
    topic.addProperty("last_posted_at",java.time.Instant.now().minusSeconds(3600).toString()); topic.addProperty("unseen",true);
    topic.add("tags",JsonParser.parseString("[{\"id\":7,\"name\":\"软件开发\",\"slug\":\"dev\"},\"中文\"]"));
    return topic;
  }
  private static void showcaseCapture(Window window,String name) throws Exception {
    edt(() -> {window.setAlwaysOnTop(true);return null;});
    if(!edt(() -> window.isActive() || Arrays.stream(window.getOwnedWindows()).anyMatch(Window::isActive))) {
      Robot focus=new Robot();focus.keyPress(java.awt.event.KeyEvent.VK_ALT);focus.keyRelease(java.awt.event.KeyEvent.VK_ALT);
      edt(() -> {window.toFront();window.requestFocus();return null;});
    }
    await("showcase window foreground",() -> window.isActive() || Arrays.stream(window.getOwnedWindows()).anyMatch(Window::isActive));
    edt(() -> {window.repaint();return null;});
    Thread.sleep(600);
    Rectangle bounds=edt(() -> {Rectangle b=window.getBounds();b.x+=8;b.width-=16;b.height-=8;return b;});
    BufferedImage captured=new Robot().createScreenCapture(bounds);
    Color center=new Color(captured.getRGB(captured.getWidth()/2,captured.getHeight()/2));
    if(center.getRed()+center.getGreen()+center.getBlue()>650) throw new AssertionError("Showcase window is obscured: "+name);
    ImageIO.write(captured,"png",output.resolve(name+".png").toFile());
    report.println("SCREENSHOT="+name+".png");
  }
  private static JFrame showcaseFrame(String title,JComponent content,int width,int height) {
    JFrame frame=new JFrame(title);
    frame.getRootPane().setOpaque(true);frame.getRootPane().setBackground(new Color(0x1e1f22));
    frame.setContentPane(content);content.setOpaque(true);content.setBackground(new Color(0x1e1f22));
    frame.setSize(width,height);frame.setLocation(80,70);frame.setVisible(true);return frame;
  }
  /** Captures production components; all draft/post operations use in-memory fixtures. */
  @SuppressWarnings("unchecked") private static void showcase() throws Exception {
    JWindow backdrop=edt(() -> {JWindow w=new JWindow();w.setBackground(new Color(0x17191c));w.setBounds(GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds());w.setVisible(true);return w;});
    LinuxDoSettingsState state=LinuxDoSettingsState.Companion.getInstance();
    state.setNetworkMode("JAVA_ONLY");
    Field clientField=LinuxDoHttpClient.class.getDeclaredField("client");clientField.setAccessible(true);
    okhttp3.OkHttpClient original=LinuxDoHttpClient.INSTANCE.getClient();
    String[] titles={"分享 IDE 内的论坛阅读与写作体验","如何整理项目中的 Markdown 文档？","Kotlin 协程与后台任务的实践笔记","分享一份开发工具与快捷键清单","本地环境配置和网络诊断经验","一起交流代码审查与单元测试","周末读书：写出更清晰的技术文档"};
    TopicEnvironment topicEnvironment=new TopicEnvironment() {
      public List<Category> categories() {return Arrays.asList(GSON.fromJson("[{\"id\":4,\"name\":\"开发调优\",\"slug\":\"dev\",\"color\":\"0088cc\",\"description\":\"讨论软件开发、工具与技术实践\",\"permission\":1},{\"id\":5,\"name\":\"开发工具\",\"slug\":\"tools\",\"parent_category_id\":4,\"color\":\"ff8800\",\"description\":\"IDE、编辑器与日常开发效率\",\"permission\":1},{\"id\":6,\"name\":\"资源荟萃\",\"slug\":\"resources\",\"color\":\"59a869\",\"description\":\"分享工具、文档和学习资源\",\"permission\":1}]",Category[].class));}
      public TagSearchResultResponse tags(String query,Integer category,List<String> selected) {
        JsonObject result=new JsonObject();JsonArray tags=new JsonArray();
        String[] names={"软件开发","人工智能","ChatGPT","OpenAI","网络安全","开源推广","职场","VPS","快问快答","纯水"};
        for(int i=0;i<names.length;i++) if(query.isEmpty() || names[i].contains(query)) {JsonObject tag=new JsonObject();tag.addProperty("id",1451+i);tag.addProperty("text",names[i]);tag.addProperty("count",40024-i*3761);tags.add(tag);}
        result.add("results",tags);return GSON.fromJson(result,TagSearchResultResponse.class);
      }
    };
    okhttp3.Interceptor fixture=chain -> {
      okhttp3.Request request=chain.request();if(!request.method().equals("GET")) throw new IOException("Showcase blocks all real writes");
      JsonObject response=new JsonObject();String path=request.url().encodedPath();
      if(path.equals("/site.json")) response.add("categories",GSON.toJsonTree(topicEnvironment.categories()));
      else if(path.equals("/categories.json")) {JsonObject categories=new JsonObject();categories.add("categories",GSON.toJsonTree(topicEnvironment.categories()));response.add("category_list",categories);}
      else if(path.equals("/latest.json")) {JsonArray topics=new JsonArray();for(int i=0;i<titles.length;i++){JsonObject topic=listTopic(999999+i,titles[i]);topic.addProperty("posts_count",12+i*5);topics.add(topic);}JsonObject list=new JsonObject();list.add("topics",topics);response.add("topic_list",list);}
      else throw new IOException("Unexpected showcase request: "+path);
      return new okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("sample data").header("Content-Type","application/json").body(okhttp3.ResponseBody.create(response.toString(),okhttp3.MediaType.parse("application/json"))).build();
    };
    clientField.set(null,original.newBuilder().addInterceptor(fixture).build());
    DocViewerPanel reader=edt(() -> new DocViewerPanel(project));
    IssueListPanel list=edt(() -> new IssueListPanel(project,t -> Unit.INSTANCE,() -> Unit.INSTANCE));
    JFrame browse=edt(() -> {JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,reader,list);split.setDividerLocation(800);return showcaseFrame("API Docs · Linux Do",split,1280,860);});
    try {
      await("showcase topic list",() -> ((DefaultListModel<?>)field(list,"topicListModel")).size()==titles.length);
      await("showcase reader",() -> field(reader,"jbCefBrowser")!=null);
      JsonObject detail=new JsonObject();detail.addProperty("id",999999);detail.addProperty("title",titles[0]);detail.addProperty("highest_post_number",12);detail.addProperty("posts_count",12);
      JsonArray posts=new JsonArray(),ids=new JsonArray();
      String[] cooked={"<h2>把阅读与写作放进日常开发流程</h2><p>在 IDE 里浏览话题、查看代码和整理回复，减少窗口之间的切换。</p><h3>阅读体验</h3><ul><li>类别、标签、未读状态和相对活动时间</li><li>关键词搜索、匹配摘要和楼层定位</li><li>跳转后返回原楼层，刷新保留阅读位置</li></ul><pre><code class='language-kotlin'>val topic = forum.readTopic(topicId)\nval floor = topic.lastReadFloor ?: 1\nreader.jumpToFloor(floor)</code></pre><table><thead><tr><th>功能</th><th>使用方式</th></tr></thead><tbody><tr><td>引用回复</td><td>选中文字后插入引用</td></tr><tr><td>草稿同步</td><td>停止输入两秒后保存</td></tr><tr><td>按需预览</td><td>点击工具栏的预览图标</td></tr></tbody></table><details><summary>查看编辑器提示</summary><p>已有正文不会被引用或预览替换。</p></details>","<aside class='quote' data-topic='999999' data-post='1'><div class='title'>community_member</div><blockquote>减少窗口之间的切换。</blockquote></aside><p>标签选择现在可以连续添加，已选标签用 × 移除，板块选中后会立即收起。</p>"};
      for(int i=0;i<cooked.length;i++){JsonObject post=new JsonObject();post.addProperty("id",i+1);post.addProperty("topic_id",999999);post.addProperty("post_number",i+1);post.addProperty("username",i==0?"community_member":"developer");post.addProperty("created_at","2026-10-01T08:00:00Z");post.addProperty("cooked",cooked[i]);posts.add(post);ids.add(i+1);}
      JsonObject stream=new JsonObject();stream.add("posts",posts);stream.add("stream",ids);detail.add("post_stream",stream);
      TopicDetailResponse topic=GSON.fromJson(detail,TopicDetailResponse.class);
      edt(() -> {Field current=DocViewerPanel.class.getDeclaredField("currentTopic");current.setAccessible(true);current.set(reader,topic);invoke(reader,"renderTopic",new Class<?>[]{TopicDetailResponse.class,Integer.class},topic,1);((JList<?>)field(list,"topicList")).setSelectedIndex(0);return null;});
      Thread.sleep(1600);showcaseCapture(browse,"topics");
    } finally {edt(() -> {reader.dispose();list.dispose();browse.dispose();return null;});}
    String markdown="## 分享一份开发工具使用笔记\n\n在 IDE 中整理文档与回复，让日常交流更顺手。\n\n### 这次体验到的功能\n\n- 搜索话题并直接定位匹配楼层\n- 选中文字插入引用，保留已有正文\n- 停止输入两秒后同步论坛草稿\n\n```kotlin\nval draft = forum.readDraft(\"new_topic\")\ncomposer.restore(draft)\n```\n\n| 操作 | 结果 |\n| --- | --- |\n| 选择板块 | 菜单自动收起 |\n| 添加标签 | 可连续选择并移除 |\n\n[details=更多说明]\n预览按需打开，正文与网页草稿可以接续编辑。\n[/details]";
    DialogWrapper topic=openTopic(new Store(topicData("分享一份开发工具与文档整理笔记",markdown,4,"软件开发")),topicEnvironment,"new_topic_showcase");
    await("showcase topic restored",() -> text(topic).isEnabled() && okEnabled(topic));
    edt(() -> {topic.getWindow().setSize(1280,860);topic.getWindow().setLocation(80,70);return null;});
    showcaseCapture(topic.getWindow(),"create-topic");
    edt(() -> {button(topic.getWindow(),"composer-preview").doClick();return null;});
    ComposerPreviewView topicPreview=(ComposerPreviewView)invoke(topic,"getPreviewView",new Class<?>[0]);
    await("showcase topic preview",() -> (Boolean)field(topicPreview,"ready"));
    Thread.sleep(700);showcaseCapture(topic.getWindow(),"create-topic-preview");
    edt(() -> {button(topic.getWindow(),"composer-preview").doClick();((JComboBox<?>)field(topic,"categoryComboBox")).showPopup();return null;});
    showcaseCapture(topic.getWindow(),"category-picker");
    edt(() -> {((JComboBox<?>)field(topic,"categoryComboBox")).hidePopup();button(topic.getWindow(),"composer-tag-picker").doClick();return null;});
    await("showcase tag candidates",() -> ((DefaultListModel<?>)field(topic,"tagSuggestionsModel")).size()==9);
    clickTag(topic,"人工智能");clickTag(topic,"ChatGPT");
    await("showcase chosen tags",() -> okEnabled(topic) && ((JLabel)field(topic,"tagPickerStatus")).getText().contains("已选"));
    showcaseCapture(topic.getWindow(),"tag-picker");
    edt(() -> {((com.intellij.openapi.ui.popup.JBPopup)field(topic,"tagPopup")).cancel();topic.close(DialogWrapper.CANCEL_EXIT_CODE);return null;});
    String replyBody="[quote=\"community_member, post:3, topic:482293\"]\n让大家都能在社区平和地交流学习。\n[/quote]\n\n感谢分享，我也整理了一个简短示例：\n\n```kotlin\nval draft = forum.readDraft(\"topic_482293\")\ncomposer.restore(draft)\n```\n\n已有内容会继续保留，草稿同步到论坛后，可以在网页接着编辑。";
    DialogWrapper reply=open(new Store(data(replyBody,3,"reply")),new Environment(),null);
    await("showcase reply restored",() -> text(reply).isEnabled() && okEnabled(reply));
    edt(() -> {reply.getWindow().setSize(1280,860);reply.getWindow().setLocation(80,70);button(reply.getWindow(),"composer-preview").doClick();return null;});
    ComposerPreviewView replyPreview=(ComposerPreviewView)invoke(reply,"getPreviewView",new Class<?>[0]);
    await("showcase reply preview",() -> (Boolean)field(replyPreview,"ready"));
    Thread.sleep(700);showcaseCapture(reply.getWindow(),"reply");
    edt(() -> {reply.close(DialogWrapper.CANCEL_EXIT_CODE);return null;});
    com.lgguan.linuxdo.plugin.config.LinuxDoSettingsPanel settings=edt(() -> new com.lgguan.linuxdo.plugin.config.LinuxDoSettingsPanel());
    JFrame preferences=edt(() -> {settings.resetFrom(state);return showcaseFrame("Settings · Tools · Linux Do (API Docs)",new JScrollPane(settings.getMainPanel()),1280,930);});
    showcaseCapture(preferences,"settings");edt(() -> {preferences.dispose();settings.dispose();return null;});
    DialogWrapper login=edt(() -> {LoginAuthDialog d=new LoginAuthDialog(project,null);d.setModal(false);((JTabbedPane)field(d,"tabs")).setSelectedIndex(1);d.show();d.getWindow().setSize(1280,860);d.getWindow().setLocation(80,70);return d;});
    showcaseCapture(login.getWindow(),"login");
    edt(() -> {login.close(DialogWrapper.CANCEL_EXIT_CODE);backdrop.dispose();return null;});
    clientField.set(null,original);
    check("SHOWCASE_USES_CURRENT_PRODUCTION_UI",true);
    report.println("REAL_POST_AND_DRAFT_WRITES=0");
  }
  private static final class ListFixture implements okhttp3.Interceptor {
    volatile boolean refresh, failList, failNextSearch;
    volatile String heldQuery;
    volatile CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1), finished=new CountDownLatch(1);
    final List<String> requests=new CopyOnWriteArrayList<>();
    final List<okhttp3.HttpUrl> tagRequests=new CopyOnWriteArrayList<>();
    int writes;
    void hold(String query) { heldQuery=query;entered=new CountDownLatch(1);release=new CountDownLatch(1);finished=new CountDownLatch(1); }
    public okhttp3.Response intercept(okhttp3.Interceptor.Chain chain) throws IOException {
      okhttp3.Request request=chain.request();
      if(!request.method().equals("GET")) {writes++;throw new IOException("Read-only isolated fixture rejects all writes");}
      String path=request.url().encodedPath(), query=request.url().queryParameter("q");
      String page=request.url().queryParameter("page"); int number=page==null?0:Integer.parseInt(page);
      requests.add(path+":"+(query==null?"":query)+":"+number);
      String body="{}"; int code=200;
      if(path.equals("/tags/filter/search.json")) {
        tagRequests.add(request.url());
        String limit=request.url().queryParameter("limit");
        if(limit!=null && (!limit.matches("[0-9]+") || Integer.parseInt(limit)>5)) {code=400;body="{\"errors\":[\"Limit 无效\"]}";}
        else body="{\"results\":[{\"id\":1451,\"text\":\"软件开发\",\"name\":\"软件开发\",\"count\":123}]}";
      }
      else if(path.equals("/categories.json")) body="{\"category_list\":{\"categories\":[{\"id\":4,\"name\":\"开发调优\",\"slug\":\"dev\",\"permission\":1}]}}";
      else if(path.equals("/session/current.json")) body="{\"current_user\":{\"id\":999997,\"username\":\"fixture_login\"}}";
      else if(path.equals("/latest.json") || path.equals("/top.json")) {
        if(failList) code=500;
        else {
          JsonArray topics=new JsonArray();
          if(path.equals("/top.json")) topics.add(listTopic(200,"切换条件后的话题"));
          else {
            if(refresh && number==0) topics.add(listTopic(10001,"刷新新增的话题"));
            for(int i=number==0?1:40;i<=(number==0?40:60);i++) topics.add(listTopic(i,(refresh?"更新标题 ":"中文话题 ")+i));
          }
          JsonObject list=new JsonObject();list.add("topics",topics);list.addProperty("more_topics_url","/latest.json?page="+(number+1));
          JsonObject response=new JsonObject();response.add("topic_list",list);body=response.toString();
        }
      } else if(path.equals("/search.json")) {
        if(query!=null && query.equals(heldQuery)) {
          entered.countDown();
          // Deliberately deliver an obsolete response even if the caller cancelled its Future.
          boolean interrupted=false;
          for(;;) {try {if(release.await(15,TimeUnit.SECONDS)) break;throw new IOException("Isolated hold timed out");}
            catch(InterruptedException e) {interrupted=true;}}
          if(interrupted) Thread.interrupted();
          finished.countDown();
        }
        if(number==2 && failNextSearch) {failNextSearch=false;code=500;}
        else {
          JsonArray topics=new JsonArray(), posts=new JsonArray();
          long first="中文".equals(query)?11:"新查询".equals(query)?75:90;
          topics.add(listTopic(first,"匹配话题 "+query));
          if("中文".equals(query)) topics.add(listTopic(number==1?12:13,"追加匹配"));
          JsonObject post=new JsonObject();post.addProperty("id",90000+first);post.addProperty("topic_id",first);
          post.addProperty("post_number",number==1?42:55);post.addProperty("blurb",number==1?"首个 <b>中文</b> 匹配摘要":"下一页匹配摘要");posts.add(post);
          JsonObject response=new JsonObject();response.add("topics",topics);response.add("posts",posts);
          JsonObject grouped=new JsonObject();grouped.addProperty("more_full_page_results",number==1);response.add("grouped_search_result",grouped);body=response.toString();
        }
      } else if(path.equals("/t/777.json")) body=listTopic(777,"精确编号话题").toString();
      else throw new IOException("Unexpected isolated list request: "+path);
      return new okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(code).message("isolated fixture")
        .header("Content-Type","application/json").body(okhttp3.ResponseBody.create(body,okhttp3.MediaType.parse("application/json"))).build();
    }
  }
  @SuppressWarnings("unchecked") private static void browsing() throws Exception {
    LinuxDoSettingsState settings=LinuxDoSettingsState.Companion.getInstance();String oldMode=settings.getNetworkMode();
    Field clientField=LinuxDoHttpClient.class.getDeclaredField("client");clientField.setAccessible(true);
    okhttp3.OkHttpClient original=LinuxDoHttpClient.INSTANCE.getClient();
    ListFixture fixture=new ListFixture();
    LinuxDoAuthService auth=LinuxDoAuthService.Companion.getInstance();
    Field credentialsField=LinuxDoAuthService.class.getDeclaredField("credentials");credentialsField.setAccessible(true);
    Field userField=LinuxDoAuthService.class.getDeclaredField("confirmedUser");userField.setAccessible(true);
    Field versionField=LinuxDoAuthService.class.getDeclaredField("confirmedVersion");versionField.setAccessible(true);
    Object previousCredentials=credentialsField.get(auth),previousUser=userField.get(auth),previousVersion=versionField.get(auth);
    com.lgguan.linuxdo.plugin.net.PersistentCookieJar listCredentials=new com.lgguan.linuxdo.plugin.net.PersistentCookieJar(false);
    java.util.concurrent.atomic.AtomicReference<Topic> opened=new java.util.concurrent.atomic.AtomicReference<>();
    IssueListPanel[] holder=new IssueListPanel[1]; JFrame[] window=new JFrame[1];
    try {
      settings.setNetworkMode("JAVA_ONLY");
      credentialsField.set(auth,listCredentials);userField.set(auth,null);
      clientField.set(null,original.newBuilder().addInterceptor(fixture).build());
      edt(() -> {
        holder[0]=new IssueListPanel(project,topic -> {opened.set(topic);return Unit.INSTANCE;},() -> Unit.INSTANCE);
        window[0]=new JFrame("列表与搜索 · 隔离论坛数据");window[0].setContentPane(holder[0]);window[0].setSize(700,540);
        window[0].setLocation(150,120);window[0].setVisible(true);return null;
      });
      IssueListPanel panel=holder[0]; JFrame frame=window[0];
      DefaultListModel<Topic> model=(DefaultListModel<Topic>)field(panel,"topicListModel");
      JList<Topic> list=(JList<Topic>)field(panel,"topicList");JScrollPane scroll=(JScrollPane)field(panel,"listScrollPane");
      JButton more=(JButton)field(panel,"loadMoreButton"),retry=(JButton)field(panel,"retryButton");
      await("IDE list first page",() -> model.size()==40 && !(Boolean)field(panel,"isLoading"));
      await("IDE category labels ready",() -> LinuxDoTopicService.Companion.getInstance().getCategoriesAreCurrent());
      long beforeLogin=fixture.requests.stream().filter(r->r.equals("/latest.json::0")).count();
      edt(()->{listCredentials.injectCookie("_t","isolated-list-login","linux.do");auth.credentialsPending();return null;});
      Thread.sleep(300);
      check("IDE_PENDING_LOGIN_INVALIDATES_WITHOUT_LIST_REFRESH",model.isEmpty()&&fixture.requests.stream().filter(r->r.equals("/latest.json::0")).count()==beforeLogin);
      auth.refreshCurrentUser(true,loggedIn->{try {invoke(panel,"refreshAfterAuthentication",new Class<?>[0]);}catch(Exception e){throw new RuntimeException(e);}return Unit.INSTANCE;});
      await("verified login refresh",()->auth.isLoggedIn()&&model.size()==40&&!(Boolean)field(panel,"isLoading"));Thread.sleep(300);
      check("IDE_LOGIN_LIST_REFRESHES_ONCE",fixture.requests.stream().filter(r->r.equals("/latest.json::0")).count()==beforeLogin+1);
      java.util.concurrent.atomic.AtomicBoolean verifiedAgain=new java.util.concurrent.atomic.AtomicBoolean();
      auth.refreshCurrentUser(true,loggedIn->{verifiedAgain.set(true);return Unit.INSTANCE;});await("same account reverified",verifiedAgain::get);Thread.sleep(300);
      check("IDE_SAME_ACCOUNT_VERIFICATION_DOES_NOT_REFRESH_LIST",fixture.requests.stream().filter(r->r.equals("/latest.json::0")).count()==beforeLogin+1);
      edt(()->{listCredentials.clearAll();auth.credentialsPending();invoke(panel,"refreshAfterAuthentication",new Class<?>[0]);invoke(panel,"refreshAfterAuthentication",new Class<?>[0]);return null;});
      await("guest verification refresh",()->model.size()==40&&!(Boolean)field(panel,"isLoading"));Thread.sleep(300);
      check("IDE_GUEST_VERIFICATION_LIST_REFRESHES_ONCE",fixture.requests.stream().filter(r->r.equals("/latest.json::0")).count()==beforeLogin+2);
      check("IDE_LIST_PARSES_TAG_OBJECTS",model.get(0).getTags().get(0).getName().equals("软件开发"));
      check("IDE_LIST_DISPLAYS_TAGS_UNREAD_CATEGORY_AND_TIME",edt(() -> {
        Component rendered=list.getCellRenderer().getListCellRendererComponent(list,model.get(0),0,false,false);
        String meta=components((Container)rendered).stream().filter(c -> c instanceof JLabel).map(c -> ((JLabel)c).getText())
          .filter(t -> t!=null && t.contains("replies")).findFirst().orElseThrow();
        return meta.contains("#软件开发") && meta.contains("#中文") && meta.contains("新话题") && meta.contains("小时前") && !meta.startsWith("General");
      }));
      check("IDE_LIST_TOOLTIP_RETAINS_TITLE_AND_ABSOLUTE_TIME",edt(() -> {
        Rectangle row=list.getCellBounds(0,0);
        String tip=list.getToolTipText(new java.awt.event.MouseEvent(list,java.awt.event.MouseEvent.MOUSE_MOVED,System.currentTimeMillis(),0,15,row.y+row.height/2,0,false));
        return tip.contains(model.get(0).getTitle()) && tip.contains(model.get(0).getLastPostedAt());
      }));
      ImageIO.write(new Robot().createScreenCapture(edt(frame::getBounds)),"png",output.resolve("browsing-list.png").toFile());
      edt(() -> {more.doClick();return null;});await("IDE list append",() -> model.size()==60);
      edt(() -> {list.setSelectedIndex(33);list.ensureIndexIsVisible(31);Rectangle row=list.getCellBounds(31,31);scroll.getViewport().setViewPosition(new Point(0,row.y+5));return null;});
      final long anchor=edt(() -> model.get(list.getFirstVisibleIndex()).getId());
      final int offset=edt(() -> scroll.getViewport().getViewPosition().y-list.getCellBounds(list.getFirstVisibleIndex(),list.getFirstVisibleIndex()).y);
      final long selected=edt(() -> list.getSelectedValue().getId());
      fixture.refresh=true;edt(() -> {panel.refreshList();return null;});
      await("IDE refresh merges by ID",() -> model.size()==61 && model.get(0).getId()==10001);
      check("IDE_REFRESH_RETAINS_SELECTION_AND_SCROLL",edt(() -> list.getSelectedValue().getId()==selected && model.get(list.getFirstVisibleIndex()).getId()==anchor && scroll.getViewport().getViewPosition().y-list.getCellBounds(list.getFirstVisibleIndex(),list.getFirstVisibleIndex()).y==offset));
      check("IDE_REFRESH_RETAINS_LOADED_PAGINATION",(Integer)field(panel,"currentPage")==1 && model.get(1).getTitle().startsWith("更新标题"));
      fixture.failList=true;
      edt(() -> {((JComboBox<?>)field(panel,"filterComboBox")).setSelectedItem(Constants.TopicFilter.TOP);return null;});
      await("IDE failed filter retains content",retry::isVisible);
      check("IDE_FAILED_FILTER_RETAINS_CONTENT",model.size()==61 && list.getSelectedValue().getId()==selected);
      fixture.failList=false;edt(() -> {retry.doClick();return null;});await("IDE filter retry",() -> model.size()==1 && model.get(0).getId()==200);
      check("IDE_FILTER_SUCCESS_RESETS_POSITION_AND_PAGE",edt(() -> list.getSelectedIndex()==-1 && scroll.getViewport().getViewPosition().y==0 && (Integer)field(panel,"currentPage")==0));
      edt(() -> {panel.search("中文");return null;});await("IDE keyword search",() -> model.size()==2 && model.get(0).getId()==11);
      check("IDE_SEARCH_FIRST_MATCH_SUMMARY",model.get(0).getSearchPostNumber()==42 && model.get(0).getSearchBlurb().contains("首个"));
      fixture.failNextSearch=true;edt(() -> {more.doClick();return null;});await("IDE search page failure",retry::isVisible);
      check("IDE_SEARCH_FAILURE_RETAINS_PAGE",model.size()==2 && (Integer)field(panel,"currentPage")==1);
      edt(() -> {retry.doClick();return null;});await("IDE search retries page two",() -> model.size()==3 && !(Boolean)field(panel,"isLoading"));
      check("IDE_SEARCH_RETRY_SAME_PAGE_AND_DEDUP",Collections.frequency(fixture.requests,"/search.json:中文:2")==2 && model.get(0).getSearchPostNumber()==42 && !more.isEnabled());
      // Dispatch through the production list listener without depending on desktop focus.
      // A separate IDEA or editor window can receive a Robot click while this suite runs.
      edt(() -> {
        list.ensureIndexIsVisible(0);
        Rectangle row=list.getCellBounds(0,0);
        list.dispatchEvent(new java.awt.event.MouseEvent(list,java.awt.event.MouseEvent.MOUSE_CLICKED,
          System.currentTimeMillis(),0,row.x+40,row.y+row.height/2,1,false,java.awt.event.MouseEvent.BUTTON1));
        return null;
      });
      await("IDE search result opens",() -> opened.get()!=null);
      check("IDE_SEARCH_CLICK_CARRIES_MATCHED_FLOOR",opened.get().getId()==11 && opened.get().getSearchPostNumber()==42);
      fixture.hold("旧查询");edt(() -> {panel.search("旧查询");return null;});if(!fixture.entered.await(15,TimeUnit.SECONDS)) throw new AssertionError("Old query did not start");
      edt(() -> {panel.search("新查询");return null;});await("IDE new query supersedes old",() -> model.size()==1 && model.get(0).getId()==75);
      fixture.release.countDown();fixture.finished.await(15,TimeUnit.SECONDS);Thread.sleep(200);
      check("IDE_QUERY_CHANGE_DISCARDS_OLD_RESPONSE",model.get(0).getId()==75 && !(Boolean)field(panel,"isLoading"));
      edt(() -> {panel.search("#777");return null;});await("IDE exact ID search",() -> model.size()==1 && model.get(0).getId()==777);
      check("IDE_EXACT_ID_HAS_NO_TEXT_PAGINATION",!more.isEnabled() && fixture.requests.contains("/t/777.json::0") && fixture.requests.stream().noneMatch(r -> r.startsWith("/search.json:#777:")));
      fixture.hold("账号查询");edt(() -> {panel.search("账号查询");return null;});fixture.entered.await(15,TimeUnit.SECONDS);
      SessionEpoch.INSTANCE.advance();fixture.release.countDown();fixture.finished.await(15,TimeUnit.SECONDS);Thread.sleep(250);
      check("IDE_LIST_SESSION_CHANGE_DISCARDS_OLD_RESPONSE",model.get(0).getId()==777);
      fixture.hold("关闭查询");edt(() -> {panel.search("关闭查询");return null;});fixture.entered.await(15,TimeUnit.SECONDS);
      edt(() -> {panel.dispose();frame.dispose();return null;});fixture.release.countDown();fixture.finished.await(15,TimeUnit.SECONDS);Thread.sleep(250);
      check("IDE_LIST_DISPOSE_DISCARDS_OLD_RESPONSE",model.get(0).getId()==777);
      Method tagSearch=Arrays.stream(DiscourseApiClient.class.getDeclaredMethods()).filter(m -> m.getName().startsWith("searchComposerTags")).findFirst().orElseThrow();
      Object tags=tagSearch.invoke(DiscourseApiClient.INSTANCE,"",4,List.of());kotlin.ResultKt.throwOnFailure(tags);
      check("IDE_PRODUCTION_TAG_DEFAULT_LIMIT_ACCEPTED",tags instanceof TagSearchResultResponse && ((TagSearchResultResponse)tags).getResults().get(0).getText().equals("软件开发") && fixture.tagRequests.get(0).queryParameter("limit")==null);
      tags=tagSearch.invoke(DiscourseApiClient.INSTANCE,"中文 & C++",5,List.of("1451","129"));kotlin.ResultKt.throwOnFailure(tags);
      okhttp3.HttpUrl tagRequest=fixture.tagRequests.get(1);
      check("IDE_PRODUCTION_TAG_SEARCH_PRESERVES_CONTEXT",tags instanceof TagSearchResultResponse && tagRequest.queryParameter("q").equals("中文 & C++") && tagRequest.queryParameter("categoryId").equals("5") && tagRequest.queryParameterValues("selected_tag_ids[]").equals(List.of("1451","129")));
      check("IDE_BROWSING_FIXTURE_NO_WRITES",fixture.writes==0);
    } finally {
      fixture.release.countDown();
      edt(() -> {if(holder[0]!=null && !(Boolean)field(holder[0],"disposed")) holder[0].dispose();if(window[0]!=null) window[0].dispose();return null;});
      clientField.set(null,original);settings.setNetworkMode(oldMode);
      credentialsField.set(auth,previousCredentials);userField.set(auth,previousUser);versionField.set(auth,previousVersion);
    }
  }
  private static JsonObject topicData(String title, String body, int category, String... tags) {
    JsonObject data = new JsonObject(); data.addProperty("action", "createTopic"); data.addProperty("archetypeId", "regular");
    data.addProperty("title", title); data.addProperty("reply", body); data.addProperty("categoryId", category);
    JsonArray array = new JsonArray(); for (String tag : tags) array.add(tag); data.add("tags", array);
    data.addProperty("unknown", "keep"); return data;
  }
  @SuppressWarnings("unchecked") private static <T,E extends Throwable> T raise(Throwable error) throws E { throw (E)error; }
  private static class TopicEnvironment implements TopicComposerEnvironment {
    final List<Function0<Unit>> listeners = new CopyOnWriteArrayList<>();
    volatile boolean uncertain, queued, tagFailure;
    volatile IOException tagError;
    volatile int extraTags;
    volatile int sends;
    public boolean getLoggedIn() { return true; }
    public void authListener(Disposable owner, Function0<Unit> changed) { listeners.add(changed); Disposer.register(owner, () -> listeners.remove(changed)); }
    public List<Category> categories() {
      return Arrays.asList(GSON.fromJson("[{\"id\":4,\"name\":\"开发调优\",\"slug\":\"dev\",\"color\":\"0088cc\",\"permission\":1},{\"id\":5,\"name\":\"测试版块\",\"slug\":\"test\",\"color\":\"ff8800\",\"parent_category_id\":4,\"description\":\"<p>编辑器交流</p>\",\"permission\":1},{\"id\":49,\"name\":\"公告\",\"slug\":\"notice\"}]", Category[].class));
    }
    public ComposerCapabilities capabilities() { return new ComposerCapabilities(6,255,20,16,64000,8,49,false,true); }
    public TagSearchResultResponse tags(String query, Integer category, List<String> selected) {
      if(tagError!=null) return IdeUiSmoke.<TagSearchResultResponse,RuntimeException>raise(tagError);
      if(tagFailure) return IdeUiSmoke.<TagSearchResultResponse,RuntimeException>raise(new IOException("isolated tag search failure"));
      JsonObject result = new JsonObject(); JsonArray items = new JsonArray(); JsonObject tag = new JsonObject();
      if(query.isEmpty()) {
        JsonObject defaults=JsonParser.parseString("{\"results\":[{\"id\":\"1451\",\"text\":\"软件开发\",\"count\":123},{\"id\":\"129\",\"text\":\"纯水\",\"count\":456},{\"id\":\"130\",\"text\":\"禁用标签\",\"disabled\":true,\"title\":\"不能用于此类别\"}]}").getAsJsonObject();
        for(int i=0;i<extraTags;i++) {JsonObject item=new JsonObject();item.addProperty("id",10000+i);item.addProperty("text","推荐标签"+i);item.addProperty("count",i+100);defaults.getAsJsonArray("results").add(item);}
        return GSON.fromJson(defaults,TagSearchResultResponse.class);
      }
      if(query.equals("无匹配")) return GSON.fromJson("{\"results\":[]}",TagSearchResultResponse.class);
      tag.addProperty("id", query.equals("软件开发") ? "1451" : "129"); tag.addProperty("text", query); tag.addProperty("name", query);
      tag.addProperty("disabled", query.equals("禁用标签")); tag.addProperty("title", "不能用于此类别"); items.add(tag); result.add("results", items);
      return GSON.fromJson(result, TagSearchResultResponse.class);
    }
    public PublishOutcome publish(TopicDraftContent content, String key, long version) {
      sends++;
      if (uncertain) return IdeUiSmoke.<PublishOutcome,RuntimeException>raise(PublishOutcome.Companion.failure(new HttpStatusException(503)));
      if (queued) return new PublishOutcome.Queued("已提交审核");
      return new PublishOutcome.Published(GSON.fromJson("{\"id\":70001,\"topic_id\":70002,\"username\":\"fixture\"}", Post.class));
    }
  }
  private static DialogWrapper openTopic(Store store, TopicEnvironment environment, String key) throws Exception {
    return openTopic(store,environment,key,false);
  }
  private static DialogWrapper openTopic(Store store, TopicEnvironment environment, String key, boolean directEntry) throws Exception {
    ForumDraftSession session = new ForumDraftSession(key, SessionEpoch.INSTANCE.getCurrent(), store.transport,
      (Function1<Long,Unit>)version -> { SessionEpoch.INSTANCE.requireCurrent(version); return Unit.INSTANCE; },
      (Function1<ForumDraft,Boolean>)draft -> ForumDraftSessionKt.isTopicDraft(draft));
    return edt(() -> {
      if(directEntry) {
        Method entry=Arrays.stream(CreateTopicDialog.Companion.getClass().getDeclaredMethods()).filter(m -> m.getName().startsWith("openForTesting")).findFirst().orElseThrow();
        return (DialogWrapper)entry.invoke(CreateTopicDialog.Companion,project,session,environment);
      }
      Constructor<?> constructor = Arrays.stream(CreateTopicDialog.class.getDeclaredConstructors())
        .filter(c -> c.getParameterCount()==5 && c.getParameterTypes()[3]==ForumDraftSession.class).findFirst().orElseThrow();
      constructor.setAccessible(true);
      DialogWrapper dialog = (DialogWrapper)constructor.newInstance(project,null,null,session,environment);
      dialog.show(); dialog.getWindow().setLocation(120,100); return dialog;
    });
  }
  private static void previewChecks(DialogWrapper dialog) throws Exception {
    ComposerPreviewView preview=(ComposerPreviewView)edt(() -> invoke(dialog,"getPreviewView",new Class<?>[0]));
    check("TOPIC_PREVIEW_DEFAULT_COLLAPSED", edt(() -> !(Boolean)field(dialog,"isPreviewVisible") && field(preview,"browser")==null));
    edt(() -> {button(dialog.getWindow(),"composer-preview").doClick();return null;});
    await("native composer preview", () -> field(preview,"browser")!=null && (Boolean)field(preview,"ready"));
    LinuxDoBrowser browser=(LinuxDoBrowser)field(preview,"browser");
    BlockingQueue<String> replies=new LinkedBlockingQueue<>();
    LinuxDoJSQuery query=LinuxDoJSQuery.Companion.create(browser,true);
    query.addHandler(value -> {if(value.startsWith("probe:"))replies.add(value.substring(6));return null;});
    String source="[quote=\"neo, post:5, topic:2909396\"]\n中文 **引用**\n[/quote]\n\n[details=展开]\n详情\n[/details]\n\n[spoiler]隐藏内容[/spoiler]\n\n|中文|值|\n|---|---|\n|数据|1|\n\n```kotlin\nval code = \"**原样**\"\n```";
    try {
      browser.getCefBrowser().reload(); Thread.sleep(500);
      edt(() -> {preview.local(source);return null;}); Thread.sleep(450);
      check("IDE_AST_PREVIEW_COMMON_CONTENT",evaluate(browser,query,replies,"({ok:document.querySelectorAll('aside.quote').length===1 && document.querySelectorAll('details').length===1 && document.querySelectorAll('.forum-table table').length===1 && document.querySelector('pre code').textContent.includes('**原样**')})").get("ok").getAsBoolean());
      check("IDE_PREVIEW_SPOILER_REVEALS",evaluate(browser,query,replies,"(()=>{var el=document.querySelector('.spoiler');el.click();return {ok:el.classList.contains('revealed')}})()").get("ok").getAsBoolean());
      check("IDE_PREVIEW_DETAILS_EXPANDS",evaluate(browser,query,replies,"(()=>{document.querySelector('summary').click();return {ok:document.querySelector('details').open}})()").get("ok").getAsBoolean());
      edt(() -> {preview.local("长正文\n\n".repeat(150));return null;});Thread.sleep(450);
      evaluate(browser,query,replies,"(()=>{window.scrollTo(0,600);return {ok:true}})()");
      edt(() -> {preview.local("长正文\n\n".repeat(150)+"新内容");return null;});Thread.sleep(450);
      check("IDE_PREVIEW_PRESERVES_SCROLL",evaluate(browser,query,replies,"({ok:window.scrollY>=590})").get("ok").getAsBoolean());
    } finally {query.dispose();edt(() -> {preview.local(text(dialog).getText());return null;});}
  }
  private static void uploads(DialogWrapper dialog) throws Exception {
    ComposerImageUpload upload=(ComposerImageUpload)edt(() -> invoke(dialog,"getImageUpload",new Class<?>[0]));
    CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    final boolean[] fail={false};
    Field transport=ComposerImageUpload.class.getDeclaredField("transport");transport.setAccessible(true);
    transport.set(upload,(Function4<byte[],String,String,Long,UploadResponse>)(bytes,name,type,version)->{
      entered.countDown();try {if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test upload timeout");}catch(InterruptedException e){throw new RuntimeException(e);}
      if(fail[0])throw new IllegalStateException("isolated upload failure");
      return GSON.fromJson("{\"id\":1,\"url\":\"https://linux.do/uploads/fixture.png\",\"short_url\":\"upload://fixture.png\"}",UploadResponse.class);
    });
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",bytes);
    String original=text(dialog).getText();
    edt(() -> {text(dialog).setCaretPosition(2);upload.bytes(bytes.toByteArray(),"fixture.png");text(dialog).setCaretPosition(text(dialog).getDocument().getLength());return null;});
    check("IDE_UPLOAD_PENDING_BLOCKS_SEND",entered.await(5,TimeUnit.SECONDS) && !okEnabled(dialog));
    release.countDown();await("mock upload inserted",()->text(dialog).getText().contains("upload://fixture.png") && upload.getPending()==0);
    check("IDE_UPLOAD_USES_CAPTURED_POSITION",text(dialog).getText().startsWith(original.substring(0,2)+"\n![fixture.png](upload://fixture.png)\n"));
    fail[0]=true;edt(() -> {upload.bytes(bytes.toByteArray(),"failed.png");return null;});
    await("mock upload failure",()->upload.getFailures()==1);
    check("IDE_UPLOAD_FAILURE_RETAINS_BODY_AND_BLOCKS_SEND",text(dialog).getText().contains("upload://fixture.png") && !okEnabled(dialog));
    fail[0]=false;edt(() -> {upload.retry();return null;});await("mock retry complete",()->upload.getPending()==0 && upload.getFailures()==0);
    check("IDE_UPLOAD_RETRY_INSERTS_ONCE",text(dialog).getText().split("upload://fixture.png",-1).length==3);
  }
  private static void topicEntryChecks(TopicEnvironment environment) throws Exception {
    Store empty=new Store(null);
    DialogWrapper blank=openTopic(empty,environment,"new_topic",true);
    await("direct empty topic entry", () -> text(blank).isEnabled() && (Boolean)field(blank,"categoriesReady"));
    check("TOPIC_DIRECT_ENTRY_EMPTY_NO_CHOOSER",empty.lastReadKey.equals("new_topic") && text(blank).getText().isEmpty());
    check("TOPIC_INITIAL_SIZE_EXPANDED",edt(() -> blank.getWindow().getWidth()>=1040 && blank.getWindow().getHeight()>=700));
    edt(() -> {text(blank).append(BODY);return null;});
    DialogWrapper same=openTopic(empty,environment,"new_topic",true);
    check("TOPIC_ENTRY_REUSES_ACTIVE_EDITOR",same==blank && text(same).getText().equals(BODY));
    saveNow(blank);await("canonical draft save",() -> BODY.equals(empty.body()));
    edt(() -> {blank.close(DialogWrapper.CANCEL_EXIT_CODE);return null;});
    DialogWrapper restored=openTopic(empty,environment,"new_topic",true);
    await("canonical direct restore",() -> text(restored).isEnabled() && text(restored).getText().equals(BODY));
    check("TOPIC_ENTRY_AUTOMATICALLY_RESTORES_SINGLE_DRAFT",empty.lastReadKey.equals("new_topic") && empty.saves==1);
    edt(() -> {restored.close(DialogWrapper.CANCEL_EXIT_CODE);return null;});
  }
  private static void clickTag(DialogWrapper dialog,String name) throws Exception {
    edt(() -> {
      JList<?> list=(JList<?>)field(dialog,"tagSuggestionsList");
      int index=-1;for(int i=0;i<list.getModel().getSize();i++) if(((TagItem)list.getModel().getElementAt(i)).getText().equals(name)) index=i;
      if(index<0)throw new AssertionError("Missing tag "+name);
      Rectangle cell=list.getCellBounds(index,index);
      list.dispatchEvent(new java.awt.event.MouseEvent(list,java.awt.event.MouseEvent.MOUSE_CLICKED,System.currentTimeMillis(),0,cell.x+8,cell.y+cell.height/2,1,false,java.awt.event.MouseEvent.BUTTON1));
      return null;
    });
  }
  private static void topicPickerChecks(DialogWrapper dialog,TopicEnvironment environment,JComboBox<?> categories) throws Exception {
    edt(() -> {categories.showPopup();return null;});
    JTextField search=(JTextField)field(categories,"searchField");
    DefaultListModel<?> results=(DefaultListModel<?>)field(categories,"results");
    edt(() -> {search.setText("开发 编辑器");return null;});
    check("CATEGORY_SEARCH_PARENT_AND_DESCRIPTION",edt(() -> results.size()==1 && results.get(0).toString().equals("测试版块")));
    screenshot(dialog,"topic-category-search");
    edt(() -> {search.setText("test");return null;});
    edt(() -> {
      JList<?> list=(JList<?>)field(categories,"resultList");Rectangle cell=list.getCellBounds(0,0);
      list.dispatchEvent(new java.awt.event.MouseEvent(list,java.awt.event.MouseEvent.MOUSE_RELEASED,
        System.currentTimeMillis(),0,cell.x+20,cell.y+cell.height/2,1,false,java.awt.event.MouseEvent.BUTTON1));
      return null;
    });
    await("category mouse collapses popup",() -> !categories.isPopupVisible());
    check("CATEGORY_SEARCH_SLUG_SELECTS_ID",edt(() -> categories.getSelectedItem().toString().equals("测试版块") && !categories.isPopupVisible()));
    edt(() -> {categories.showPopup();search.setText("无匹配");return null;});
    check("CATEGORY_EMPTY_SEARCH_STAYS_OPEN",edt(() -> results.isEmpty() && categories.isPopupVisible() && ((JLabel)field(categories,"emptyLabel")).isVisible()));
    edt(() -> {categories.hidePopup();categories.setSelectedIndex(1);button(dialog.getWindow(),"composer-tag-picker").doClick();return null;});
    await("tag choices",() -> ((DefaultListModel<?>)field(dialog,"tagSuggestionsModel")).size()==2);
    clickTag(dialog,"纯水");
    check("TAG_MULTISELECT_KEEPS_POPUP_OPEN",edt(() -> ((com.intellij.openapi.ui.popup.JBPopup)field(dialog,"tagPopup")).isVisible() && ((Set<?>)field(dialog,"selectedTags")).containsAll(Arrays.asList("软件开发","纯水"))));
    clickTag(dialog,"禁用标签");
    check("TAG_DISABLED_ROW_EXPLAINS_AND_REJECTS",edt(() -> !((Set<?>)field(dialog,"selectedTags")).contains("禁用标签") && ((JLabel)field(dialog,"tagPickerStatus")).getText().contains("不能用于")));
    screenshot(dialog,"topic-tag-multiselect");
    choose("composer-remove-tag-软件开发");
    check("TAG_SELECTED_CHIP_REMOVES",edt(() -> !((Set<?>)field(dialog,"selectedTags")).contains("软件开发")));
    await("removed tag offered again",() -> ((DefaultListModel<?>)field(dialog,"tagSuggestionsModel")).size()==2);
    clickTag(dialog,"软件开发");
    choose("composer-remove-tag-纯水");
    await("tag choices restored",() -> ((DefaultListModel<?>)field(dialog,"tagSuggestionsModel")).size()==2);
    check("TAG_WEB_LAYOUT_SELECTED_CHIPS_ABOVE_SEARCH",edt(() -> {
      JPanel chips=(JPanel)field(dialog,"tagsPanel");JTextField input=(JTextField)field(dialog,"tagInputField");JList<?> list=(JList<?>)field(dialog,"tagSuggestionsList");
      return chips.isShowing() && list.getLocationOnScreen().y<chips.getLocationOnScreen().y && chips.getLocationOnScreen().y<input.getLocationOnScreen().y && ((JButton)field(dialog,"tagSelectButton")).getText().contains("软件开发");
    }));
    JTextField tags=(JTextField)field(dialog,"tagInputField");
    edt(() -> {tags.setText("无匹配");return null;});
    await("empty tag search",() -> ((JLabel)field(dialog,"tagPickerStatus")).getText().contains("没有匹配"));
    check("TAG_EMPTY_SEARCH_STAYS_OPEN",edt(() -> ((com.intellij.openapi.ui.popup.JBPopup)field(dialog,"tagPopup")).isVisible()));
    environment.tagFailure=true;
    edt(() -> {tags.setText("软件");return null;});
    await("tag failure shown",() -> ((JLabel)field(dialog,"tagPickerStatus")).getText().contains("读取失败"));
    check("TAG_SEARCH_FAILURE_RETAINS_SELECTION",edt(() -> ((Set<?>)field(dialog,"selectedTags")).contains("软件开发")));
    environment.tagFailure=false;
    environment.tagError=com.lgguan.linuxdo.plugin.net.HttpFailure.INSTANCE.classify(429,java.util.Map.of(),"Cloudflare verification required");
    choose("重试");await("tag verification prompt",() -> ((JLabel)field(dialog,"tagPickerStatus")).getText().contains("人机验证"));
    check("TAG_CLOUDFLARE_429_REQUESTS_VERIFICATION",edt(() -> ((Set<?>)field(dialog,"selectedTags")).contains("软件开发")));
    environment.tagError=com.lgguan.linuxdo.plugin.net.HttpFailure.INSTANCE.classify(429,java.util.Map.of(),"rate limit");
    choose("重试");await("tag rate limit prompt",() -> ((JLabel)field(dialog,"tagPickerStatus")).getText().contains("频率限制"));
    check("TAG_ORDINARY_429_REQUESTS_COOLDOWN",edt(() -> !((JLabel)field(dialog,"tagPickerStatus")).getText().contains("人机验证")));
    environment.tagError=null;choose("重试");
    await("tag retry",() -> !((JLabel)field(dialog,"tagPickerStatus")).getText().contains("读取失败") && ((DefaultListModel<?>)field(dialog,"tagSuggestionsModel")).size()>0);
    check("TAG_SEARCH_RETRY_SUCCEEDS",true);
    edt(() -> {((com.intellij.openapi.ui.popup.JBPopup)field(dialog,"tagPopup")).cancel();return null;});
    await("tag validation ready",() -> okEnabled(dialog));
    tagReopenChecks(dialog,environment);
  }
  @SuppressWarnings({"rawtypes","unchecked"}) private static void tagReopenChecks(DialogWrapper dialog,TopicEnvironment environment) throws Exception {
    environment.extraTags=200;
    for(int cycle=0;cycle<15;cycle++) {
      edt(() -> {button(dialog.getWindow(),"composer-tag-picker").doClick();return null;});
      await("large tag popup reopen",() -> ((DefaultListModel<?>)field(dialog,"tagSuggestionsModel")).size()==202);
      if(cycle==0) check("TAG_RENDERER_REUSES_ONE_ROW",edt(() -> {
        JList list=(JList)field(dialog,"tagSuggestionsList");ListCellRenderer renderer=list.getCellRenderer();
        return renderer.getListCellRendererComponent(list,list.getModel().getElementAt(0),0,false,false)==renderer.getListCellRendererComponent(list,list.getModel().getElementAt(1),1,true,false);
      }));
      if(cycle==0) tagPopupScreenshot(dialog,"topic-tag-web-layout");
      edt(() -> {JList<?> list=(JList<?>)field(dialog,"tagSuggestionsList");list.setSelectedIndex(100);list.ensureIndexIsVisible(100);((com.intellij.openapi.ui.popup.JBPopup)field(dialog,"tagPopup")).cancel();return null;});
    }
    check("TAG_SELECTED_REOPENS_15_TIMES_WITH_200_RESULTS",edt(() -> ((Set<?>)field(dialog,"selectedTags")).equals(Set.of("软件开发")) && field(dialog,"tagPopup")==null));
    check("TAG_RENDERER_COMPONENT_COUNT_STAYS_BOUNDED",edt(() -> {
      JList<?> list=(JList<?>)field(dialog,"tagSuggestionsList");return Arrays.stream(list.getComponents()).filter(c -> c instanceof CellRendererPane).allMatch(c -> ((CellRendererPane)c).getComponentCount()<=2);
    }));
    Dimension original=edt(() -> dialog.getWindow().getSize());
    edt(() -> {dialog.getWindow().setSize(440,700);return null;});
    await("narrow composer layout",() -> ((JButton)field(dialog,"tagSelectButton")).getWidth()<com.intellij.util.ui.JBUI.scale(300));
    edt(() -> {button(dialog.getWindow(),"composer-tag-picker").doClick();return null;});
    await("narrow tag popup",() -> ((DefaultListModel<?>)field(dialog,"tagSuggestionsModel")).size()==202);
    for(int i=0;i<7;i++) clickTag(dialog,"推荐标签"+i);
    await("selected chips wrap",() -> edt(() -> {
      JPanel chips=(JPanel)field(dialog,"tagsPanel");return chips.getComponentCount()==8 && Arrays.stream(chips.getComponents()).map(Component::getY).distinct().count()>1;
    }));
    tagPopupScreenshot(dialog,"topic-tag-eight-chips-narrow");
    report.println(edt(() -> {
      JPanel chips=(JPanel)field(dialog,"tagsPanel");JComponent content=((com.intellij.openapi.ui.popup.JBPopup)field(dialog,"tagPopup")).getContent();
      return "TAG_NARROW_BOUNDS=content:"+content.getSize()+";chips:"+chips.getSize()+";rows:"+Arrays.stream(chips.getComponents()).map(c -> c.getBounds().toString()).toList();
    }));
    check("TAG_EIGHT_CHIPS_WRAP_IN_NARROW_POPUP",edt(() -> {
      JPanel chips=(JPanel)field(dialog,"tagsPanel");
      JComponent content=((com.intellij.openapi.ui.popup.JBPopup)field(dialog,"tagPopup")).getContent();
      return content.getWidth()<=com.intellij.util.ui.JBUI.scale(406) && Arrays.stream(chips.getComponents()).allMatch(c -> c.getX()+c.getWidth()<=chips.getWidth() && c.getY()+c.getHeight()<=chips.getHeight());
    }));
    check("TAG_REMOVE_BUTTON_COMPACT",edt(() -> {
      JComponent content=((com.intellij.openapi.ui.popup.JBPopup)field(dialog,"tagPopup")).getContent();
      return components(content).stream().filter(c -> "composer-remove-tag-软件开发".equals(c.getName())).findFirst().orElseThrow().getWidth()<=com.intellij.util.ui.JBUI.scale(20);
    }));
    for(int i=0;i<7;i++) choose("composer-remove-tag-推荐标签"+i);
    await("narrow tag cleanup",() -> ((DefaultListModel<?>)field(dialog,"tagSuggestionsModel")).size()==202 && okEnabled(dialog));
    edt(() -> {((com.intellij.openapi.ui.popup.JBPopup)field(dialog,"tagPopup")).cancel();dialog.getWindow().setSize(original);return null;});
    environment.extraTags=0;
  }
  private static void topics() throws Exception {
    TopicEnvironment environment = new TopicEnvironment();
    topicEntryChecks(environment);
    Store store = new Store(topicData("网页原始标题",BODY,4,"软件开发"));
    DialogWrapper dialog = openTopic(store,environment,"new_topic_fixture_1");
    await("topic draft ready", () -> text(dialog).isEnabled() && okEnabled(dialog));
    check("TOPIC_RESTORES_FULL_DRAFT", edt(() -> ((JTextField)field(dialog,"titleField")).getText().equals("网页原始标题") && text(dialog).getText().equals(BODY)));
    JComboBox<?> categories = (JComboBox<?>)field(dialog,"categoryComboBox");
    check("TOPIC_EXCLUDES_UNWRITABLE_CATEGORY", edt(() -> categories.getItemCount()==3 && categories.getSelectedItem().toString().equals("开发调优")));
    topicPickerChecks(dialog,environment,categories);
    edt(() -> { ((JTextField)field(dialog,"titleField")).setText("插件修改后的标题"); text(dialog).append("\n插件正文修改"); return null; });
    String local = text(dialog).getText();
    await("topic autosave", () -> store.body().equals(local) && status(dialog).getText().equals("已同步到论坛"));
    check("TOPIC_AUTOSAVE_PRESERVES_UNKNOWN_FIELDS", store.data.get("title").getAsString().equals("插件修改后的标题") && store.data.get("unknown").getAsString().equals("keep"));
    previewChecks(dialog);
    Thread.sleep(450);
    screenshot(dialog,"topic-restored-preview");
    Window composer=edt(dialog::getWindow);
    Dimension originalSize=edt(composer::getSize);
    edt(() -> {composer.setSize(440,760);return null;});Thread.sleep(350);
    ComposerEditorSupport support=(ComposerEditorSupport)edt(() -> invoke(dialog,"getEditorSupport",new Class<?>[0]));
    screenshot(dialog,"topic-narrow-preview");
    report.println("COMPOSER_NARROW_WIDTH="+composer.getWidth()+";TOOLBAR="+support.getToolbar().getSize());
    check("IDE_NARROW_COMPOSER_TOOLBAR_WRAPS",edt(() -> composer.getWidth()<=450 && Arrays.stream(support.getToolbar().getComponents()).filter(Component::isVisible).allMatch(c -> c.getX()>=0 && c.getX()+c.getWidth()<=support.getToolbar().getWidth() && c.getY()+c.getHeight()<=support.getToolbar().getHeight())));
    screenshot(dialog,"topic-narrow-preview");
    edt(() -> {composer.setSize(originalSize);return null;});
    check("IDE_COMPOSER_ICON_BUTTONS_ACCESSIBLE",edt(() -> Arrays.stream(support.getToolbar().getComponents()).filter(c -> c instanceof JButton).map(c -> (JButton)c).allMatch(b -> b.getIcon()!=null && (b.getText()==null || b.getText().isEmpty()) && b.getAccessibleContext().getAccessibleName()!=null)));
    String unchanged=text(dialog).getText();
    edt(() -> {text(dialog).select(0,5);button(composer,"composer-bold").doClick();return null;});
    check("IDE_FORMATTING_PRESERVES_SELECTION",text(dialog).getText().startsWith("**"+unchanged.substring(0,5)+"**"));
    edt(() -> {button(composer,"composer-undo").doClick();return null;});
    check("IDE_FORMATTING_SINGLE_UNDO_RESTORES_BODY",text(dialog).getText().equals(unchanged));
    edt(() -> {text(dialog).select(0,5);composerMenu(composer,"composer-more","折叠详情");return null;});
    check("IDE_MORE_FORMATS_PRESERVE_BODY",text(dialog).getText().startsWith("[details=点击展开]\n"+unchanged.substring(0,5)+"\n[/details]"));
    edt(() -> {button(composer,"composer-undo").doClick();button(composer,"composer-preview").doClick();return null;});
    check("IDE_MORE_FORMATS_SINGLE_UNDO",text(dialog).getText().equals(unchanged));
    edt(() -> {composerMenu(composer,"composer-dropdown","本地预览");return null;});
    check("IDE_PREVIEW_MENU_OPENS_AND_SELECTS",edt(() -> (Boolean)field(dialog,"isPreviewVisible") && button(composer,"composer-preview").isSelected() && text(dialog).getText().equals(unchanged)));
    store.offline=true;
    edt(() -> { text(dialog).append("\n离线修改"); return null; }); saveNow(dialog);
    await("topic offline", () -> status(dialog).getText().contains("同步失败"));
    check("TOPIC_OFFLINE_RETAINS_EDITOR", text(dialog).isEnabled() && text(dialog).getText().contains("离线修改"));
    store.offline=false; edt(() -> { ((JButton)field(dialog,"draftRetry")).doClick(); return null; });
    await("topic retry", () -> status(dialog).getText().equals("已同步到论坛"));
    store.failure = com.lgguan.linuxdo.plugin.net.HttpFailure.INSTANCE.classify(429, java.util.Map.of("cf-mitigated","challenge"), "");
    edt(() -> { text(dialog).append("\n验证前的修改"); return null; }); saveNow(dialog);
    String topicVerificationBody = text(dialog).getText();
    await("topic verification prompt", () -> status(dialog).getText().contains("人机验证"));
    check("TOPIC_CLOUDFLARE_429_PROMPTS_VERIFICATION_AND_RETAINS_BODY", text(dialog).getText().equals(topicVerificationBody) && edt(() -> ((JButton)field(dialog,"draftRetry")).getText().equals("验证后重试")));
    store.failure = null; edt(() -> { ((JButton)field(dialog,"draftRetry")).doClick(); return null; });
    await("topic verification retry", () -> status(dialog).getText().equals("已同步到论坛"));
    check("TOPIC_VERIFICATION_RETRY_SYNCS_RETAINED_BODY", topicVerificationBody.equals(store.body()));
    store.sequence++; store.data=topicData("其他客户端标题",BODY+"\n其他客户端",5,"纯水");
    edt(() -> { text(dialog).append("\n本地冲突版本"); return null; }); saveNow(dialog);
    await("topic conflict", () -> status(dialog).getText().contains("草稿冲突"));
    edt(() -> { ((JButton)field(dialog,"draftRetry")).doClick(); return null; });
    choose("采用服务器版本");
    await("topic server choice", () -> text(dialog).getText().equals(store.body()) && okEnabled(dialog));
    check("TOPIC_CONFLICT_RESTORES_TITLE_CATEGORY_TAGS", edt(() -> ((JTextField)field(dialog,"titleField")).getText().equals("其他客户端标题") && categories.getSelectedItem().toString().equals("测试版块") && ((Set<?>)field(dialog,"selectedTags")).contains("纯水")));
    // A restored draft may contain a now-disabled tag; the picker rejects choosing it.
    edt(() -> { ((Set<String>)field(dialog,"selectedTags")).add("禁用标签"); invoke(dialog,"validateSelectedTags",new Class<?>[0]); return null; });
    await("disabled restored tag checked", () -> !(Boolean)field(dialog,"validatingTags"));
    check("TOPIC_DISABLED_TAG_BLOCKS_SEND", !okEnabled(dialog));
    edt(() -> { ((Set<?>)field(dialog,"selectedTags")).remove("禁用标签"); invoke(dialog,"validateSelectedTags",new Class<?>[0]); return null; });
    await("corrected tags ready", () -> okEnabled(dialog));
    uploads(dialog);
    environment.uncertain=true;
    SwingUtilities.invokeLater(() -> { try { invoke(dialog,"doOKAction",new Class<?>[0]); } catch(Exception e){throw new RuntimeException(e);} });
    choose("OK");
    check("TOPIC_UNCONFIRMED_PUBLISH_PRESERVES_DRAFT", text(dialog).isEnabled() && !okEnabled(dialog) && store.data!=null && store.deletes==0);
    check("TOPIC_503_RESULT_REQUIRES_WEB_CHECK", edt(() -> (Boolean)field(dialog,"unconfirmed") && ((JButton)field(dialog,"publishCheck")).isVisible()));
    edt(() -> { invoke(dialog,"doOKAction",new Class<?>[0]); return null; });
    check("TOPIC_UNCONFIRMED_NEVER_AUTORETRIES", environment.sends==1);
    edt(() -> { dialog.close(DialogWrapper.CANCEL_EXIT_CODE); return null; });
    environment.uncertain=false; environment.queued=true;
    DialogWrapper queue = openTopic(store,environment,"new_topic_fixture_1");
    await("queued editor ready", () -> okEnabled(queue));
    Window window=edt(queue::getWindow);
    SwingUtilities.invokeLater(() -> { try { invoke(queue,"doOKAction",new Class<?>[0]); } catch(Exception e){throw new RuntimeException(e);} });
    choose("OK"); await("queue accepted", () -> !window.isShowing());
    check("TOPIC_QUEUED_CLEARS_ONLY_OWN_DRAFT", store.data==null && store.deletes==1);
    Store fresh=new Store(null);
    DialogWrapper blank=openTopic(fresh,environment,"new_topic_fixture_2");
    await("fresh editor ready", () -> text(blank).isEnabled() && (Boolean)field(blank,"categoriesReady"));
    check("TOPIC_DEFAULT_CATEGORY_REQUIRES_PERMISSION", edt(() -> ((JComboBox<?>)field(blank,"categoryComboBox")).getSelectedIndex()==0 && !okEnabled(blank)));
    Thread.sleep(2200); check("TOPIC_EMPTY_FORM_NOT_AUTOSAVED",fresh.saves==0);
    edt(() -> { text(blank).append(BODY); SessionEpoch.INSTANCE.advance(); environment.listeners.forEach(Function0::invoke); return null; });
    Thread.sleep(2200); check("TOPIC_ACCOUNT_SWITCH_STOPS_SYNC",fresh.saves==0 && !okEnabled(blank));
    edt(() -> { blank.close(DialogWrapper.CANCEL_EXIT_CODE); return null; });
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
    topic.add("valid_reactions",JsonParser.parseString("[\"heart\",\"tada\"]"));
    JsonObject permissions=new JsonObject();permissions.addProperty("can_create_post",true);permissions.addProperty("notification_level",1);topic.add("details",permissions);
    JsonArray posts = new JsonArray(); JsonArray ids = new JsonArray();
    for (int floor = 1; floor <= 12; floor++) {
      JsonObject post = new JsonObject(); post.addProperty("id", floor); post.addProperty("topic_id", 999999);
      post.addProperty("post_number", floor); post.addProperty("username", "fixture_author");
      post.addProperty("yours",floor==1);post.addProperty("can_edit",floor==1);post.addProperty("can_delete",floor==1);
      post.addProperty("can_view_edit_history",true);post.addProperty("version",2);post.addProperty("bookmarked",false);
      if(floor<=2)post.add("reactions",new JsonArray());
      post.add("actions_summary",JsonParser.parseString("[{\"id\":2,\"count\":3,\"acted\":false,\"can_act\":"+(floor>1)+",\"can_undo\":false},{\"id\":99,\"count\":0,\"can_act\":true}]"));
      post.addProperty("cooked", "<p>中文正文第 " + floor + " 楼</p><pre><code>val sample = " + floor + "</code></pre><p>本地阅读验收</p>"+
        (floor==1 ? "<span class='math'>E=mc^2</span><pre><code class='language-mermaid'>graph LR; A[Read] --> B[Reply]</code></pre><aside class='quote' data-topic='2909396' data-post='5'><div class='title'>跨话题作者</div><blockquote>引用正文</blockquote></aside><aside class='quote' data-topic='999999' data-post='8'><div class='title'>本话题作者</div><blockquote>本话题引用</blockquote></aside><details><summary>展开详情</summary><p>内容</p></details><div class='spoiler'>隐藏内容</div><aside class='onebox'><header>来源</header><article><h3>卡片标题</h3><p>摘要</p></article></aside><table><tr><th>中文</th><th>值</th></tr><tr><td>数据</td><td>1</td></tr></table>" : ""));
      posts.add(post); ids.add(floor);
    }
    JsonObject stream = new JsonObject(); stream.add("posts", posts); stream.add("stream", ids); topic.add("post_stream", stream);
    TopicDetailResponse detail = GSON.fromJson(topic, TopicDetailResponse.class);
    // Reader capabilities include account state. Use memory-only credentials and local timing responses.
    LinuxDoAuthService auth=LinuxDoAuthService.Companion.getInstance();
    Field credentialsField=LinuxDoAuthService.class.getDeclaredField("credentials");credentialsField.setAccessible(true);
    Field userField=LinuxDoAuthService.class.getDeclaredField("confirmedUser");userField.setAccessible(true);
    Field versionField=LinuxDoAuthService.class.getDeclaredField("confirmedVersion");versionField.setAccessible(true);
    Object previousCredentials=credentialsField.get(auth),previousUser=userField.get(auth),previousVersion=versionField.get(auth);
    com.lgguan.linuxdo.plugin.net.PersistentCookieJar readerCredentials=new com.lgguan.linuxdo.plugin.net.PersistentCookieJar(false);
    readerCredentials.injectCookie("_t","isolated-reader-synthetic","linux.do");
    Field clientField=LinuxDoHttpClient.class.getDeclaredField("client");clientField.setAccessible(true);
    okhttp3.OkHttpClient previousClient=LinuxDoHttpClient.INSTANCE.getClient();
    LinuxDoSettingsState settings=LinuxDoSettingsState.Companion.getInstance();String previousMode=settings.getNetworkMode();
    boolean previousAutoReport=settings.getAutoReportReadTimings();settings.setAutoReportReadTimings(true);
    java.util.concurrent.atomic.AtomicInteger timingPosts=new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.atomic.AtomicBoolean timingCsrfVerified=new java.util.concurrent.atomic.AtomicBoolean();
    java.util.concurrent.atomic.AtomicReference<Map<String,String>> timingBody=new java.util.concurrent.atomic.AtomicReference<>();
    settings.setNetworkMode("JAVA_ONLY");
    clientField.set(null,previousClient.newBuilder().cookieJar(readerCredentials).addInterceptor(chain->{
      okhttp3.Request request=chain.request();String path=request.url().encodedPath();String response;
      if(request.method().equals("GET")&&(path.equals("/session/csrf")||path.equals("/session/csrf.json")))response="{\"csrf\":\"reader-fixture-csrf\"}";
      else if(request.method().equals("POST")&&path.equals("/topics/timings")){
        okio.Buffer form=new okio.Buffer();request.body().writeTo(form);Map<String,String> values=new HashMap<>();
        for(String entry:form.readUtf8().split("&")){String[] pair=entry.split("=",2);values.put(java.net.URLDecoder.decode(pair[0],java.nio.charset.StandardCharsets.UTF_8),java.net.URLDecoder.decode(pair[1],java.nio.charset.StandardCharsets.UTF_8));}
        timingBody.set(values);timingPosts.incrementAndGet();response="";
        timingCsrfVerified.set("reader-fixture-csrf".equals(request.header("X-CSRF-Token")));
      }
      else throw new IOException("Isolated reader rejects all HTTP transport");
      return new okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("isolated reader fixture").body(okhttp3.ResponseBody.create(response,okhttp3.MediaType.parse("application/json"))).build();
    }).build());
    credentialsField.set(auth,readerCredentials);
    auth.setCurrentUserDirectly(GSON.fromJson("{\"id\":999998,\"username\":\"fixture_reader\"}",UserInfo.class));
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
      check("IDE_READER_ONLY_AVAILABLE_ACTIONS",evaluate(browser,query,replies,"({ok:!document.querySelector('.action-disabled,[data-reader-action=recover],[data-reader-action=accept],[data-reader-action=reactionUsers]') && !document.querySelector('#floor-2 [data-reader-action=edit]') && !document.querySelector('.floor-comment-header .floor-actions') && !!document.querySelector('#floor-2 [data-reader-action=reaction]') && !document.querySelector('#floor-1 [data-reader-action=reaction]')})").get("ok").getAsBoolean());
      check("IDE_READER_ACTIONS_COMPACT_AND_UNIFORM",evaluate(browser,query,replies,"({ok:[...document.querySelectorAll('.floor-actions')].every(el=>el.querySelectorAll(':scope > button').length<=3 && el.querySelectorAll('.post-actions-menu').length<=1) && new Set([...document.querySelectorAll('.floor-actions > button')].map(el=>el.getBoundingClientRect().height)).size===1 && !document.querySelector('.post-actions-menu[open]')})").get("ok").getAsBoolean());
      check("IDE_ICON_ACTIONS_AND_DOCUMENT_READING_TOOLS",evaluate(browser,query,replies,"(()=>{const el=document.querySelector('.topic-reader-tools'),tools=el.getBoundingClientRect(),header=el.closest('.doc-header').getBoundingClientRect();return {ok:!!document.querySelector('#floor-2 .floor-actions > [data-post-command=boost]') && !document.querySelector('.floor-actions > [data-post-command=share]') && !!document.querySelector('.post-actions-menu-items [data-post-command=share]') && getComputedStyle(el).position==='absolute' && Math.abs(tools.top-header.top)<2 && Math.abs(tools.right-header.right)<2 && !document.querySelector('.topic-navigation .topic-reader-tools')}})()").get("ok").getAsBoolean());
      check("IDE_CONCISE_METADATA_AND_SINGLE_BOOKMARK_ICON",evaluate(browser,query,replies,"({ok:![...document.querySelectorAll('.floor-number')].some(el=>/Original Specification|Revision|---/.test(el.textContent)) && [...document.querySelectorAll('[data-reader-action=bookmark]')].every(el=>el.querySelectorAll('svg').length===1 && !/[★☆]/.test(el.textContent))})").get("ok").getAsBoolean());
      check("IDE_READER_SEMANTIC_CONTENT",evaluate(browser,query,replies,"({ok:document.querySelectorAll('aside.quote').length===2 && document.querySelectorAll('.onebox article').length===1 && document.querySelectorAll('.forum-table table').length===1})").get("ok").getAsBoolean());
      Thread.sleep(1500);
      check("IDE_OFFLINE_MATH_AND_MERMAID_RENDER",evaluate(browser,query,replies,"({ok:document.querySelectorAll('.forum-source-output svg').length===2 && !document.querySelector('.forum-render-error')})").get("ok").getAsBoolean());
      evaluate(browser,query,replies,"(()=>{const node=document.querySelector('#floor-1 .post-content p').firstChild,r=document.createRange();r.setStart(node,0);r.setEnd(node,4);getSelection().removeAllRanges();getSelection().addRange(r);document.querySelector('#floor-1 details').open=true;window.readerThemeState={key:linuxDoPage.key,node:document.querySelector('#floor-1'),selection:getSelection().toString(),top:document.querySelector('#floor-1').getBoundingClientRect().top};return {ok:true}})()");
      com.intellij.openapi.editor.colors.EditorColorsManager colors=com.intellij.openapi.editor.colors.EditorColorsManager.getInstance();
      com.intellij.openapi.editor.colors.EditorColorsScheme originalScheme=colors.getGlobalScheme();
      com.intellij.openapi.editor.colors.EditorColorsScheme lightScheme=Arrays.stream(colors.getAllSchemes()).filter(s->!com.intellij.ui.ColorUtil.isDark(s.getDefaultBackground())).findFirst().orElseThrow();
      final LinuxDoJSQuery themeQuery=query;
      String lightBackground="#"+com.intellij.ui.ColorUtil.toHex(lightScheme.getDefaultBackground()),originalBackground="#"+com.intellij.ui.ColorUtil.toHex(originalScheme.getDefaultBackground());
      try {
        edt(()->{colors.setGlobalScheme(lightScheme);return null;});
        await("reader follows light editor theme",()->evaluate(browser,themeQuery,replies,"({ok:getComputedStyle(document.documentElement).getPropertyValue('--bg').trim().toLowerCase()==='"+lightBackground.toLowerCase()+"'&&getComputedStyle(document.body).color==='rgb(31, 35, 40)'})").get("ok").getAsBoolean());
        check("IDE_THEME_CHANGE_UPDATES_CURRENT_DOCUMENT",evaluate(browser,query,replies,"({ok:linuxDoPage.key===readerThemeState.key && document.querySelector('#floor-1')===readerThemeState.node && getSelection().toString()===readerThemeState.selection && document.querySelector('#floor-1 details').open && Math.abs(document.querySelector('#floor-1').getBoundingClientRect().top-readerThemeState.top)<3})").get("ok").getAsBoolean());
        edt(()->{colors.setGlobalScheme(originalScheme);return null;});
        await("reader restores editor theme",()->evaluate(browser,themeQuery,replies,"({ok:getComputedStyle(document.documentElement).getPropertyValue('--bg').trim().toLowerCase()==='"+originalBackground.toLowerCase()+"'})").get("ok").getAsBoolean());
        check("IDE_THEME_RESTORE_PRESERVES_DOCUMENT",evaluate(browser,query,replies,"({ok:linuxDoPage.key===readerThemeState.key && document.querySelector('#floor-1')===readerThemeState.node})").get("ok").getAsBoolean());
        evaluate(browser,query,replies,"(()=>{document.getElementById('linuxdo-reader-theme').textContent=':root { --bg: #123456; }';return {ok:true}})()");
        edt(()->{com.intellij.openapi.application.ApplicationManager.getApplication().getMessageBus().syncPublisher(com.intellij.ide.ui.LafManagerListener.TOPIC).lookAndFeelChanged(com.intellij.ide.ui.LafManager.getInstance());return null;});
        await("look and feel event updates reader",()->evaluate(browser,themeQuery,replies,"({ok:getComputedStyle(document.documentElement).getPropertyValue('--bg').trim().toLowerCase()==='"+originalBackground.toLowerCase()+"'})").get("ok").getAsBoolean());
        check("IDE_LOOK_AND_FEEL_EVENT_UPDATES_READER",evaluate(browser,query,replies,"({ok:linuxDoPage.key===readerThemeState.key && document.querySelector('#floor-1')===readerThemeState.node})").get("ok").getAsBoolean());
      } finally {edt(()->{colors.setGlobalScheme(originalScheme);return null;});}
      int originalWidth=settings.getReadingWidth();
      try {
        edt(()->{settings.setReadingWidth(600);settings.fireSettingsChanged();return null;});
        await("reading width updates current layout",()->evaluate(browser,themeQuery,replies,"({ok:linuxDoPage.width===600 && Math.abs(document.querySelector('.doc-container').getBoundingClientRect().width-600)<2 && Math.abs(document.querySelector('.doc-header').getBoundingClientRect().width-600)<2 && Math.abs(document.querySelector('#floor-1').getBoundingClientRect().width-600)<2})").get("ok").getAsBoolean());
        check("IDE_READING_WIDTH_UPDATES_WITHOUT_REOPENING",evaluate(browser,query,replies,"({ok:linuxDoPage.key===readerThemeState.key && document.querySelector('#floor-1')===readerThemeState.node && getSelection().toString()===readerThemeState.selection})").get("ok").getAsBoolean());
      } finally {edt(()->{settings.setReadingWidth(originalWidth);settings.fireSettingsChanged();return null;});}
      await("reading width restored",()->evaluate(browser,themeQuery,replies,"({ok:linuxDoPage.width==="+originalWidth+"})").get("ok").getAsBoolean());
      evaluate(browser,query,replies,"(()=>{getSelection().removeAllRanges();document.querySelector('#floor-1 details').open=false;return {ok:true}})()");
      check("IDE_CROSS_TOPIC_QUOTE_SHOWS_CONTEXT",evaluate(browser,query,replies,"(()=>{var original=window.intellijBridge.readerAction;var target=0;window.intellijBridge.readerAction=function(key,id,action,postId,input){target=input.topic;linuxDoReaderResult(key,id,{items:[{floor:5,author:'sample',text:'公开引用上下文'}]})};document.querySelector('aside[data-topic=\"2909396\"] .quote-controls').click();window.intellijBridge.readerAction=original;return {ok:target===2909396 && !!document.querySelector('.topic-reader-panel')}})()").get("ok").getAsBoolean());
      evaluate(browser, query, replies, "(()=>{document.querySelector('.topic-reader-panel button').click();linuxDoPagination.jump(8);return {ok:true}})()");
      await("IDE receives successful jump origin", () -> ((Map<?,?>)field(panel, "returnFloors")).containsKey(999999L));
      check("IDE_NAVIGATION_RETURN_BRIDGE", edt(() -> ((Map<?,?>)field(panel, "returnFloors")).get(999999L) != null));
      check("IDE_RETURN_BUTTON_ENABLED", evaluate(browser, query, replies, "({ok:!document.querySelector('.topic-return-button').disabled})").get("ok").getAsBoolean());
      evaluate(browser, query, replies, "(()=>{document.querySelector('.topic-return-button').click();return {ok:true}})()");
      evaluate(browser, query, replies, "(()=>{linuxDoPagination.jump(1);return {ok:true}})()");
      Thread.sleep(2000);
      showcaseCapture(frame,"reader-media-and-actions");
      edt(() -> { frame.setSize(360, 700); return null; }); Thread.sleep(300);
      check("IDE_NARROW_NAVIGATION_NO_OVERFLOW", evaluate(browser, query, replies, "({ok:document.documentElement.scrollWidth<=window.innerWidth+1})").get("ok").getAsBoolean());
      evaluate(browser,query,replies,"(()=>{document.querySelector('#floor-1 .post-actions-menu summary').click();return {ok:true}})()");Thread.sleep(100);
      check("IDE_NARROW_POST_MENU_STAYS_IN_VIEW",evaluate(browser,query,replies,"(()=>{const r=document.querySelector('#floor-1 .post-actions-menu-items').getBoundingClientRect();return {ok:r.left>=0 && r.right<=innerWidth && r.top>=0 && r.bottom<=innerHeight}})()").get("ok").getAsBoolean());
      evaluate(browser,query,replies,"(()=>{document.querySelector('#floor-1 .post-actions-menu').open=false;return {ok:true}})()");
      showcaseCapture(frame,"reader-narrow-navigation");
      // Capture ordinary short posts, matching the layout that prompted this regression.
      JsonObject compact=GSON.toJsonTree(detail).getAsJsonObject();compact.addProperty("title","开发笔记与交流");compact.addProperty("highest_post_number",2);compact.addProperty("last_read_post_number",0);
      JsonArray shortPosts=new JsonArray();
      for(int index=0;index<2;index++){
        JsonObject p=posts.get(index).getAsJsonObject().deepCopy();p.addProperty("cooked",index==0?"<p>整理了一份开发笔记，欢迎交流。</p>":"<p>感谢分享，期待后续更新。</p>");
        p.addProperty("can_edit",false);p.addProperty("can_delete",false);p.addProperty("can_view_edit_history",false);
        if(index==0)p.add("actions_summary",JsonParser.parseString("[{\"id\":2,\"count\":0,\"can_act\":false}]"));
        shortPosts.add(p);
      }
      JsonObject shortStream=new JsonObject();shortStream.add("posts",shortPosts);shortStream.add("stream",JsonParser.parseString("[1,2]"));compact.add("post_stream",shortStream);
      TopicDetailResponse ordinary=GSON.fromJson(compact,TopicDetailResponse.class);
      edt(() -> {frame.setSize(960,700);Field f=DocViewerPanel.class.getDeclaredField("currentTopic");f.setAccessible(true);f.set(panel,ordinary);invoke(panel,"renderTopic",new Class<?>[]{TopicDetailResponse.class,Integer.class},ordinary,1);return null;});
      Thread.sleep(1200);
      check("IDE_ORDINARY_POSTS_HAVE_ONLY_AVAILABLE_ACTIONS",evaluate(browser,query,replies,"({ok:document.querySelectorAll('.post-entry').length===2 && !document.querySelector('[data-reader-action=edit],[data-reader-action=history],[data-reader-action=delete],.action-disabled') && document.querySelector('.topic-return-button').hidden && document.querySelector('#floor-1 .floor-actions').getBoundingClientRect().top>=document.querySelector('#floor-1 .post-content').getBoundingClientRect().bottom})").get("ok").getAsBoolean());
      check("IDE_READER_FOCUS_TARGET_IS_BROWSER",edt(()->panel.preferredFocusedComponent()==browser.getComponent()));
      edt(()->{frame.toFront();frame.requestFocus();panel.setSelected(true);panel.preferredFocusedComponent().requestFocusInWindow();return null;});
      Point readerWindow=edt(frame::getLocationOnScreen);Robot readerFocus=new Robot();readerFocus.mouseMove(readerWindow.x+200,readerWindow.y+15);readerFocus.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);readerFocus.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
      edt(()->{panel.preferredFocusedComponent().requestFocusInWindow();return null;});
      final LinuxDoJSQuery readingQuery=query;
      await("automatic foreground read samples",()->evaluate(browser,readingQuery,replies,"({ok:[...document.querySelectorAll('.unread-dot')].every(el=>getComputedStyle(el).display==='none')})").get("ok").getAsBoolean());
      check("IDE_AUTO_READ_UPDATES_VISIBLE_DOTS",LinuxDoReadTrackingService.Companion.getInstance().isPostRead(999999L,1,false,0)&&LinuxDoReadTrackingService.Companion.getInstance().isPostRead(999999L,2,false,0));
      check("IDE_AUTO_READ_CLEARS_UNREAD_TOOL",evaluate(browser,query,replies,"({ok:linuxDoPage.unreadFloor===null&&[...document.querySelectorAll('.topic-reader-menu button')].find(el=>el.textContent==='未读').hidden})").get("ok").getAsBoolean());
      edt(()->{invoke(panel,"flushReading",new Class<?>[0]);return null;});await("automatic timings reach mock server",()->timingPosts.get()>0);
      Map<String,String> reportedValues=timingBody.get();
      check("IDE_AUTO_READ_SYNCS_REAL_TIMING_PAYLOAD",timingCsrfVerified.get() && reportedValues.get("topic_id").equals("999999") && Long.parseLong(reportedValues.get("timings[1]"))>0 && Long.parseLong(reportedValues.get("timings[2]"))>0 && Long.parseLong(reportedValues.get("topic_time"))==Long.parseLong(reportedValues.get("timings[1]"))+Long.parseLong(reportedValues.get("timings[2]")));
      edt(()->{panel.setSelected(false);return null;});Thread.sleep(1200);
      check("IDE_BACKGROUND_TAB_ACCUMULATES_NO_READING",((Map<?,?>)invoke(field(panel,"readingClock"),"drain",new Class<?>[0])).isEmpty());
      evaluate(browser,query,replies,"(()=>{linuxDoPagination.jump(2);return {ok:true}})()");
      check("IDE_LOCATION_STATUS_AND_SHORTCUTS_SHARE_ROW",evaluate(browser,query,replies,"(()=>{const a=document.querySelector('.topic-navigation-status').getBoundingClientRect(),b=document.querySelector('.floor-jump-controls').getBoundingClientRect();return {ok:Math.abs((a.top+a.bottom)/2-(b.top+b.bottom)/2)<2}})()").get("ok").getAsBoolean());
      evaluate(browser,query,replies,"(()=>{document.querySelector('#floor-2 .post-actions-menu').open=true;return {ok:true}})()");
      final LinuxDoJSQuery resizeQuery=query;
      for(int[] size:new int[][]{{1080,780},{480,600},{860,740},{360,700},{960,700}}){
        edt(()->{frame.setSize(size[0],size[1]);return null;});
        await("actual IDE browser viewport follows "+size[0],()->evaluate(browser,resizeQuery,replies,"({width:innerWidth,height:innerHeight})").get("width").getAsInt()==browser.getComponent().getWidth() && evaluate(browser,resizeQuery,replies,"({height:innerHeight})").get("height").getAsInt()==browser.getComponent().getHeight());
        Thread.sleep(150);
        check("IDE_DYNAMIC_RESIZE_"+size[0]+"_"+size[1],evaluate(browser,query,replies,"(()=>{const el=document.querySelector('.topic-reader-tools'),r=el.getBoundingClientRect(),h=el.closest('.doc-header').getBoundingClientRect(),m=document.querySelector('#floor-2 .post-actions-menu-items').getBoundingClientRect();return {ok:Math.abs(r.right-h.right)<2 && document.documentElement.scrollWidth<=innerWidth && m.left>=0&&m.right<=innerWidth&&m.top>=0&&m.bottom<=innerHeight}})()").get("ok").getAsBoolean());
      }
      evaluate(browser,query,replies,"(()=>{document.querySelector('#floor-2 .post-actions-menu').open=false;return {ok:true}})()");
      Thread.sleep(2500);
      showcaseCapture(frame,"reader-compact-actions");
      edt(() -> {frame.setSize(360,700);return null;});Thread.sleep(350);
      check("IDE_ORDINARY_NARROW_LAYOUT_NO_OVERFLOW",evaluate(browser,query,replies,"({ok:document.documentElement.scrollWidth<=innerWidth+1 && !document.querySelector('.topic-reader-tools[open]')})").get("ok").getAsBoolean());
      showcaseCapture(frame,"reader-compact-narrow");
      // Verify actual native frames without a wheel/JS query to provoke repainting.
      for(int i=0;i<240;i++) {
        int width=360+(i*73)%900,height=420+(i*31)%360;
        edt(()->{frame.setSize(width,height);return null;}); Thread.sleep(5);
      }
      edt(()->{frame.setSize(960,700);return null;});
      Thread.sleep(200);
      report.println("RESIZE_DIAGNOSTIC_COMPONENT="+browser.getComponent().getSize()+" SCALE="+browser.getComponent().getGraphicsConfiguration().getDefaultTransform().getScaleX()
          +" IMAGE="+((BufferedImage)field(browser,"image")).getWidth()+"x"+((BufferedImage)field(browser,"image")).getHeight());
      await("native bitmap follows final IDE resize without scrolling",()-> {
        synchronized(field(browser,"imageLock")) {
          BufferedImage bitmap=(BufferedImage)field(browser,"image");
          double scale=browser.getComponent().getGraphicsConfiguration().getDefaultTransform().getScaleX();
          return bitmap!=null && bitmap.getWidth()==Math.ceil(browser.getComponent().getWidth()*scale)
              && bitmap.getHeight()==Math.ceil(browser.getComponent().getHeight()*scale);
        }
      });
      check("IDE_RAPID_RESIZE_PAINTS_WITHOUT_SCROLL",true);
      // Inject the same callback-channel loss seen in the user's log, only in this isolated IDE.
      Object runtime=browser.getRuntime(),app=field(runtime,"app");
      Object server=browser.getRuntime().call(app,"getServer",new Object[0]);
      invoke(server,"onCefHandlersThreadFinished",new Class<?>[0]);
      await("reader offers recovery after callback disconnect",()->retryBrowserButton(panel)!=null);
      check("IDE_DISCONNECT_SHOWS_RETRY_INSTEAD_OF_WAITING",true);
      edt(()->{retryBrowserButton(panel).doClick();return null;});
      await("fresh reader created after retry",()->field(panel,"jbCefBrowser")!=null && field(panel,"jbCefBrowser")!=browser);
      LinuxDoBrowser recovered=(LinuxDoBrowser)edt(()->field(panel,"jbCefBrowser"));
      LinuxDoJSQuery recoveredQuery=(LinuxDoJSQuery)edt(()->field(panel,"jsQuery"));
      BlockingQueue<String> recoveryReplies=new LinkedBlockingQueue<>();
      @SuppressWarnings("unchecked") Function1<String,LinuxDoJSQuery.Response> recoveredHandler=(Function1<String,LinuxDoJSQuery.Response>)field(recoveredQuery,"handler");
      recoveredQuery.addHandler(value->{if(value.startsWith("probe:")){recoveryReplies.add(value.substring(6));return null;}return recoveredHandler.invoke(value);});
      await("recovered reader restores cached topic",()->evaluate(recovered,recoveredQuery,recoveryReplies,"({ok:document.querySelectorAll('.post-entry').length===2 && !!window.linuxDoPagination})").get("ok").getAsBoolean());
      check("IDE_RETRY_RESTORES_TOPIC_IN_FRESH_RUNTIME",recovered.getRuntime()!=runtime && panel.getCurrentPostNumber()!=null);
    } finally {
      if (query != null) query.dispose();
      edt(() -> { panel.dispose(); frame.dispose(); return null; });
      credentialsField.set(auth,previousCredentials);userField.set(auth,previousUser);versionField.set(auth,previousVersion);
      clientField.set(null,previousClient);settings.setNetworkMode(previousMode);settings.setAutoReportReadTimings(previousAutoReport);
    }
    report.println("READER_NETWORK_REQUESTS_BLOCKED=true");
    report.println("TOTAL_CHECKS=" + checks.size());
  }
  private static JButton retryBrowserButton(Container root) {
    for(Component child:root.getComponents()) {
      if(child instanceof JButton && ((JButton)child).getText().equals("重试正文浏览器"))return (JButton)child;
      if(child instanceof Container) { JButton result=retryBrowserButton((Container)child);if(result!=null)return result; }
    }
    return null;
  }
}
