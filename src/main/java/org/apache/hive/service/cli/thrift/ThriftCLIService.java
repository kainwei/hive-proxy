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

package org.apache.hive.service.cli.thrift;

import com.jj.hiveproxy.util.TransferStatement;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hive.common.ServerUtils;
import org.apache.hadoop.hive.common.metrics.common.Metrics;
import org.apache.hadoop.hive.common.metrics.common.MetricsConstant;
import org.apache.hadoop.hive.common.metrics.common.MetricsFactory;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.conf.HiveConf.ConfVars;
import org.apache.hive.jdbc.HiveConnection;
import org.apache.hive.service.AbstractService;
import org.apache.hive.service.ServiceException;
import org.apache.hive.service.ServiceUtils;
import org.apache.hive.service.auth.HiveAuthFactory;
import org.apache.hive.service.auth.TSetIpAddressProcessor;
import org.apache.hive.service.cli.*;
import org.apache.hive.service.cli.session.SessionManager;
import org.apache.hive.service.server.HiveServer2;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.server.ServerContext;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TServerEventHandler;
import org.apache.thrift.transport.TTransport;

import javax.security.auth.login.LoginException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ThriftCLIService.
 *
 */
public abstract class ThriftCLIService extends AbstractService implements TCLIService.Iface, Runnable {

  public static final Log LOG = LogFactory.getLog(ThriftCLIService.class.getName());

  protected CLIService cliService;
  protected TCLIService.Iface cliClient;
  private static final TStatus OK_STATUS = new TStatus(TStatusCode.SUCCESS_STATUS);
  protected static HiveAuthFactory hiveAuthFactory;
  private static final AtomicInteger sessionCount = new AtomicInteger();

  protected int portNum;
  protected InetAddress serverIPAddress;
  protected String hiveHost;
  protected TServer server;
  protected org.eclipse.jetty.server.Server httpServer;

  private boolean isStarted = false;
  protected boolean isEmbedded = false;

  protected HiveConf hiveConf;

  protected int minWorkerThreads;
  protected int maxWorkerThreads;
  protected long workerKeepAliveTime;

  protected TServerEventHandler serverEventHandler;
  protected ThreadLocal<ServerContext> currentServerContext;

  static class ThriftCLIServerContext implements ServerContext {
    private SessionHandle sessionHandle = null;

    public void setSessionHandle(SessionHandle sessionHandle) {
      this.sessionHandle = sessionHandle;
    }

    public SessionHandle getSessionHandle() {
      return sessionHandle;
    }
  }

  public void GetRemoteServerClient(){
      String host = "ip_instead_tmp";
      String port = "10000";
      String url = "jdbc:hive2://" + host + ":" + port + "/bill;user=hdfs;password=bar;hive.metastore.uris=thrift://ip_instead_tmp:9083";

      Properties pro = new Properties();
      HiveConnection hc = null;

      try {
          hc = new HiveConnection(url, pro);

      } catch (SQLException e) {
          e.printStackTrace();
      }
      this.cliClient = hc.client;

  }

  public ThriftCLIService(CLIService service, String serviceName) {
    super(serviceName);
    //GetRemoteServerClient();
    this.cliService = service;


    currentServerContext = new ThreadLocal<ServerContext>();
    serverEventHandler = new TServerEventHandler() {
      @Override
      public ServerContext createContext(
          TProtocol input, TProtocol output) {
        Metrics metrics = MetricsFactory.getInstance();
        if (metrics != null) {
          try {
            metrics.incrementCounter(MetricsConstant.OPEN_CONNECTIONS);
          } catch (Exception e) {
            LOG.warn("Error Reporting JDO operation to Metrics system", e);
          }
        }
        return new ThriftCLIServerContext();
      }

      @Override
      public void deleteContext(ServerContext serverContext,
          TProtocol input, TProtocol output) {
        Metrics metrics = MetricsFactory.getInstance();
        if (metrics != null) {
          try {
            metrics.decrementCounter(MetricsConstant.OPEN_CONNECTIONS);
          } catch (Exception e) {
            LOG.warn("Error Reporting JDO operation to Metrics system", e);
          }
        }
        ThriftCLIServerContext context = (ThriftCLIServerContext) serverContext;
        SessionHandle sessionHandle = context.getSessionHandle();
        if (sessionHandle != null) {
          LOG.info("Session disconnected without closing properly, close it now");
          try {
            cliService.closeSession(sessionHandle);
          } catch (HiveSQLException e) {
            LOG.warn("Failed to close session: " + e, e);
          }
        }
      }

      @Override
      public void preServe() {
      }

      @Override
      public void processContext(ServerContext serverContext,
          TTransport input, TTransport output) {
        currentServerContext.set(serverContext);
      }
    };
  }

