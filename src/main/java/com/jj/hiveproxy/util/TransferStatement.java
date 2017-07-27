package com.jj.hiveproxy.util;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by weizh on 2017/1/18.
 */
public class TransferStatement {
    public static String getTranTabNameExp(String tablename){
        Map<String, String> dicMap = new HashMap<String, String>();
        dicMap.put("closeuser","src_closeuser");
        dicMap.put("forum_board","src_dimension_forum_board");
        dicMap.put("thread_type","src_dimension_thread_type");
        dicMap.put("gametype","src_gametype");
        dicMap.put("waredef","src_waredef");
        dicMap.put("产品信息","src_productinfo");
        dicMap.put("渠道信息","src_resinfo");
        if(dicMap.containsKey(tablename)){
            return dicMap.get(tablename);
        }
        return tablename;

    }

    public static String getTranTabName(String tablename){
        String op = "post";
        String path = "/v1/api/table/realName";
        String rString = tablename;
        String res = new RestFulClient().Client(path, op, rString);
        return res;

    }
    public static String TranStat(String statement) {
        statement = statement.trim();
        String [] analyseStr = statement.split(" ");
        String tablename = "";
        String newTableName = "";
        int index = 0;
        if(analyseStr[0].toLowerCase().equals("select")) {
            index = 3;
        }
        if(index!=0){
            tablename = analyseStr[index];
            String dbname = "";
            boolean withDb = false;
            if(tablename.contains(".")){
                withDb = true;
                dbname = tablename.split("\\.")[0];
                tablename = tablename.split("\\.")[1];
            }
            newTableName = getTranTabName(tablename);

            if(newTableName.split("\\.").length == 2){
                analyseStr[index] = newTableName;
            }
            StringBuffer sb = new StringBuffer();
            for(int i=0; i<analyseStr.length; i++){
                sb.append(analyseStr[i] + " ");
            }
            return sb.toString().trim();

        }


        return statement;
    }

    public static void main(String [] args ){
        System.out.println(TranStat("select * from 1"));
        System.out.println(TranStat("select * from 1"));
        System.out.println(TranStat("select * from 1"));
        System.out.println(TranStat("select * from 1"));
        System.out.println(TranStat(" select *   from 1 "));
        System.out.println(TranStat(" selebt * from 1 "));

    }
}
