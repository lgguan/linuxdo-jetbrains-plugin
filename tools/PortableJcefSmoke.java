import com.lgguan.linuxdo.plugin.net.*;
import com.lgguan.linuxdo.plugin.common.Constants;
import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState;
import com.intellij.ui.jcef.JBCefCookie;
import com.google.gson.JsonObject;
import org.cef.CefApp;
import org.cef.browser.*;
import org.cef.handler.CefLoadHandlerAdapter;
import java.awt.event.*;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;

/** Deterministic native smoke: production runtime/rendering/IPC, synthetic pages and cookies, no network/account. */
public class PortableJcefSmoke {
  public static void main(String[] args) throws Exception {
    System.out.println("ENVIRONMENT=" + System.getProperty("os.name") + "/" + System.getProperty("os.arch"));
    String failure = IsolatedCefRuntime.Companion.supportFailure();
    if (failure != null) throw new AssertionError(failure);
    Object platformState = CefApp.getState();
    boolean platformRemote = CefApp.isRemoteEnabled();
    LinuxDoSettingsState settings = LinuxDoSettingsState.Companion.getInstance();
    settings.setBaseUrl("https://linux.do");
    settings.setDohProvider(Constants.DohProvider.DISABLED);
    StandaloneSmokeApplication.installApplication(settings);
    IsolatedCefRuntime runtime = null;
    int exit = 1;
    try {
      runtime = IsolatedCefRuntime.Companion.get();
      LinuxDoBrowser browser = new LinuxDoBrowser(runtime);
      browser.getComponent().setSize(1000, 720);
      LinuxDoJSQuery query = LinuxDoJSQuery.Companion.create(browser, true);
      query.addHandler(result -> { PluginCefSmoke.replies.add(result); return null; });
      CountDownLatch ready = new CountDownLatch(1);
      browser.getJbCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
        public void onLoadEnd(CefBrowser b, CefFrame frame, int status) {
          if (frame.isMain() && status == 200) ready.countDown();
        }
      }, browser.getCefBrowser());
      browser.loadHTML("<!doctype html><html><body style='margin:0'><input id='entry' style='width:300px;height:40px'>"
          + "<div style='height:5000px;background:linear-gradient(white,blue)'>Native smoke</div></body></html>");
      browser.createImmediately();
      PluginCefSmoke.check(ready.await(25, TimeUnit.SECONDS), "LOCAL_DOCUMENT_READY");
      PluginCefSmoke.check(PluginCefSmoke.evaluate(browser, query,
          "({valid:location.origin==='https://linux.do'&&document.querySelector('#entry')!==null})")
          .get("valid").getAsBoolean(), "DOCUMENT_ORIGIN");
      SwingUtilities.invokeAndWait(() -> {
        browser.getCefBrowser().setFocus(true);
        for (int id : new int[]{MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED}) {
          browser.getComponent().dispatchEvent(new MouseEvent(browser.getComponent(), id,
              System.currentTimeMillis(), 0, 100, 20, 1, false, MouseEvent.BUTTON1));
        }
      });
      // Renderer focus is asynchronous; do not race the subsequent key events against mouse IPC.
      await(browser, query, "document.activeElement===document.querySelector('#entry')", "INPUT_FOCUSED");
      SwingUtilities.invokeAndWait(() -> {
        for (char c : "native".toCharArray()) {
          for (KeyListener listener : browser.getComponent().getKeyListeners()) {
            listener.keyPressed(new KeyEvent(browser.getComponent(), KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.getExtendedKeyCodeForChar(c), c));
            listener.keyTyped(new KeyEvent(browser.getComponent(), KeyEvent.KEY_TYPED, 0, 0, KeyEvent.VK_UNDEFINED, c));
            listener.keyReleased(new KeyEvent(browser.getComponent(), KeyEvent.KEY_RELEASED, 0, 0, KeyEvent.getExtendedKeyCodeForChar(c), c));
          }
        }
      });
      await(browser, query, "document.querySelector('#entry').value==='native'", "KEYBOARD_INPUT");
      SwingUtilities.invokeAndWait(() -> browser.getComponent().dispatchEvent(new MouseWheelEvent(
          browser.getComponent(), MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0, 500, 500, 0,
          false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1)));
      await(browser, query, "window.scrollY>0", "WHEEL_SCROLL");
      browser.getJbCefCookieManager().setCookie("https://linux.do/",
          new JBCefCookie("linuxdo_smoke", "synthetic", "linux.do", "/", true, false));
      await(browser, query, "document.cookie.includes('linuxdo_smoke=synthetic')", "COOKIE_WRITE");
      LinuxDoBrowser second = new LinuxDoBrowser(runtime);
      second.getComponent().setSize(800, 600);
      LinuxDoJSQuery secondQuery = PluginCefSmoke.query(second);
      second.loadHTML("<!doctype html><html><body>Shared session</body></html>");
      second.createImmediately();
      // Await creation before running a query in the second renderer.
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
      while (!second.getCefBrowser().getURL().startsWith("https://linux.do") && System.nanoTime() < deadline) Thread.sleep(100);
      await(second, secondQuery, "document.cookie.includes('linuxdo_smoke=synthetic')", "SHARED_SESSION");
      browser.getJbCefCookieManager().deleteCookies("https://linux.do/", "linuxdo_smoke");
      await(second, secondQuery, "!document.cookie.includes('linuxdo_smoke=')", "COOKIE_DELETE");
      // Import after two browsers are already running: replace both old account cookies and session state.
      browser.getJbCefCookieManager().setCookie("https://linux.do/",
          new JBCefCookie("_t", "old-synthetic", ".linux.do", "/", true, false));
      await(second, secondQuery, "document.cookie.includes('_t=old-synthetic')", "OLD_ACCOUNT_COOKIE");
      java.lang.reflect.Method replaceCookies = java.util.Arrays.stream(LinuxDoBrowser.class.getDeclaredMethods())
          .filter(method -> method.getName().startsWith("replaceSessionCookies") && method.getParameterCount() == 2
              && !java.lang.reflect.Modifier.isStatic(method.getModifiers())).findFirst().orElseThrow();
      ((CompletableFuture<?>)replaceCookies.invoke(browser,
          LoginCookieSupport.INSTANCE.parseHeader("_t=new-synthetic; _forum_session=new-session"),
          SessionEpoch.INSTANCE.getCurrent())).get(12, TimeUnit.SECONDS);
      await(second, secondQuery,
          "document.cookie.includes('_t=new-synthetic') && document.cookie.includes('_forum_session=new-session') && !document.cookie.includes('old-synthetic')",
          "IMPORTED_ACCOUNT_SHARED");
      java.lang.reflect.Field image = LinuxDoBrowser.class.getDeclaredField("image");
      image.setAccessible(true);
      PluginCefSmoke.check(image.get(browser) != null, "NATIVE_PAINT");
      Object firstRaster = image.get(browser);
      for (int i = 0; i < 20; i++) {
        browser.getCefBrowser().executeJavaScript("window.scrollBy(0,10)", browser.getCefBrowser().getURL(), 0);
        Thread.sleep(30);
      }
      PluginCefSmoke.check(firstRaster == image.get(browser), "PAINT_BUFFER_REUSED");
      String oldQuery = query.inject("JSON.stringify({old:true})");
      CountDownLatch newDocumentReady = new CountDownLatch(1);
      browser.getJbCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
        public void onLoadEnd(CefBrowser b, CefFrame frame, int status) {
          if (frame.isMain() && status == 200) newDocumentReady.countDown();
        }
      }, browser.getCefBrowser());
      browser.newDocument();
      browser.loadHTML("<!doctype html><html><body>New trusted document</body></html>");
      PluginCefSmoke.check(newDocumentReady.await(25, TimeUnit.SECONDS), "NEW_DOCUMENT_READY");
      await(browser, query, "document.body.textContent==='New trusted document'", "NEW_DOCUMENT_QUERY");
      PluginCefSmoke.replies.clear();
      browser.getCefBrowser().executeJavaScript(oldQuery, browser.getCefBrowser().getURL(), 0);
      PluginCefSmoke.check(PluginCefSmoke.replies.poll(1, TimeUnit.SECONDS) == null, "STALE_DOCUMENT_QUERY_REJECTED");
      browser.loadURL("data:text/html,<html><body>Untrusted page</body></html>");
      Thread.sleep(1000);
      PluginCefSmoke.replies.clear();
      browser.getCefBrowser().executeJavaScript(query.inject("JSON.stringify({external:true})"), "", 0);
      PluginCefSmoke.check(PluginCefSmoke.replies.poll(1, TimeUnit.SECONDS) == null, "EXTERNAL_DOCUMENT_QUERY_REJECTED");
      secondQuery.dispose(); second.dispose(); query.dispose(); browser.dispose(); browser.dispose();
      PaginationSmoke.run();
      runtime.dispose();
      runtime = IsolatedCefRuntime.Companion.get();
      LinuxDoBrowser recreated = new LinuxDoBrowser(runtime);
      recreated.getComponent().setSize(800, 600);
      LinuxDoJSQuery recreatedQuery = PluginCefSmoke.query(recreated);
      CountDownLatch recreatedReady = new CountDownLatch(1);
      recreated.getJbCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
        public void onLoadEnd(CefBrowser b, CefFrame frame, int status) {
          if (frame.isMain() && status == 200) recreatedReady.countDown();
        }
      }, recreated.getCefBrowser());
      recreated.loadHTML("<!doctype html><html><body>Recreated browser</body></html>");
      recreated.createImmediately();
      PluginCefSmoke.check(recreatedReady.await(25, TimeUnit.SECONDS), "RUNTIME_RECREATE");
      await(recreated, recreatedQuery, "document.body.textContent==='Recreated browser'", "RECREATED_RENDERER");
      recreatedQuery.dispose(); recreated.dispose(); runtime.dispose();
      // Disposing during NEW used to race CefInitialize-thread and leak cef_server.
      runtime = IsolatedCefRuntime.Companion.get();
      runtime.dispose();
      java.lang.reflect.Field termination = IsolatedCefRuntime.class.getDeclaredField("termination");
      termination.setAccessible(true);
      ((CompletableFuture<?>)termination.get(runtime)).get(8, TimeUnit.SECONDS);
      System.out.println("EARLY_DISPOSE_PASS=true");
      PluginCefSmoke.check(platformState == CefApp.getState() && platformRemote == CefApp.isRemoteEnabled(), "PLATFORM_JCEF_UNCHANGED");
      System.out.println("PORTABLE_NATIVE_PASS=true");
      exit = 0;
    } catch (Throwable failureDuringSmoke) {
      failureDuringSmoke.printStackTrace();
    } finally {
      if (runtime != null) runtime.dispose();
      Thread.sleep(1000);
      System.exit(exit);
    }
  }

  static void await(LinuxDoBrowser browser, LinuxDoJSQuery query, String condition, String name) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    do {
      JsonObject result = PluginCefSmoke.evaluate(browser, query, "({valid:" + condition + "})");
      if (result.get("valid").getAsBoolean()) { PluginCefSmoke.check(true, name); return; }
      Thread.sleep(100);
    } while (System.nanoTime() < deadline);
    throw new AssertionError(name);
  }
}