  @Override
  public synchronized void init(HiveConf hiveConf) {
    this.hiveConf = hiveConf;

    String hiveHost = System.getenv("HIVE_SERVER2_THRIFT_BIND_HOST");
    if (hiveHost == null) {
      hiveHost = hiveConf.getVar(ConfVars.HIVE_SERVER2_THRIFT_BIND_HOST);
    }
    try {
      serverIPAddress = ServerUtils.getHostAddress(hiveHost);
    } catch (UnknownHostException e) {
      throw new ServiceException(e);
    }

    // Initialize common server configs needed in both binary & http modes
    String portString;
    // HTTP mode
    if (HiveServer2.isHTTPTransportMode(hiveConf)) {
      workerKeepAliveTime =
          hiveConf.getTimeVar(ConfVars.HIVE_SERVER2_THRIFT_HTTP_WORKER_KEEPALIVE_TIME,
              TimeUnit.SECONDS);
      portString = System.getenv("HIVE_SERVER2_THRIFT_HTTP_PORT");
      if (portString != null) {
        portNum = Integer.valueOf(portString);
      } else {
        portNum = hiveConf.getIntVar(ConfVars.HIVE_SERVER2_THRIFT_HTTP_PORT);
      }
    }
    // Binary mode
    else {
      workerKeepAliveTime =
          hiveConf.getTimeVar(ConfVars.HIVE_SERVER2_THRIFT_WORKER_KEEPALIVE_TIME, TimeUnit.SECONDS);
      portString = System.getenv("HIVE_SERVER2_THRIFT_PORT");
      if (portString != null) {
        portNum = Integer.valueOf(portString);
      } else {
        portNum = hiveConf.getIntVar(ConfVars.HIVE_SERVER2_THRIFT_PORT);
      }
    }
    minWorkerThreads = hiveConf.getIntVar(ConfVars.HIVE_SERVER2_THRIFT_MIN_WORKER_THREADS);
    maxWorkerThreads = hiveConf.getIntVar(ConfVars.HIVE_SERVER2_THRIFT_MAX_WORKER_THREADS);
    super.init(hiveConf);
  }

  public static void printM(){
    System.out.println("research log : "+ Thread.currentThread().getStackTrace()[2].getMethodName());
  }

  @Override
  public synchronized void start() {
    super.start();
    if (!isStarted && !isEmbedded) {
      new Thread(this).start();
      isStarted = true;
    }
  }

  @Override
  public synchronized void stop() {
    if (isStarted && !isEmbedded) {
      if(server != null) {
        server.stop();
        LOG.info("Thrift server has stopped");
      }
      if((httpServer != null) && httpServer.isStarted()) {
        try {
          httpServer.stop();
          LOG.info("Http server has stopped");
        } catch (Exception e) {
          LOG.error("Error stopping Http server: ", e);
        }
      }
      isStarted = false;
    }
    super.stop();
  }

  public int getPortNumber() {
    return portNum;
  }

  public InetAddress getServerIPAddress() {
    return serverIPAddress;
  }

  @Override
  public TGetDelegationTokenResp GetDelegationToken(TGetDelegationTokenReq req){
      TGetDelegationTokenResp resp = null;
      try {
          resp = cliClient.GetDelegationToken(req);
      } catch (TException e) {
          e.printStackTrace();
      }
      return resp;
  }





