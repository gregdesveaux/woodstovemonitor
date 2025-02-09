package de.joeakeem.m28BYJ48;
import static spark.Spark.*;

public class WebInterface {

    private int temperature = 0;
    private int damper = 0;
    MonitorStove parent;

    public  WebInterface(MonitorStove parent) {
        this.parent=parent;
        port(8080); // Set the port (optional, defaults to 4567)
        temperature =parent.getTemp();
        damper=parent.getDamperPosition();
        get("/", (req, res) -> {
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
                    "</style>" +
                    "</head>" +
                    "<body>" +
                    "<p id='counter'>Temperature: " + temperature + "</p>" +
                    "<p id='counter'>Damper: " + damper + "</p>" +
                    "<form action='/increment' method='post'>" +
                    "<button type='submit'>refresh</button>" +
                    "</form>" +
                    "<form action='/reset' method='post'>" +
                    "<button type='submit'>Reset burn</button>" +
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

        post("/reset", (req, res) -> {
            parent.resetBurn();
            res.redirect("/");
            return null;
        });

    }

    private  void incrementCounter() {
        temperature =parent.getTemp();
        damper=parent.getDamperPosition();
        System.out.println("Got Temp: " + temperature);
    }

}
