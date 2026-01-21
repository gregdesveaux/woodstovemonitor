package de.joeakeem.m28BYJ48;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static spark.Spark.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class WebInterface {

    private static final Logger logger = LoggerFactory.getLogger(WebInterface.class);
    private static final Path LOG_FILE = Path.of("/home/gdesveau/logs/application.log");
    private static final int LOG_TAIL_LINES = 80;
    private int temperature = 0;
    private int damper = 0;
    private int startHighTemp = 0;
    private String status = "";
    private final MonitorStove parent;

    public WebInterface(MonitorStove parent) {
        this.parent = parent;
        port(8080);
        temperature = parent.getTemp();
        damper = parent.getDamperPosition();
        startHighTemp = parent.getHighTemp();
        status = parent.getStatus();
        get("/", (req, res) -> {
            incrementCounter();
            String html = "<!DOCTYPE html>" +
                    "<html>" +
                    "<head>" +
                    "<title>Wood Stove Monitor</title>" +
                    "<meta http-equiv='refresh' content='60'>" +
                    "<style>" +
                    "body {" +
                    "  font-family: sans-serif;" +
                    "  display: flex;" +
                    "  flex-direction: column;" +
                    "  align-items: center;" +
                    "  justify-content: center;" +
                    "  min-height: 100vh;" +
                    "  margin: 0;" +
                    "}" +
                    "#status {" +
                    "  font-size: 26px;" +
                    "  margin-bottom: 20px;" +
                    "  font-weight: bold;" +
                    "}" +
                    "#counter {" +
                    "  font-size: 24px;" +
                    "  margin-bottom: 20px;" +
                    "}" +
                    "button {" +
                    "  padding: 15px 30px;" +
                    "  font-size: 20px;" +
                    "  cursor: pointer;" +
                    "  background-color: #4CAF50;" +
                    "  color: white;" +
                    "  border: none;" +
                    "  border-radius: 5px;" +
                    "}" +
                    "button:hover {" +
                    "  background-color: #3e8e41;" +
                    "}" +
                    ".restart-button {" +
                    "  background-color: #f44336;" +
                    "  margin-top: 40px;" +
                    "}" +
                    ".restart-button:hover {" +
                    "  background-color: #d32f2f;" +
                    "}" +
                    "#log-container {" +
                    "  width: 90%;" +
                    "  max-width: 900px;" +
                    "  margin-top: 40px;" +
                    "}" +
                    "#log-container h2 {" +
                    "  margin: 0 0 10px 0;" +
                    "}" +
                    "#log-output {" +
                    "  background-color: #111;" +
                    "  color: #e0e0e0;" +
                    "  padding: 15px;" +
                    "  border-radius: 6px;" +
                    "  font-size: 14px;" +
                    "  overflow-x: auto;" +
                    "  white-space: pre-wrap;" +
                    "}" +
                    "</style>" +
                    "</head>" +
                    "<body>" +
                    "<div id='status'>Status: " + status + "</div>" +
                    "<p id='counter'>Temperature: " + temperature + "</p>" +
                    "<p id='counter'>Damper: " + damper + "</p>" +
                    "<p id='counter'>Start High Temp: " + startHighTemp + "</p>" +
                    "<form action='/increment' method='post'>" +
                    "<button type='submit'>refresh</button>" +
                    "</form>" +
                    "<p id='counter'>*****************************************</p>" +
                    "<form action='/reset' method='post'>" +
                    "<button type='submit'>Reset burn</button>" +
                    "</form>" +
                    "<p id='counter'>*****************************************</p>" +
                    "<form action='/setHighTemp' method='post'>" +
                    "<label for='highTemp'>Start High Temp: </label>" +
                    "<input type='number' id='highTemp' name='highTemp' value='" + startHighTemp + "' required>" +
                    "<button type='submit'>Set Start High Temp</button>" +
                    "</form>" +
                    "<p id='counter'>*****************************************</p>" +
                    "<form action='/open' method='post'>" +
                    "<button type='submit'>Open Damper a bit</button>" +
                    "</form>" +
                    "<p id='counter'>*****************************************</p>" +
                    "<form action='/close' method='post'>" +
                    "<button type='submit'>Close Damper</button>" +
                    "</form>" +
                    "<form action='/restart' method='post'>" +
                    "<button type='submit' class='restart-button'>Restart App</button>" +
                    "</form>" +
                    "<div id='log-container'>" +
                    "<h2>Log Output</h2>" +
                    "<pre id='log-output'>" + readLogTail() + "</pre>" +
                    "</div>" +
                    "</body>" +
                    "</html>";
            res.type("text/html");
            return html;
        });


        post("/increment", (req, res) -> {
            incrementCounter();
            res.redirect("/"); // Redirect back to the main page
            return null; // Required for Spark
        });

        post("/setHighTemp", (req, res) -> {
            String highTempParam = req.queryParams("highTemp");
            if (highTempParam != null) {
                try {
                    int newHighTemp = Integer.parseInt(highTempParam);
                    parent.setHighTemp(newHighTemp);
                    startHighTemp = newHighTemp;
                    logger.info("Updated start high temp to {}", newHighTemp);
                } catch (NumberFormatException e) {
                    logger.warn("Invalid high temp value provided: {}", highTempParam);
                }
            }
            res.redirect("/");
            return null;
        });

        post("/reset", (req, res) -> {
            parent.resetBurn();
            res.redirect("/");
            return null;
        });
        post("/open", (req, res) -> {
            parent.openDamper();
            res.redirect("/");
            return null;
        });
        post("/close", (req, res) -> {
            parent.closeDamper();
            res.redirect("/");
            return null;
        });

        post("/restart", (req, res) -> {
            logger.info("Restart requested via web interface");
            res.redirect("/");
            new Thread(() -> {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                }
                System.exit(0);
            }).start();
            return null;
        });

    }

    private void incrementCounter() {
        temperature = parent.getTemp();
        damper = parent.getDamperPosition();
        startHighTemp = parent.getHighTemp();
        status = parent.getStatus();
        logger.info("Got Temp: {}", temperature);
    }

    private String readLogTail() {
        if (!Files.exists(LOG_FILE)) {
            return "Log file not found: " + escapeHtml(LOG_FILE.toString());
        }
        try {
            List<String> lines = Files.readAllLines(LOG_FILE, StandardCharsets.UTF_8);
            int startIndex = Math.max(0, lines.size() - LOG_TAIL_LINES);
            StringBuilder builder = new StringBuilder();
            for (int i = startIndex; i < lines.size(); i++) {
                builder.append(lines.get(i));
                if (i < lines.size() - 1) {
                    builder.append('\n');
                }
            }
            return escapeHtml(builder.toString());
        } catch (IOException e) {
            logger.warn("Unable to read log file from {}", LOG_FILE, e);
            return "Unable to read log file.";
        }
    }

    private String escapeHtml(String text) {
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

}
