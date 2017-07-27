package com.jj.hiveproxy;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hive.common.JvmPauseMonitor;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hive.service.server.HiveServer2;

/**
 * Created by weizh on 2017/1/13.
 */
public class ProxyServer {
    private static final Log LOG = LogFactory.getLog(HiveServer2.class);
    private static void startHiveServer2(HiveConf hiveConf) throws Throwable {
        long attempts = 0, maxAttempts = 1;
        while (true) {
            LOG.info("Starting HiveServer2");

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
               /* if (hiveConf.getBoolVar(HiveConf.ConfVars.HIVE_SERVER2_TEZ_INITIALIZE_DEFAULT_SESSIONS)) {
                    TezSessionPoolManager sessionPool = TezSessionPoolManager.getInstance();
                    sessionPool.setupPool(hiveConf);
                    sessionPool.startPool();
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
    public static void main(String [] args ) {
        System.out.println("123");
        HiveConf hc = new HiveConf();
        hc.set(HiveConf.ConfVars.HIVE_SERVER2_THRIFT_BIND_HOST.varname, "0.0.0.0");
        hc.set(HiveConf.ConfVars.HIVE_SERVER2_THRIFT_PORT.varname, "10000");
        //hc.set(HiveConf.ConfVars.HADOOPBIN.varname,"/home/abc");
        try {
            startHiveServer2(hc);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }


    }

}
