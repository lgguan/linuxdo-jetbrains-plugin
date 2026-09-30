import com.lgguan.linuxdo.plugin.net.*;
import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState;
import com.intellij.openapi.application.*;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;
public class StandaloneSmokeApplication {
  static void installApplication(LinuxDoSettingsState settings) {
    // Load the empty jar before installing a mock application: never read PasswordSafe.
    LinuxDoHttpClient.INSTANCE.getCookieJar().loadForRequest(okhttp3.HttpUrl.get("https://linux.do/"));
    ModalityState modality = new ModalityState() {
      public boolean dominates(ModalityState other) { return false; }
      public String toString() { return "smoke"; }
    };
    LinuxDoPluginLifetime lifetime = new LinuxDoPluginLifetime();
    Application app = (Application)java.lang.reflect.Proxy.newProxyInstance(Application.class.getClassLoader(), new Class<?>[]{Application.class}, (proxy, method, arguments) -> {
      switch (method.getName()) {
        case "hashCode": return System.identityHashCode(proxy);
        case "equals": return proxy == arguments[0];
        case "toString": return "LinuxDoSmokeApplication";
        case "getService": return arguments[0] == LinuxDoSettingsState.class ? settings : arguments[0] == LinuxDoPluginLifetime.class ? lifetime : null;
        case "invokeLater": SwingUtilities.invokeLater((Runnable)arguments[0]); return null;
        case "invokeAndWait": SwingUtilities.invokeAndWait((Runnable)arguments[0]); return null;
        case "executeOnPooledThread": return CompletableFuture.runAsync((Runnable)arguments[0]);
        case "isDispatchThread": return SwingUtilities.isEventDispatchThread();
      }
      if (method.getReturnType() == ModalityState.class) return modality;
      if (method.getReturnType() == boolean.class) return false;
      if (method.getReturnType() == long.class) return 0L;
      if (method.getReturnType() == int.class) return 0;
      return null;
    });
    ApplicationManager.setApplication(app);
  }
}
