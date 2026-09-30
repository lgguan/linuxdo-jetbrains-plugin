import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.util.net.ProxyConfiguration;
import com.intellij.util.net.ProxySettings;
import com.intellij.util.net.HttpConfigurable;
import com.lgguan.linuxdo.plugin.net.IdeProxySettings;
import com.lgguan.linuxdo.plugin.net.PasswordSafeAttributes;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

/** Exercises the reflected public APIs against the actual target IDE, with synthetic services and credentials. */
public class IdeProxyMigrationSmoke {
  private static ProxyConfiguration configuration = ProxyConfiguration.getDirect();
  private static HttpConfigurable backingState;
  private static Credentials savedCredentials = new Credentials("proxy-user", "test-password");

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    ProxySettings settings = (ProxySettings) Proxy.newProxyInstance(ProxySettings.class.getClassLoader(),
        new Class<?>[] {ProxySettings.class}, (proxy, method, values) -> {
          if (method.getName().equals("getProxyConfiguration")) return configuration;
          throw new UnsupportedOperationException(method.getName());
        });
    Application app = (Application) Proxy.newProxyInstance(Application.class.getClassLoader(),
        new Class<?>[] {Application.class}, (proxy, method, values) -> {
          if (method.getName().equals("getService") || method.getName().equals("getServiceIfCreated")) {
            if (values[0] == ProxySettings.class) return settings;
            if (values[0] == HttpConfigurable.class) return backingState;
            return null;
          }
          if (method.getName().equals("isUnitTestMode")) return true;
          if (method.getReturnType() == boolean.class) return false;
          if (method.getReturnType() == int.class) return 0;
          return null;
        });
    ApplicationManager.setApplication(app);
    // The target IDE implements its NEW public credential API using this legacy backing state.
    // Only the test fixture references it; the distributed plugin uses ProxyCredentialStore.
    backingState = new HttpConfigurable() {
      public String getProxyLogin() { return savedCredentials == null ? null : savedCredentials.getUserName(); }
      public String getPlainProxyPassword() { return savedCredentials == null ? null : savedCredentials.getPasswordAsString(); }
      public java.net.PasswordAuthentication getGenericPassword(String host, int port) {
        throw new AssertionError("Credential lookup must use the configured proxy endpoint");
      }
    };
    backingState.PROXY_HOST = "127.0.0.1";
    backingState.PROXY_PORT = 8080;
    backingState.PROXY_AUTHENTICATION = true;

    require(IdeProxySettings.INSTANCE.current() == null, "IDE no-proxy mode must not force a proxy");
    for (ProxyConfiguration.ProxyProtocol protocol : ProxyConfiguration.ProxyProtocol.values()) {
      configuration = ProxyConfiguration.proxy(protocol, "127.0.0.1", 8080, "");
      IdeProxySettings.ManualProxy proxy = IdeProxySettings.INSTANCE.current();
      require(proxy != null, "The public proxy API must be read successfully");
      java.net.Proxy.Type expected = protocol == ProxyConfiguration.ProxyProtocol.SOCKS ? java.net.Proxy.Type.SOCKS : java.net.Proxy.Type.HTTP;
      require(proxy.javaProxy().type() == expected, "Proxy protocol changed");
      InetSocketAddress address = (InetSocketAddress) proxy.javaProxy().address();
      require(address.getHostString().equals("127.0.0.1") && address.getPort() == 8080, "Proxy endpoint changed");
      require(proxy.browserArgument().equals("--proxy-server=" + (expected == java.net.Proxy.Type.SOCKS ? "socks5" : "http") + "://127.0.0.1:8080"), "JCEF endpoint changed");
      Request request = new Request.Builder().url("https://example.test/").build();
      Response challenge = new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(407).message("Proxy Authentication Required").build();
      Request authenticated = IdeProxySettings.INSTANCE.authenticator(proxy).authenticate(null, challenge);
      require(authenticated != null && authenticated.header("Proxy-Authorization").equals(okhttp3.Credentials.basic("proxy-user", "test-password")), "Proxy credentials were not read from the current IDE API");
      savedCredentials = null;
      require(IdeProxySettings.INSTANCE.authenticator(proxy).authenticate(null, challenge) == null, "Missing credentials must not retry");
      savedCredentials = new Credentials("proxy-user", "test-password");
    }
    configuration = ProxyConfiguration.getAutodetect();
    require(IdeProxySettings.INSTANCE.current() == null, "Automatic mode must not be mistaken for a manual proxy");
    CredentialAttributes attributes = PasswordSafeAttributes.forService("IntelliJ Platform LinuxDoPlugin — test");
    require(attributes.getServiceName().equals("IntelliJ Platform LinuxDoPlugin — test") && attributes.getUserName() == null && !attributes.isPasswordMemoryOnly(), "Saved credential identity or persistence changed");
    ConfigurationException error = new ConfigurationException("DoH <invalid> & resolver", "Settings");
    require(error.getMessageHtml().toString().contains("&lt;invalid&gt; &amp; resolver"), "Configuration errors must remain visible as escaped HTML");
    System.out.println("TARGET_IDE_PROXY_MIGRATION_PASS=true");
  }
}
