package de.joeakeem.m28BYJ48;

import spark.utils.IOUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Memo {
    private static final Logger logger = LoggerFactory.getLogger(Memo.class);
    private static final String LOCATION = "http://192.168.1.207:49153";

    public void setOn() {
        logger.info("Turning fan on");
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

        logger.info("setOn {}", resp);

    }
    public void setOff()  {
        logger.info("Turning fan off");
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

        logger.info("setOn {}", resp);

    }
    private String call(String endpoint, String soapCall, String content) {
        try {
            // String urlParameters = "param1=a&param2=b&param3=c";
            // String request = "http://example.com/index.php";
            URL url = new URL(LOCATION + endpoint);

            Socket s = new Socket(InetAddress.getByName(url.getHost()),
                    url.getPort());
            try {
                OutputStream os = s.getOutputStream();
                StringBuilder sb = new StringBuilder();

                sb.append("POST ").append(url).append(" HTTP/1.1\r\n");
                sb.append("Content-Type: text/xml; charset=utf-8\r\n");
                sb.append("Content-Length: ").append(content.getBytes().length)
                        .append("\r\n");

                sb.append("SOAPACTION: \"").append(soapCall).append("\"\r\n");
                sb.append("\r\n");

                IOException lastWriteException = null;
                for (int attempt = 1; attempt <= 5; attempt++) {
                    try {
                        os.write(sb.toString().getBytes());
                        os.write(content.getBytes());
                        os.flush();
                        lastWriteException = null;
                        break;
                    } catch (IOException e) {
                        lastWriteException = e;
                        logger.warn("Failed to write request (attempt {} of 5)", attempt, e);
                    }
                }
                if (lastWriteException != null) {
                    throw lastWriteException;
                }

                String resp = IOUtils.toString(s.getInputStream());
                return resp;

            } finally {
                s.close();
            }
        } catch (Exception e) {
            logger.info("Can't call device", e);
            return "failed";
        }

    }
}
