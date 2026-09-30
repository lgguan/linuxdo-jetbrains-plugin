import com.intellij.openapi.application.ApplicationStarter;
import com.lgguan.linuxdo.plugin.net.IsolatedCefRuntime;
import java.nio.file.*;
import java.util.*;

public class IdePluginSmoke implements ApplicationStarter {
  public String getCommandName() { return "linuxdo-host-smoke"; }
  public boolean isHeadless() { return true; }
  public int getRequiredModality() { return NOT_IN_EDT; }
  public void premain(List<String> args) { report(false); }
  public void main(List<String> args) { report(true); System.exit(0); }
  private void report(boolean applicationReady) {
    StringBuilder report = new StringBuilder();
    try {
      ClassLoader loader = IsolatedCefRuntime.class.getClassLoader();
      report.append("PLUGIN_LOADER=").append(loader.getClass().getName()).append('\n');
      try {
        Class<?> cef = Class.forName("org.cef.CefApp", false, loader);
        report.append("CEF_LOADER=").append(cef.getClassLoader().getClass().getName()).append('\n');
        report.append("CEF_CODE_SOURCE=").append(cef.getProtectionDomain().getCodeSource()).append('\n');
        report.append("CEF_RESOURCE=").append(cef.getResource("CefApp.class")).append('\n');
      } catch(Throwable t) { report.append("CEF_CLASS_ERROR=").append(t).append('\n'); }
      report.append("SUPPORTED=").append(IsolatedCefRuntime.Companion.isSupported()).append('\n');
      if (applicationReady && IsolatedCefRuntime.Companion.isSupported() && Boolean.getBoolean("linuxdo.host.native")) {
        PluginCefSmoke.main(new String[0]);
        report.append("HOST_NATIVE_PASS=true\n");
      }
    } catch(Throwable t) { report.append("ERROR=").append(t).append('\n'); }
    try { Files.writeString(Path.of(System.getProperty("linuxdo.host.report")), report); }
    catch(Exception e) { e.printStackTrace(); }

  }
}
