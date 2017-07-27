package com.jj.hiveproxy;

import org.apache.hive.jdbc.HiveConnection;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Created by weizh on 2017/1/11.
 */
public class ProxyClient {

    public void ConnectTest() {
        Properties pro = new Properties();
        String host = "localhost";
        String port = "10000";
        String url = "jdbc:hive2://" + host + ":" + port + "/bill;user=test123";
        HiveConnection hc = null;
        try {
            hc = new HiveConnection(url, pro);
            Statement stmt = hc.createStatement();
            ResultSet res = stmt.executeQuery("select * from dic.closeuser limit 5");
            ResultSetMetaData meta = res.getMetaData();

            System.out.println("Resultset has " + meta.getColumnCount() + " columns");
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                System.out.println("Column #" + i + " Name: " + meta.getColumnName(i) +
                        " Type: " + meta.getColumnType(i));
            }

            while (res.next()) {
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    System.out.println("Column #" + i + ": " + res.getString(i));
                }
            }
            res = stmt.executeQuery("show databases");
            res.close();
            stmt.close();

        } catch (SQLException e) {
            e.printStackTrace();
        }

    }

    public static void main(String [] args ){
        new ProxyClient().ConnectTest();
        Thread ct = new Thread(new ConcurrecyTest());
        //ct.start();

    }

}

class ConcurrecyTest implements Runnable{

    @Override
    public void run() {
        for(int i = 0; i<5; i++ ) {
            new ProxyClient().ConnectTest();
        }
    }
}