  @Override
  public TCancelDelegationTokenResp CancelDelegationToken(TCancelDelegationTokenReq req){
    printM();
    TCancelDelegationTokenResp  resp = null;
      try {
          resp = cliClient.CancelDelegationToken(req);
      } catch (TException e) {
          e.printStackTrace();
      }
      return resp;
  }

  @Override
  public TRenewDelegationTokenResp RenewDelegationToken(TRenewDelegationTokenReq req){
      printM();
      TRenewDelegationTokenResp resp = null;
      try {
          resp = cliClient.RenewDelegationToken(req);
      } catch (TException e) {
          e.printStackTrace();
      }
      return resp;
  }

  private TStatus unsecureTokenErrorStatus() {
    TStatus errorStatus = new TStatus(TStatusCode.ERROR_STATUS);
    errorStatus.setErrorMessage("Delegation token only supported over remote " +
        "client with kerberos authentication");
    return errorStatus;
  }

  @Override
  public TOpenSessionResp OpenSession(TOpenSessionReq req) throws TException {
    printM();
    System.out.println("thrift server  log : "+ Thread.currentThread().getStackTrace()[2].getClassName() +
            " " + Thread.currentThread().getStackTrace()[2].getMethodName());
    GetRemoteServerClient();
    Map<String, String> map = req.getConfiguration();
    for(Map.Entry<String, String> e:map.entrySet()){
      System.out.println("conf key " + e.getKey());
    }

    System.out.println("my object id : " + java.lang.System.identityHashCode(cliClient));
    System.out.println("user is  : " + req.getUsername() + "  password : " + req.getPassword()
            + " req mes " + req.toString() + " conf size " + req.getConfigurationSize());
    System.out.println("username from TSetIpAddressProcessor " + TSetIpAddressProcessor.getUserName() +
            " password from TSetIpAddressProcessor " + TSetIpAddressProcessor.getUserIpAddress() );
    System.out.println(" username from hiveAuthFactory " + hiveAuthFactory.getRemoteUser());
    System.out.println("username from SessionManager " + SessionManager.getUserName());








    TOpenSessionResp resp = cliClient.OpenSession(req);
    return resp;
  }

  private String getIpAddress() {
    printM();
    String clientIpAddress;
    // Http transport mode.
    // We set the thread local ip address, in ThriftHttpServlet.
    if (cliService.getHiveConf().getVar(
        ConfVars.HIVE_SERVER2_TRANSPORT_MODE).equalsIgnoreCase("http")) {
      clientIpAddress = SessionManager.getIpAddress();
    }
    else {
      if (hiveAuthFactory != null && hiveAuthFactory.isSASLWithKerberizedHadoop()) {
        clientIpAddress = hiveAuthFactory.getIpAddress();
      }
      // NOSASL
      else {
        clientIpAddress = TSetIpAddressProcessor.getUserIpAddress();
      }
    }
    LOG.debug("Client's IP Address: " + clientIpAddress);
    return clientIpAddress;
  }

  /**
   * Returns the effective username.
   * 1. If hive.server2.allow.user.substitution = false: the username of the connecting user
   * 2. If hive.server2.allow.user.substitution = true: the username of the end user,
   * that the connecting user is trying to proxy for.
   * This includes a check whether the connecting user is allowed to proxy for the end user.
   * @param req
   * @return
   * @throws HiveSQLException
   */
  private String getUserName(TOpenSessionReq req) throws HiveSQLException {
    printM();
    String userName = null;
    if (hiveAuthFactory != null && hiveAuthFactory.isSASLWithKerberizedHadoop()) {
      userName = hiveAuthFactory.getRemoteUser();
    }
    // NOSASL
    if (userName == null) {
      userName = TSetIpAddressProcessor.getUserName();
    }
    // Http transport mode.
    // We set the thread local username, in ThriftHttpServlet.
    if (cliService.getHiveConf().getVar(
        ConfVars.HIVE_SERVER2_TRANSPORT_MODE).equalsIgnoreCase("http")) {
      userName = SessionManager.getUserName();
    }
    if (userName == null) {
      userName = req.getUsername();
    }

    userName = getShortName(userName);
    String effectiveClientUser = getProxyUser(userName, req.getConfiguration(), getIpAddress());
    LOG.debug("Client's username: " + effectiveClientUser);
    return effectiveClientUser;
  }

