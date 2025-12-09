package de.joeakeem.m28BYJ48;

import spark.utils.IOUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;

public class Memo {
    public String location = "http://192.168.1.207:49153";

    public void setOn() {
        System.out.println("Turning fan on ");
        String request = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
                "  <s:Body>\n" +
                "    <u:SetBinaryState xmlns:u=\"urn:Belkin:service:basicevent:1\">\n" +
                "      <BinaryState>1</BinaryState>\n" +
                "    </u:SetBinaryState>\n" +
                "  </s:Body>\n" +
                "</s:Envelope>";
        String resp = call(
                "/upnp/control/basicevent1",
                "urn:Belkin:service:basicevent:1#SetBinaryState",
                request);

        System.out.println("setOn " + resp);

    }
    public void setOff()  {
        System.out.println("Turning fan off");
        String request = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
                "  <s:Body>\n" +
                "    <u:SetBinaryState xmlns:u=\"urn:Belkin:service:basicevent:1\">\n" +
                "      <BinaryState>0</BinaryState>\n" +
                "    </u:SetBinaryState>\n" +
                "  </s:Body>\n" +
                "</s:Envelope>";
        String resp = call(
                "/upnp/control/basicevent1",
                "urn:Belkin:service:basicevent:1#SetBinaryState",
                request);

        System.out.println("setOn " + resp);

    }
    private String call(String endpoint, String soapCall, String content) {
        try {
            // String urlParameters = "param1=a&param2=b&param3=c";
            // String request = "http://example.com/index.php";
            URL url = new URL(this.location + endpoint);

            Socket s = new Socket(InetAddress.getByName(url.getHost()),
                    url.getPort());
            try {
                OutputStream os = s.getOutputStream();
                StringBuffer sb = new StringBuffer();

                sb.append("POST " + url + " HTTP/1.1\r\n");
                sb.append("Content-Type: text/xml; charset=utf-8\r\n");
                sb.append("Content-Length: " + content.getBytes().length
                        + "\r\n");

                sb.append("SOAPACTION: \"" + soapCall + "\"\r\n");
                sb.append("\r\n");

                os.write(sb.toString().getBytes());
                os.write(content.getBytes());

                os.flush();

                String resp = IOUtils.toString(s.getInputStream());
                return resp;

            } finally {
                s.close();
            }
        } catch (Exception e) {
            throw new RuntimeException("Can't call device", e);
        }
    }
}
