/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hive.service.server;

import org.apache.commons.cli.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.ACLProvider;
import org.apache.curator.framework.api.BackgroundCallback;
import org.apache.curator.framework.api.CuratorEvent;
import org.apache.curator.framework.api.CuratorEventType;
import org.apache.curator.framework.recipes.nodes.PersistentEphemeralNode;
import org.apache.hadoop.hive.common.JvmPauseMonitor;
import org.apache.hadoop.hive.common.LogUtils;
import org.apache.hadoop.hive.common.LogUtils.LogInitializationException;
import org.apache.hadoop.hive.common.metrics.common.MetricsFactory;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.conf.HiveConf.ConfVars;
import org.apache.hadoop.hive.shims.Utils;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hive.common.util.HiveStringUtils;
import org.apache.hive.http.HttpServer;
import org.apache.hive.service.CompositeService;
import org.apache.hive.service.ServiceException;
import org.apache.hive.service.cli.CLIService;
import org.apache.hive.service.cli.thrift.ThriftBinaryCLIService;
import org.apache.hive.service.cli.thrift.ThriftCLIService;
import org.apache.hive.service.cli.thrift.ThriftHttpCLIService;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooDefs.Perms;
import org.apache.zookeeper.data.ACL;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

/**
 * HiveServer2.
 *
 */
public class HiveServer2 extends CompositeService {
  private static final Log LOG = LogFactory.getLog(HiveServer2.class);
  private static CountDownLatch deleteSignal;

  private CLIService cliService;
  private ThriftCLIService thriftCLIService;
  private PersistentEphemeralNode znode;
  private String znodePath;
  private CuratorFramework zooKeeperClient;
  private boolean registeredWithZooKeeper = false;
  private HttpServer webServer; // Web UI

  public HiveServer2() {
    super(HiveServer2.class.getSimpleName());
    HiveConf.setLoadHiveServer2Config(true);
  }