  private String getShortName(String userName) {
    printM();
    String ret = null;
    if (userName != null) {
      int indexOfDomainMatch = ServiceUtils.indexOfDomainMatch(userName);
      ret = (indexOfDomainMatch <= 0) ? userName :
          userName.substring(0, indexOfDomainMatch);
    }

    return ret;
  }

  /**
   * Create a session handle
   * @param req
   * @param res
   * @return
   * @throws HiveSQLException
   * @throws LoginException
   * @throws IOException
   */
  SessionHandle getSessionHandle(TOpenSessionReq req, TOpenSessionResp res)
      throws HiveSQLException, LoginException, IOException {
    printM();
    String userName = getUserName(req);
    String ipAddress = getIpAddress();
    TProtocolVersion protocol = getMinVersion(CLIService.SERVER_VERSION,
        req.getClient_protocol());
    SessionHandle sessionHandle;
    if (cliService.getHiveConf().getBoolVar(ConfVars.HIVE_SERVER2_ENABLE_DOAS) &&
        (userName != null)) {
      String delegationTokenStr = getDelegationToken(userName);
      sessionHandle = cliService.openSessionWithImpersonation(protocol, userName,
          req.getPassword(), ipAddress, req.getConfiguration(), delegationTokenStr);

    } else {
      sessionHandle = cliService.openSession(protocol, userName, req.getPassword(),
          ipAddress, req.getConfiguration());
    }
    res.setServerProtocolVersion(protocol);
    return sessionHandle;
  }


  private String getDelegationToken(String userName)
      throws HiveSQLException, LoginException, IOException {
    printM();
    try {
      return cliService.getDelegationTokenFromMetaStore(userName);
    } catch (UnsupportedOperationException e) {
      // The delegation token is not applicable in the given deployment mode
      // such as HMS is not kerberos secured 
    }
    return null;
  }

  private TProtocolVersion getMinVersion(TProtocolVersion... versions) {
    printM();
    TProtocolVersion[] values = TProtocolVersion.values();
    int current = values[values.length - 1].getValue();
    for (TProtocolVersion version : versions) {
      if (current > version.getValue()) {
        current = version.getValue();
      }
    }
    for (TProtocolVersion version : values) {
      if (version.getValue() == current) {
        return version;
      }
    }
    throw new IllegalArgumentException("never");
  }

  @Override
  public TCloseSessionResp CloseSession(TCloseSessionReq req) throws TException {
    printM();
    TCloseSessionResp resp = cliClient.CloseSession(req);
    return resp;
  }

  @Override
  public TGetInfoResp GetInfo(TGetInfoReq req) throws TException {

    printM();

    TGetInfoResp resp = cliClient.GetInfo(req);

    return resp;
  }

  @Override
  public TExecuteStatementResp ExecuteStatement(TExecuteStatementReq req) throws TException {
    printM();
    System.out.println("thrift server  log : "+ Thread.currentThread().getStackTrace()[2].getMethodName());
    String origStatement = req.getStatement();
    System.out.println(origStatement);
    String transferedStatement = TransferStatement.TranStat(origStatement);
    System.out.println("transfered statement " + transferedStatement);
    req.setStatement(transferedStatement);
    TExecuteStatementResp resp = cliClient.ExecuteStatement(req);
    return resp;
  }

  @Override
  public TGetTypeInfoResp GetTypeInfo(TGetTypeInfoReq req) throws TException {
    printM();
    TGetTypeInfoResp resp = cliClient.GetTypeInfo(req);

    return resp;
  }

  @Override
  public TGetCatalogsResp GetCatalogs(TGetCatalogsReq req) throws TException {
    printM();
    TGetCatalogsResp resp = cliClient.GetCatalogs(req);

    return resp;
  }

  @Override
  public TGetSchemasResp GetSchemas(TGetSchemasReq req) throws TException {
    printM();
    TGetSchemasResp resp = cliClient.GetSchemas(req);

    return resp;
  }

