package com.jj.hiveproxy.util;

import org.glassfish.jersey.client.ClientConfig;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.MediaType;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;

/**
 * Created by weizh on 2016/8/9.
 */
public class RestFulClient {
    ClientConfig config = new ClientConfig();

    Client client = ClientBuilder.newClient(config);

    WebTarget target = client.target(getBaseURI());
    public String Client(String path, String op, String text){
        try {
            text = URLEncoder.encode(text, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        System.out.println(text);
        //path = path + "/" + text;
        String response;
        if(op.equals("post")){
            Form form = new Form();
            /*response = target.path(path).request().accept(MediaType.APPLICATION_JSON_TYPE).
                    post(Entity.entity(form,MediaType.APPLICATION_FORM_URLENCODED_TYPE)).toString();*/
            response = target.path(path).request().accept(MediaType.APPLICATION_JSON_TYPE).
                    post(Entity.entity(text,MediaType.TEXT_PLAIN_TYPE)).readEntity(String.class);
        }else if(op.equals("del")){
            response = target.path(path).
                    request().
                    accept(MediaType.APPLICATION_JSON_TYPE).delete(String.class);
        }else{
            response = target.path(path).
                    request().
                    accept(MediaType.APPLICATION_JSON_TYPE).get(String.class).toString();
        }
        return response;
    }
    public static void main(String[] args) {


        String op = "post";
        String path = "/v1/api/table/realName";
        String rString = "1";
        String res = new RestFulClient().Client(path, op, rString);

        System.out.println(res);

    }

    private static URI getBaseURI() {

        URI uri = null;
        try {
            uri = new URI("http://ip_instead_tmp:9090/");
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        return uri;
    }
}
