import com.lgguan.linuxdo.plugin.net.*;
import com.lgguan.linuxdo.plugin.common.Constants;
import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState;
import com.intellij.ui.jcef.JBCefCookie;
import com.google.gson.*;
import org.cef.CefApp;
import org.cef.browser.*;
import org.cef.handler.*;
import java.awt.event.*;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;

/** Production browser code against target IDE native JCEF, without real credentials. */
public class PluginCefSmoke {
  static final BlockingQueue<String> replies = new LinkedBlockingQueue<>();
  static void check(boolean value, String name) {
    System.out.println(name + "=" + value);
    if (!value) throw new AssertionError(name);
  }
  static JsonObject evaluate(LinuxDoBrowser browser, LinuxDoJSQuery query, String expression) throws Exception {
    replies.clear();
    browser.getCefBrowser().executeJavaScript(
      "(async()=>{try{var result=await (" + expression + ");" + query.inject("JSON.stringify(result)") +
      "}catch(e){" + query.inject("JSON.stringify({error:e.name})") + "}})()", browser.getCefBrowser().getURL(), 0);
    String reply = replies.poll(30, TimeUnit.SECONDS);
    if (reply == null) throw new AssertionError("JS reply timed out");
    JsonObject result = JsonParser.parseString(reply).getAsJsonObject();
    if (result.has("error")) throw new AssertionError("JS error: " + result.get("error"));
    return result;
  }
  static LinuxDoJSQuery query(LinuxDoBrowser browser) {
    LinuxDoJSQuery query = LinuxDoJSQuery.Companion.create(browser);
    query.addHandler(result -> { replies.add(result); return null; });
    return query;
  }
  static void testBridge() throws Exception {
    for (String endpoint : new String[]{"site", "latest"}) {
    LinuxDoJcefBridge.BridgeRequest request = new LinuxDoJcefBridge.BridgeRequest(
      java.util.UUID.randomUUID().toString(), "https://linux.do/" + endpoint + ".json", "GET", java.util.Map.of("Accept", "application/json"),
      null, false, null, null, null, "composer", false, SessionEpoch.INSTANCE.getCurrent());
    java.lang.reflect.Method method = java.util.Arrays.stream(LinuxDoJcefBridge.class.getMethods())
      .filter(m -> m.getName().startsWith("execute-") && m.getParameterCount() == 2).findFirst().orElseThrow();
    Object result = method.invoke(LinuxDoJcefBridge.INSTANCE, request, 30L);
    kotlin.ResultKt.throwOnFailure(result);
    LinuxDoJcefBridge.BridgeResponse response = (LinuxDoJcefBridge.BridgeResponse)result;
    check(response.getSuccess() && response.getStatus() == 200 && JsonParser.parseString(response.getBody()).getAsJsonObject().has(endpoint.equals("site") ? "categories" : "topic_list"), "PLUGIN_PRODUCTION_BRIDGE_" + endpoint.toUpperCase());
    }
  }
  public static void main(String[] args) throws Exception {
    IsolatedCefRuntime runtime = null;
    int exit = 1;
    Object platformState = CefApp.getState();
    boolean platformRemote = CefApp.isRemoteEnabled();
    try {
      LinuxDoSettingsState settings = LinuxDoSettingsState.Companion.getInstance();
      settings.setDohProvider(Constants.DohProvider.CUSTOM);
      settings.setCustomDohUrl(args.length == 0 ? "https://neil.ddd.oaifree.com/query-dns" : args[0]);
      settings.setNetworkDiagnosticToken(java.util.UUID.randomUUID().toString());
      if (!Boolean.getBoolean("linuxdo.host.smoke")) Class.forName("StandaloneSmokeApplication").getDeclaredMethod("installApplication", LinuxDoSettingsState.class).invoke(null, settings);
      else check(ApplicationManager.getApplication() != null, "REAL_IDE_APPLICATION");
      runtime = IsolatedCefRuntime.Companion.get();
      testBridge();
      LinuxDoBrowser browser = new LinuxDoBrowser(runtime, false);
      JcefNetworkTrace.INSTANCE.install(browser, "smoke-login");
      browser.getComponent().setSize(1000, 720);
      LinuxDoJSQuery query = query(browser);
      CountDownLatch ready = new CountDownLatch(1);
      browser.getJbCefClient().addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
        public void onAfterCreated(CefBrowser b) { System.out.println("PLUGIN_NATIVE_CREATED"); }
      }, browser.getCefBrowser());
      browser.getJbCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
        public void onLoadEnd(CefBrowser b, CefFrame f, int status) {
          if (f.isMain() && f.getURL().startsWith("https://linux.do")) {
            System.out.println("PLUGIN_HTTP=" + status);
            if (status == 200) ready.countDown();
          }
        }
        public void onLoadError(CefBrowser b, CefFrame f, ErrorCode code, String message, String url) {
          if (f.isMain()) System.out.println("PLUGIN_LOAD_ERROR=" + code);
        }
      }, browser.getCefBrowser());
      // Exercise pending navigation before native creation (the login dialog's sequence).
      browser.loadURL("https://linux.do/login");
      browser.createImmediately();
      // A remote analytics/font request can keep window.load pending after the login UI works.
      // Probe DOM usability independently of that event; never enlarge production timeouts.
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
      boolean usable = false;
      while (!usable && System.nanoTime() < deadline) {
        browser.getCefBrowser().executeJavaScript(query.inject("JSON.stringify({ready:location.origin==='https://linux.do'&&!!document.querySelector('#login-account-name')})"), "https://linux.do", 0);
        String probe = replies.poll(1, TimeUnit.SECONDS);
        usable = probe != null && JsonParser.parseString(probe).getAsJsonObject().get("ready").getAsBoolean();
        if (!usable) Thread.sleep(200);
      }
      if (!usable) {
        System.out.println("DOM_STATE=" + evaluate(browser, query, "({origin:location.origin,title:document.title,state:document.readyState,inputs:Array.from(document.querySelectorAll('input')).map(e=>e.id)})"));
        java.lang.reflect.Field screenshotField = LinuxDoBrowser.class.getDeclaredField("image");
        screenshotField.setAccessible(true);
        java.awt.image.BufferedImage screenshot = (java.awt.image.BufferedImage)screenshotField.get(browser);
        if (screenshot != null) javax.imageio.ImageIO.write(screenshot, "png", new java.io.File("build/private-login-failed.png"));
      }
      check(usable, "PLUGIN_READY");
      JsonObject api = evaluate(browser, query,
        "(async()=>{let r=await fetch('/site.json');let j=await r.json();return {status:r.status,valid:!!j.categories}})()");
      check(api.get("status").getAsInt() == 200 && api.get("valid").getAsBoolean(), "PLUGIN_API");
      JsonObject field = evaluate(browser, query,
        "new Promise((resolve,reject)=>{let n=0;let t=setInterval(()=>{let e=document.querySelector('#login-account-name');" +
        "if(e){clearInterval(t);let r=e.getBoundingClientRect();resolve({x:r.x+r.width/2,y:r.y+r.height/2})}" +
        "else if(++n>100){clearInterval(t);reject(Error('input'))}},100)})");
      int x = field.get("x").getAsInt(), y = field.get("y").getAsInt();
      // Dispatch through production Swing listeners; never submit the login form.
      SwingUtilities.invokeAndWait(() -> {
        browser.getCefBrowser().setFocus(true);
        for (int id : new int[]{MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED}) {
          MouseEvent event = new MouseEvent(browser.getComponent(), id, System.currentTimeMillis(), 0, x, y, 1, false, MouseEvent.BUTTON1);
          browser.getComponent().dispatchEvent(event);
        }
        for (char c : "dohsmoke".toCharArray()) {
          for (KeyListener listener : browser.getComponent().getKeyListeners()) {
            listener.keyPressed(new KeyEvent(browser.getComponent(), KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.getExtendedKeyCodeForChar(c), c));
            listener.keyTyped(new KeyEvent(browser.getComponent(), KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, c));
            listener.keyReleased(new KeyEvent(browser.getComponent(), KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, KeyEvent.getExtendedKeyCodeForChar(c), c));
          }
        }
      });
      Thread.sleep(300);
      check(evaluate(browser, query, "({valid:document.querySelector('#login-account-name').value==='dohsmoke'})").get("valid").getAsBoolean(), "PLUGIN_INPUT");
      evaluate(browser, query, "(()=>{document.querySelector('#login-account-name').value='';return {ok:true}})()");
      String cookieName = "linuxdo_smoke_" + System.nanoTime();
      browser.getJbCefCookieManager().setCookie("https://linux.do/", new JBCefCookie(cookieName, "synthetic", "linux.do", "/", true, false));
      CountDownLatch cookieSeen = new CountDownLatch(1);
      browser.getJbCefCookieManager().visitAllCookies((cookie, index, total, ignored) -> {
        if (cookie.name.equals(cookieName) && cookie.value.equals("synthetic")) cookieSeen.countDown();
        return true;
      });
      check(cookieSeen.await(5, TimeUnit.SECONDS), "PLUGIN_COOKIE_MANAGER");
      java.lang.reflect.Field imageField = LinuxDoBrowser.class.getDeclaredField("image");
      imageField.setAccessible(true);
      java.awt.image.BufferedImage image = (java.awt.image.BufferedImage)imageField.get(browser);
      check(image != null, "PLUGIN_PAINT");
      javax.imageio.ImageIO.write(image, "png", new java.io.File("build/private-login-smoke.png"));
      String iconUrl = evaluate(browser, query, "({url:document.querySelector('link[rel=icon]').href})").get("url").getAsString();
      LinuxDoBrowser document = new LinuxDoBrowser(runtime, false);
      document.getComponent().setSize(1000, 720);
      LinuxDoJSQuery docQuery = query(document);
      CountDownLatch docReady = new CountDownLatch(1);
      document.getJbCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
        public void onLoadEnd(CefBrowser b, CefFrame f, int status) { if (f.isMain() && status == 200) docReady.countDown(); }
      }, document.getCefBrowser());
      document.loadHTML("<!doctype html><html><body><h1>Plugin document</h1><img id=probe src=\"" + iconUrl.replace("&", "&amp;").replace("\"", "&quot;") + "\"></body></html>");
      document.createImmediately();
      check(docReady.await(30, TimeUnit.SECONDS), "PLUGIN_DOCUMENT");
      JsonObject doc = evaluate(document, docQuery,
        "({origin:location.origin,cookie:document.cookie.includes('" + cookieName + "=synthetic'),image:document.getElementById('probe').naturalWidth>0})");
      check(doc.get("origin").getAsString().equals("https://linux.do"), "PLUGIN_DOCUMENT_ORIGIN");
      check(doc.get("cookie").getAsBoolean(), "PLUGIN_SHARED_COOKIES");
      check(doc.get("image").getAsBoolean(), "PLUGIN_IMAGE");
      browser.getJbCefCookieManager().deleteCookies("https://linux.do/", cookieName);
      check(!evaluate(document, docQuery, "({cookie:document.cookie.includes('" + cookieName + "=')})").get("cookie").getAsBoolean(), "PLUGIN_COOKIE_DELETE");
      docQuery.dispose();
      docQuery.dispose();
      document.dispose();
      query.dispose();
      browser.dispose();
      check(platformState == CefApp.getState() && platformRemote == CefApp.isRemoteEnabled(), "PLATFORM_JCEF_UNCHANGED");
      exit = 0;
    } catch (Throwable t) { t.printStackTrace(); }
    finally {
      LinuxDoJcefBridge.INSTANCE.dispose();
      if (runtime != null) runtime.dispose();
      Thread.sleep(3500);
      if (!Boolean.getBoolean("linuxdo.host.smoke")) System.exit(exit);
      else if (exit != 0) throw new AssertionError("Host native smoke failed");
    }
  }
}