  @Override
  public TGetTablesResp GetTables(TGetTablesReq req) throws TException {
    printM();
    TGetTablesResp resp = cliClient.GetTables(req);
    return resp;
  }

  @Override
  public TGetTableTypesResp GetTableTypes(TGetTableTypesReq req) throws TException {
    printM();
    TGetTableTypesResp resp = cliClient.GetTableTypes(req);
    return resp;
  }

  @Override
  public TGetColumnsResp GetColumns(TGetColumnsReq req) throws TException {
    printM();
    TGetColumnsResp resp = cliClient.GetColumns(req);
    return resp;
  }

  @Override
  public TGetFunctionsResp GetFunctions(TGetFunctionsReq req) throws TException {
    printM();
    TGetFunctionsResp resp = cliClient.GetFunctions(req);

    return resp;
  }

  @Override
  public TGetOperationStatusResp GetOperationStatus(TGetOperationStatusReq req) throws TException {
    printM();
    TGetOperationStatusResp resp = cliClient.GetOperationStatus(req);

    return resp;
  }

  @Override
  public TCancelOperationResp CancelOperation(TCancelOperationReq req) throws TException {
    printM();
    TCancelOperationResp resp = cliClient.CancelOperation(req);
    return resp;
  }

  @Override
  public TCloseOperationResp CloseOperation(TCloseOperationReq req) throws TException {
    printM();
    TCloseOperationResp resp = cliClient.CloseOperation(req);

    return resp;
  }

  @Override
  public TGetResultSetMetadataResp GetResultSetMetadata(TGetResultSetMetadataReq req)
      throws TException {
    printM();
    TGetResultSetMetadataResp resp = cliClient.GetResultSetMetadata(req);

    return resp;
  }

  @Override
  public TFetchResultsResp FetchResults(TFetchResultsReq req) throws TException {
    printM();

    TFetchResultsResp resp = cliClient.FetchResults(req);

    return resp;
  }

  @Override
  public abstract void run();

  /**
   * If the proxy user name is provided then check privileges to substitute the user.
   * @param realUser
   * @param sessionConf
   * @param ipAddress
   * @return
   * @throws HiveSQLException
   */
  private String getProxyUser(String realUser, Map<String, String> sessionConf,
      String ipAddress) throws HiveSQLException {
    printM();
    String proxyUser = null;
    // Http transport mode.
    // We set the thread local proxy username, in ThriftHttpServlet.
    if (cliService.getHiveConf().getVar(
        ConfVars.HIVE_SERVER2_TRANSPORT_MODE).equalsIgnoreCase("http")) {
      proxyUser = SessionManager.getProxyUserName();
      LOG.debug("Proxy user from query string: " + proxyUser);
    }

    if (proxyUser == null && sessionConf != null && sessionConf.containsKey(HiveAuthFactory.HS2_PROXY_USER)) {
      String proxyUserFromThriftBody = sessionConf.get(HiveAuthFactory.HS2_PROXY_USER);
      LOG.debug("Proxy user from thrift body: " + proxyUserFromThriftBody);
      proxyUser = proxyUserFromThriftBody;
    }

    if (proxyUser == null) {
      return realUser;
    }

    // check whether substitution is allowed
    if (!hiveConf.getBoolVar(HiveConf.ConfVars.HIVE_SERVER2_ALLOW_USER_SUBSTITUTION)) {
      throw new HiveSQLException("Proxy user substitution is not allowed");
    }

    // If there's no authentication, then directly substitute the user
    if (HiveAuthFactory.AuthTypes.NONE.toString().
        equalsIgnoreCase(hiveConf.getVar(ConfVars.HIVE_SERVER2_AUTHENTICATION))) {
      return proxyUser;
    }

    // Verify proxy user privilege of the realUser for the proxyUser
    HiveAuthFactory.verifyProxyAccess(realUser, proxyUser, ipAddress, hiveConf);
    LOG.debug("Verified proxy user: " + proxyUser);
    return proxyUser;
  }
}
