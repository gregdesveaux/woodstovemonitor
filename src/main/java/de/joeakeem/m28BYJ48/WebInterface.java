package de.joeakeem.m28BYJ48;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static spark.Spark.*;

public class WebInterface {

    private static final Logger logger = LoggerFactory.getLogger(WebInterface.class);
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

}
