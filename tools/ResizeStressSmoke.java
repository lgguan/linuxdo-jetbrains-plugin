import com.lgguan.linuxdo.plugin.net.*;
import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState;
import com.lgguan.linuxdo.plugin.common.Constants;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.util.concurrent.*;
import javax.swing.*;

/** Real displayed OSR frames, resize bursts, EDT responsiveness and owned-process loss. No forum requests. */
public class ResizeStressSmoke {
  static final Field IMAGE;
  static { try { IMAGE=LinuxDoBrowser.class.getDeclaredField("image"); IMAGE.setAccessible(true); }
    catch(Exception e) { throw new ExceptionInInitializerError(e); } }
  static final String HTML="<!doctype html><style>html,body{margin:0;width:100%;height:100%;background:#d00000}"
      + "@media(max-width:799px){html,body{background:#00c800}}</style><body></body>";
  static JFrame frame;
  static long maxEdtMs;
  public static void main(String[] args) throws Exception {
    LinuxDoSettingsState settings=LinuxDoSettingsState.Companion.getInstance();
    settings.setDohProvider(Constants.DohProvider.DISABLED);
    StandaloneSmokeApplication.installApplication(settings);
    IsolatedCefRuntime runtime=null;
    int exit=1;
    try {
      runtime=IsolatedCefRuntime.Companion.get();
      if(System.getProperty("os.name").startsWith("Windows")) {
        Field app=IsolatedCefRuntime.class.getDeclaredField("app");app.setAccessible(true);
        Object server=runtime.call(app.get(runtime),"getServer",new Object[0]);
        Object transport=runtime.call(server,"getThriftServer",new Object[0]);
        int port=(Integer)runtime.call(transport,"getPort",new Object[0]);
        check((Boolean)runtime.call(transport,"isTcp",new Object[0]) && port>=49152,"PRIVATE_WINDOWS_TCP_TRANSPORT");
        Class<?> platformTransport=Class.forName("com.jetbrains.cef.remote.ThriftTransport",true,org.cef.CefApp.class.getClassLoader());
        for(String name:new String[]{"ourDefaultServer","ourDefaultClient"}) {
          Object endpoint=platformTransport.getField(name).get(null);
          check(port!=(Integer)runtime.call(endpoint,"getPort",new Object[0]),"EXCLUDES_IDE_"+name);
        }
      }
      LinuxDoBrowser browser=new LinuxDoBrowser(runtime,false);
      LinuxDoBrowser initial=browser;
      SwingUtilities.invokeAndWait(()-> { frame=new JFrame("LinuxDo resize regression");
        frame.setContentPane(initial.getComponent()); frame.setSize(1024,768); frame.setVisible(true); });
      browser.loadHTML(HTML);
      awaitPaint(browser,20);
      long heapBefore=usedHeap();
      java.util.List<java.lang.ref.WeakReference<LinuxDoBrowser>> retired=new java.util.ArrayList<>();
      for(int round=0;round<12;round++) {
        for(int i=0;i<300;i++) {
          int width=360+(i*71+round*17)%1200, height=300+(i*43)%650;
          resize(width,height);
          Thread.sleep(5);
        }
        resize(round%2==0?600:1100,700);
        awaitPaint(browser,10);
        check(true,"RESIZE_BURST_PAINT_"+round);
      }
      long heapAfter=usedHeap();
      System.out.println("HEAP_BEFORE_BYTES="+heapBefore+"\nHEAP_AFTER_BYTES="+heapAfter+"\nMAX_EDT_MS="+maxEdtMs);
      check(heapAfter-heapBefore<96L*1024*1024,"HEAP_BOUNDED_AFTER_3600_RESIZES");
      check(maxEdtMs<2000,"EDT_RESPONSIVE");
      for(int i=0;i<8;i++) {
        LinuxDoBrowser next=new LinuxDoBrowser(runtime,false);
        retired.add(new java.lang.ref.WeakReference<>(browser));
        browser.dispose();
        SwingUtilities.invokeAndWait(()-> { frame.setContentPane(next.getComponent()); frame.validate(); });
        next.loadHTML(HTML);
        awaitPaint(next,15);
        browser=next;
      }
      check(true,"NEW_POSTS_AFTER_RESIZE");
      usedHeap();
      long retained=retired.stream().filter(reference->reference.get()!=null).count();
      System.out.println("RETIRED_VIEWS_RETAINED="+retained);
      // The first view may remain a live local in an interpreted main method.
      check(retained<=1,"CLOSED_VIEWS_RELEASED");
      if(java.util.Arrays.asList(args).contains("--recovery")) {
        Method processMethod=IsolatedCefRuntime.class.getDeclaredMethod("nativeProcess"); processMethod.setAccessible(true);
        Process process=(Process)processMethod.invoke(runtime);
        check(process!=null && process.isAlive(),"OWNED_PROCESS_ALIVE");
        java.util.List<ProcessHandle> children=process.descendants().toList();
        process.destroyForcibly().waitFor(5,TimeUnit.SECONDS);
        for(ProcessHandle child:children)if(child.isAlive())child.destroyForcibly();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(!browser.isDisposed() && System.nanoTime()<deadline)Thread.sleep(25);
        check(browser.isDisposed(),"DEAD_RUNTIME_DISPOSES_VIEWS");
        check(IsolatedCefRuntime.Companion.currentOrNull()==null,"DEAD_RUNTIME_NOT_REUSED");
        IsolatedCefRuntime previous=runtime;
        runtime=IsolatedCefRuntime.Companion.get();
        check(previous!=runtime,"FRESH_RUNTIME_AFTER_PROCESS_LOSS");
        LinuxDoBrowser recovered=new LinuxDoBrowser(runtime,false);
        SwingUtilities.invokeAndWait(()-> { frame.setContentPane(recovered.getComponent()); frame.validate(); });
        recovered.loadHTML(HTML); awaitPaint(recovered,20);
        check(true,"PAINT_AFTER_PROCESS_RECOVERY");
        recovered.dispose();
      } else browser.dispose();
      check(true,"RESIZE_STRESS_PASS"); exit=0;
    } catch(Throwable e) { e.printStackTrace(); }
    finally {
      if(frame!=null)SwingUtilities.invokeAndWait(()->frame.dispose());
      if(runtime!=null) {
        runtime.dispose();
        Field terminated=IsolatedCefRuntime.class.getDeclaredField("termination"); terminated.setAccessible(true);
        try { ((CompletableFuture<?>)terminated.get(runtime)).get(8,TimeUnit.SECONDS); }
        catch(Exception e) { e.printStackTrace(); exit=1; }
      }
      System.exit(exit);
    }
  }
  static void resize(int width,int height)throws Exception {
    long start=System.nanoTime();
    SwingUtilities.invokeAndWait(()->frame.setSize(width,height));
    maxEdtMs=Math.max(maxEdtMs,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
  }
  static void awaitPaint(LinuxDoBrowser browser,int seconds)throws Exception {
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
    do {
      // Real bitmap size AND CSS-responsive pixel. No wheel, click or JavaScript to force painting.
      Dimension size=browser.getComponent().getSize();
      Field imageLock=LinuxDoBrowser.class.getDeclaredField("imageLock");imageLock.setAccessible(true);
      synchronized(imageLock.get(browser)) {
        BufferedImage image=(BufferedImage)IMAGE.get(browser);
        if(image!=null && image.getWidth()==size.width && image.getHeight()==size.height
            && (image.getRGB(size.width/2,size.height/2)&0xffffff)==(size.width<800?0x00c800:0xd00000))return;
      }
      Thread.sleep(25);
    }while(System.nanoTime()<deadline);
    BufferedImage image=(BufferedImage)IMAGE.get(browser);
    throw new AssertionError("No fresh paint without scrolling: view="+browser.getComponent().getSize()
        +" image="+(image==null?null:image.getWidth()+"x"+image.getHeight()));
  }
  static long usedHeap()throws Exception { System.gc();Thread.sleep(250);return Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory(); }
  static void check(boolean condition,String name) { PluginCefSmoke.check(condition,name); }
}
