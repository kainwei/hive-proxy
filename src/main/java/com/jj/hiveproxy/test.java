package com.jj.hiveproxy;

/**
 * Created by weizh on 2017/1/16.
 */
public class test {
    public static void printM(){
        System.out.println("research log : "+ Thread.currentThread().getStackTrace()[2].getMethodName());
    }

    public String seeMem(){
        String [] a = {"1", "2"};
        System.out.println("internal : " + java.lang.System.identityHashCode(this));

        return a.toString();
    }
    public static void main(String [] args ) {
        test out1 = new test();
        out1.seeMem();
        System.out.println("out1 : " + java.lang.System.identityHashCode(out1));
        test out2 = new test();
        out2.seeMem();
        out1.seeMem();
        System.out.println("out2 : " + java.lang.System.identityHashCode(out2));
        //printM();
    }
}