  @Override
  public synchronized void init(HiveConf hiveConf) {
    //Initialize metrics first, as some metrics are for initialization stuff.
    try {
      if (hiveConf.getBoolVar(ConfVars.HIVE_SERVER2_METRICS_ENABLED)) {
        MetricsFactory.init(hiveConf);
      }
    } catch (Throwable t) {
      LOG.warn("Could not initiate the HiveServer2 Metrics system.  Metrics may not be reported.", t);
    }

    /*cliService = new CLIService(this);
    addService(cliService);*/
    if (isHTTPTransportMode(hiveConf)) {
      thriftCLIService = new ThriftHttpCLIService(cliService);
    } else {
      thriftCLIService = new ThriftBinaryCLIService(cliService);
    }
    addService(thriftCLIService);
    super.init(hiveConf);
    // Set host name in hiveConf
    try {
      hiveConf.set(HiveConf.ConfVars.HIVE_SERVER2_THRIFT_BIND_HOST.varname, getServerHost());
    } catch (Throwable t) {
      throw new Error("Unable to intitialize HiveServer2", t);
    }
    // Setup web UI
    /*try {
      if (hiveConf.getBoolVar(ConfVars.HIVE_IN_TEST)) {
        LOG.info("Web UI is disabled since in test mode");
      } else {
        int webUIPort =
          hiveConf.getIntVar(ConfVars.HIVE_SERVER2_WEBUI_PORT);
        if (webUIPort <= 0) {
          LOG.info("Web UI is disabled since port is set to " + webUIPort);
        } else {
          HttpServer.Builder builder = new HttpServer.Builder();
          builder.setName("hiveserver2").setPort(webUIPort).setConf(hiveConf);
          builder.setHost(hiveConf.getVar(ConfVars.HIVE_SERVER2_WEBUI_BIND_HOST));
          builder.setMaxThreads(
            hiveConf.getIntVar(ConfVars.HIVE_SERVER2_WEBUI_MAX_THREADS));
          builder.setAdmins(hiveConf.getVar(ConfVars.USERS_IN_ADMIN_ROLE));
          // SessionManager is initialized
          builder.setContextAttribute("hive.sm",
            cliService.getSessionManager());
          hiveConf.set("startcode",
            String.valueOf(System.currentTimeMillis()));
          if (hiveConf.getBoolVar(ConfVars.HIVE_SERVER2_WEBUI_USE_SSL)) {
            String keyStorePath = hiveConf.getVar(
              ConfVars.HIVE_SERVER2_WEBUI_SSL_KEYSTORE_PATH);
            if (Strings.isNullOrEmpty(keyStorePath)) {
              throw new IllegalArgumentException(
                ConfVars.HIVE_SERVER2_WEBUI_SSL_KEYSTORE_PATH.varname
                  + " Not configured for SSL connection");
            }
            builder.setKeyStorePassword(ShimLoader.getHadoopShims().getPassword(
              hiveConf, ConfVars.HIVE_SERVER2_WEBUI_SSL_KEYSTORE_PASSWORD.varname));
            builder.setKeyStorePath(keyStorePath);
            builder.setUseSSL(true);
          }
          if (hiveConf.getBoolVar(ConfVars.HIVE_SERVER2_WEBUI_USE_SPNEGO)) {
            String spnegoPrincipal = hiveConf.getVar(
                ConfVars.HIVE_SERVER2_WEBUI_SPNEGO_PRINCIPAL);
            String spnegoKeytab = hiveConf.getVar(
                ConfVars.HIVE_SERVER2_WEBUI_SPNEGO_KEYTAB);
            if (Strings.isNullOrEmpty(spnegoPrincipal) || Strings.isNullOrEmpty(spnegoKeytab)) {
              throw new IllegalArgumentException(
                ConfVars.HIVE_SERVER2_WEBUI_SPNEGO_PRINCIPAL.varname
                  + "/" + ConfVars.HIVE_SERVER2_WEBUI_SPNEGO_KEYTAB.varname
                  + " Not configured for SPNEGO authentication");
            }
            builder.setSPNEGOPrincipal(spnegoPrincipal);
            builder.setSPNEGOKeytab(spnegoKeytab);
            builder.setUseSPNEGO(true);
          }
          webServer = builder.build();
          webServer.addServlet("query_page", "/query_page", QueryProfileServlet.class);
        }
      }
    } catch (IOException ie) {
      throw new ServiceException(ie);
    }*/
    // Add a shutdown hook for catching SIGTERM & SIGINT
    final HiveServer2 hiveServer2 = this;
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        hiveServer2.stop();
      }
    });
  }

  public static boolean isHTTPTransportMode(HiveConf hiveConf) {
    String transportMode = System.getenv("HIVE_SERVER2_TRANSPORT_MODE");
    if (transportMode == null) {
      transportMode = hiveConf.getVar(HiveConf.ConfVars.HIVE_SERVER2_TRANSPORT_MODE);
    }
    if (transportMode != null && (transportMode.equalsIgnoreCase("http"))) {
      return true;
    }
    return false;
  }

  public static boolean isKerberosAuthMode(HiveConf hiveConf) {
    String authMode = hiveConf.getVar(HiveConf.ConfVars.HIVE_SERVER2_AUTHENTICATION);
    if (authMode != null && (authMode.equalsIgnoreCase("KERBEROS"))) {
      return true;
    }
    return false;
  }

  /**
   * ACLProvider for providing appropriate ACLs to CuratorFrameworkFactory
   */
  private final ACLProvider zooKeeperAclProvider = new ACLProvider() {
    List<ACL> nodeAcls = new ArrayList<ACL>();

    @Override
    public List<ACL> getDefaultAcl() {
      if (UserGroupInformation.isSecurityEnabled()) {
        // Read all to the world
        nodeAcls.addAll(Ids.READ_ACL_UNSAFE);
        // Create/Delete/Write/Admin to the authenticated user
        nodeAcls.add(new ACL(Perms.ALL, Ids.AUTH_IDS));
      } else {
        // ACLs for znodes on a non-kerberized cluster
        // Create/Read/Delete/Write/Admin to the world
        nodeAcls.addAll(Ids.OPEN_ACL_UNSAFE);
      }
      return nodeAcls;
    }

    @Override
    public List<ACL> getAclForPath(String path) {
      return getDefaultAcl();
    }
  };



  /**
   * Add conf keys, values that HiveServer2 will publish to ZooKeeper.
   * @param hiveConf
   */
  private void addConfsToPublish(HiveConf hiveConf, Map<String, String> confsToPublish) {
    // Hostname
    confsToPublish.put(ConfVars.HIVE_SERVER2_THRIFT_BIND_HOST.varname,
        hiveConf.getVar(ConfVars.HIVE_SERVER2_THRIFT_BIND_HOST));
    // Transport mode
    confsToPublish.put(ConfVars.HIVE_SERVER2_TRANSPORT_MODE.varname,
        hiveConf.getVar(ConfVars.HIVE_SERVER2_TRANSPORT_MODE));
    // Transport specific confs
    if (isHTTPTransportMode(hiveConf)) {
      confsToPublish.put(ConfVars.HIVE_SERVER2_THRIFT_HTTP_PORT.varname,
          hiveConf.getVar(ConfVars.HIVE_SERVER2_THRIFT_HTTP_PORT));
      confsToPublish.put(ConfVars.HIVE_SERVER2_THRIFT_HTTP_PATH.varname,
          hiveConf.getVar(ConfVars.HIVE_SERVER2_THRIFT_HTTP_PATH));
    } else {
      confsToPublish.put(ConfVars.HIVE_SERVER2_THRIFT_PORT.varname,
          hiveConf.getVar(ConfVars.HIVE_SERVER2_THRIFT_PORT));
      confsToPublish.put(ConfVars.HIVE_SERVER2_THRIFT_SASL_QOP.varname,
          hiveConf.getVar(ConfVars.HIVE_SERVER2_THRIFT_SASL_QOP));
    }
    // Auth specific confs
    confsToPublish.put(ConfVars.HIVE_SERVER2_AUTHENTICATION.varname,
        hiveConf.getVar(ConfVars.HIVE_SERVER2_AUTHENTICATION));
    if (isKerberosAuthMode(hiveConf)) {
      confsToPublish.put(ConfVars.HIVE_SERVER2_KERBEROS_PRINCIPAL.varname,
          hiveConf.getVar(ConfVars.HIVE_SERVER2_KERBEROS_PRINCIPAL));
    }
    // SSL conf
    confsToPublish.put(ConfVars.HIVE_SERVER2_USE_SSL.varname,
        hiveConf.getVar(ConfVars.HIVE_SERVER2_USE_SSL));
  }

  /**
   * For a kerberized cluster, we dynamically set up the client's JAAS conf.
   *
   * @param hiveConf
   * @return
   * @throws Exception
   */
  private void setUpZooKeeperAuth(HiveConf hiveConf) throws Exception {
    if (UserGroupInformation.isSecurityEnabled()) {
      String principal = hiveConf.getVar(ConfVars.HIVE_SERVER2_KERBEROS_PRINCIPAL);
      if (principal.isEmpty()) {
        throw new IOException("HiveServer2 Kerberos principal is empty");
      }
      String keyTabFile = hiveConf.getVar(ConfVars.HIVE_SERVER2_KERBEROS_KEYTAB);
      if (keyTabFile.isEmpty()) {
        throw new IOException("HiveServer2 Kerberos keytab is empty");
      }
      // Install the JAAS Configuration for the runtime
      Utils.setZookeeperClientKerberosJaasConfig(principal, keyTabFile);
    }
  }

  /**
   * The watcher class which sets the de-register flag when the znode corresponding to this server
   * instance is deleted. Additionally, it shuts down the server if there are no more active client
   * sessions at the time of receiving a 'NodeDeleted' notification from ZooKeeper.
   */
  private class DeRegisterWatcher implements Watcher {
    @Override
    public void process(WatchedEvent event) {
      if (event.getType().equals(Event.EventType.NodeDeleted)) {
        if (znode != null) {
          try {
            znode.close();
            LOG.warn("This HiveServer2 instance is now de-registered from ZooKeeper. "
                + "The server will be shut down after the last client sesssion completes.");
          } catch (IOException e) {
            LOG.error("Failed to close the persistent ephemeral znode", e);
          } finally {
            HiveServer2.this.setRegisteredWithZooKeeper(false);
            // If there are no more active client sessions, stop the server
            if (cliService.getSessionManager().getOpenSessionCount() == 0) {
              LOG.warn("This instance of HiveServer2 has been removed from the list of server "
                  + "instances available for dynamic service discovery. "
                  + "The last client session has ended - will shutdown now.");
              HiveServer2.this.stop();
            }
          }
        }
      }
    }
  }

  private void removeServerInstanceFromZooKeeper() throws Exception {
    setRegisteredWithZooKeeper(false);
    if (znode != null) {
      znode.close();
    }
    zooKeeperClient.close();
    LOG.info("Server instance removed from ZooKeeper.");
  }

  public boolean isRegisteredWithZooKeeper() {
    return registeredWithZooKeeper;
  }

  private void setRegisteredWithZooKeeper(boolean registeredWithZooKeeper) {
    this.registeredWithZooKeeper = registeredWithZooKeeper;
  }

  private String getServerInstanceURI() throws Exception {
    if ((thriftCLIService == null) || (thriftCLIService.getServerIPAddress() == null)) {
      throw new Exception("Unable to get the server address; it hasn't been initialized yet.");
    }
    return thriftCLIService.getServerIPAddress().getHostName() + ":"
        + thriftCLIService.getPortNumber();
  }

  private String getServerHost() throws Exception {
    if ((thriftCLIService == null) || (thriftCLIService.getServerIPAddress() == null)) {
      throw new Exception("Unable to get the server address; it hasn't been initialized yet.");
    }
    return thriftCLIService.getServerIPAddress().getHostName();
  }

  @Override
  public synchronized void start() {
    super.start();
    if (webServer != null) {
      try {
        webServer.start();
        LOG.info("Web UI has started on port " + webServer.getPort());
      } catch (Exception e) {
        LOG.error("Error starting Web UI: ", e);
        throw new ServiceException(e);
      }
    }
  }

  @Override
  public synchronized void stop() {
    LOG.info("Shutting down HiveServer2");
    HiveConf hiveConf = this.getHiveConf();
    super.stop();
    if (webServer != null) {
      try {
        webServer.stop();
        LOG.info("Web UI has stopped");
      } catch (Exception e) {
        LOG.error("Error stopping Web UI: ", e);
      }
    }
    // Shutdown Metrics
    if (MetricsFactory.getInstance() != null) {
      try {
        MetricsFactory.close();
      } catch (Exception e) {
        LOG.error("error in Metrics deinit: " + e.getClass().getName() + " "
          + e.getMessage(), e);
      }
    }
    // Remove this server instance from ZooKeeper if dynamic service discovery is set
    if (hiveConf.getBoolVar(ConfVars.HIVE_SERVER2_SUPPORT_DYNAMIC_SERVICE_DISCOVERY)) {
      try {
        removeServerInstanceFromZooKeeper();
      } catch (Exception e) {
        LOG.error("Error removing znode for this HiveServer2 instance from ZooKeeper.", e);
      }
    }
    // There should already be an instance of the session pool manager.
    // If not, ignoring is fine while stopping HiveServer2.



  }

  private static void startHiveServer2() throws Throwable {
    long attempts = 0, maxAttempts = 1;
    while (true) {
      LOG.info("Starting HiveServer2");
      HiveConf hiveConf = new HiveConf();
      maxAttempts = hiveConf.getLongVar(HiveConf.ConfVars.HIVE_SERVER2_MAX_START_ATTEMPTS);
      HiveServer2 server = null;
      try {
        server = new HiveServer2();
        server.init(hiveConf);
        server.start();

        try {
          JvmPauseMonitor pauseMonitor = new JvmPauseMonitor(hiveConf);
          pauseMonitor.start();
        } catch (Throwable t) {
          LOG.warn("Could not initiate the JvmPauseMonitor thread." + " GCs and Pauses may not be " +
            "warned upon.", t);
        }

        // If we're supporting dynamic service discovery, we'll add the service uri for this
        // HiveServer2 instance to Zookeeper as a znode.
        /*if (hiveConf.getBoolVar(ConfVars.HIVE_SERVER2_SUPPORT_DYNAMIC_SERVICE_DISCOVERY)) {
          server.addServerInstanceToZooKeeper(hiveConf);
        }
        if (hiveConf.getBoolVar(ConfVars.HIVE_SERVER2_TEZ_INITIALIZE_DEFAULT_SESSIONS)) {
          TezSessionPoolManager sessionPool = TezSessionPoolManager.getInstance();
          sessionPool.setupPool(hiveConf);
          sessionPool.startPool();
        }

        if (hiveConf.getVar(ConfVars.HIVE_EXECUTION_ENGINE).equals("spark")) {
          SparkSessionManagerImpl.getInstance().setup(hiveConf);
        }*/
        break;
      } catch (Throwable throwable) {
        if (server != null) {
          try {
            server.stop();
          } catch (Throwable t) {
            LOG.info("Exception caught when calling stop of HiveServer2 before retrying start", t);
          } finally {
            server = null;
          }
        }
        if (++attempts >= maxAttempts) {
          throw new Error("Max start attempts " + maxAttempts + " exhausted", throwable);
        } else {
          LOG.warn("Error starting HiveServer2 on attempt " + attempts
              + ", will retry in 60 seconds", throwable);
          try {
            Thread.sleep(60L * 1000L);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
      }
    }
  }




  private static class DeleteCallBack implements BackgroundCallback {
    @Override
    public void processResult(CuratorFramework zooKeeperClient, CuratorEvent event)
        throws Exception {
      if (event.getType() == CuratorEventType.DELETE) {
        deleteSignal.countDown();
      }
    }
  }

  public static void main(String[] args) {
    HiveConf.setLoadHiveServer2Config(true);
    try {
      ServerOptionsProcessor oproc = new ServerOptionsProcessor("hiveserver2");
      ServerOptionsProcessorResponse oprocResponse = oproc.parse(args);

      // NOTE: It is critical to do this here so that log4j is reinitialized
      // before any of the other core hive classes are loaded
      String initLog4jMessage = LogUtils.initHiveLog4j();
      LOG.debug(initLog4jMessage);
      HiveStringUtils.startupShutdownMessage(HiveServer2.class, args, LOG);

      // Log debug message from "oproc" after log4j initialize properly
      LOG.debug(oproc.getDebugMessage().toString());

      // Call the executor which will execute the appropriate command based on the parsed options
      oprocResponse.getServerOptionsExecutor().execute();
    } catch (LogInitializationException e) {
      LOG.error("Error initializing log: " + e.getMessage(), e);
      System.exit(-1);
    }
  }

  /**
   * ServerOptionsProcessor.
   * Process arguments given to HiveServer2 (-hiveconf property=value)
   * Set properties in System properties
   * Create an appropriate response object,
   * which has executor to execute the appropriate command based on the parsed options.
   */
  static class ServerOptionsProcessor {
    private final Options options = new Options();
    private org.apache.commons.cli.CommandLine commandLine;
    private final String serverName;
    private final StringBuilder debugMessage = new StringBuilder();

    @SuppressWarnings("static-access")
    ServerOptionsProcessor(String serverName) {
      this.serverName = serverName;
      // -hiveconf x=y
      options.addOption(OptionBuilder
          .withValueSeparator()
          .hasArgs(2)
          .withArgName("property=value")
          .withLongOpt("hiveconf")
          .withDescription("Use value for given property")
          .create());
      // -deregister <versionNumber>
      options.addOption(OptionBuilder
          .hasArgs(1)
          .withArgName("versionNumber")
          .withLongOpt("deregister")
          .withDescription("Deregister all instances of given version from dynamic service discovery")
          .create());
      options.addOption(new Option("H", "help", false, "Print help information"));
    }

    ServerOptionsProcessorResponse parse(String[] argv) {
      try {
        commandLine = new GnuParser().parse(options, argv);
        // Process --hiveconf
        // Get hiveconf param values and set the System property values
        Properties confProps = commandLine.getOptionProperties("hiveconf");
        for (String propKey : confProps.stringPropertyNames()) {
          // save logging message for log4j output latter after log4j initialize properly
          debugMessage.append("Setting " + propKey + "=" + confProps.getProperty(propKey) + ";\n");
          System.setProperty(propKey, confProps.getProperty(propKey));
        }

        // Process --help
        if (commandLine.hasOption('H')) {
          return new ServerOptionsProcessorResponse(new HelpOptionExecutor(serverName, options));
        }

        // Process --deregister
        if (commandLine.hasOption("deregister")) {
          return new ServerOptionsProcessorResponse(new DeregisterOptionExecutor(
              commandLine.getOptionValue("deregister")));
        }
      } catch (ParseException e) {
        // Error out & exit - we were not able to parse the args successfully
        System.err.println("Error starting HiveServer2 with given arguments: ");
        System.err.println(e.getMessage());
        System.exit(-1);
      }
      // Default executor, when no option is specified
      return new ServerOptionsProcessorResponse(new StartOptionExecutor());
    }

    StringBuilder getDebugMessage() {
      return debugMessage;
    }
  }

  /**
   * The response sent back from {@link ServerOptionsProcessor#parse(String[])}
   */
  static class ServerOptionsProcessorResponse {
    private final ServerOptionsExecutor serverOptionsExecutor;

    ServerOptionsProcessorResponse(ServerOptionsExecutor serverOptionsExecutor) {
      this.serverOptionsExecutor = serverOptionsExecutor;
    }

    ServerOptionsExecutor getServerOptionsExecutor() {
      return serverOptionsExecutor;
    }
  }

  /**
   * The executor interface for running the appropriate HiveServer2 command based on parsed options
   */
  static interface ServerOptionsExecutor {
    public void execute();
  }

  /**
   * HelpOptionExecutor: executes the --help option by printing out the usage
   */
  static class HelpOptionExecutor implements ServerOptionsExecutor {
    private final Options options;
    private final String serverName;

    HelpOptionExecutor(String serverName, Options options) {
      this.options = options;
      this.serverName = serverName;
    }

    @Override
    public void execute() {
      new HelpFormatter().printHelp(serverName, options);
      System.exit(0);
    }
  }

  /**
   * StartOptionExecutor: starts HiveServer2.
   * This is the default executor, when no option is specified.
   */
  static class StartOptionExecutor implements ServerOptionsExecutor {
    @Override
    public void execute() {
      try {
        startHiveServer2();
      } catch (Throwable t) {
        LOG.fatal("Error starting HiveServer2", t);
        System.exit(-1);
      }
    }
  }

  /**
   * DeregisterOptionExecutor: executes the --deregister option by deregistering all HiveServer2
   * instances from ZooKeeper of a specific version.
   */
  static class DeregisterOptionExecutor implements ServerOptionsExecutor {
    private final String versionNumber;

    DeregisterOptionExecutor(String versionNumber) {
      this.versionNumber = versionNumber;
    }

    @Override
    public void execute() {

      System.exit(0);
    }
  }
}
