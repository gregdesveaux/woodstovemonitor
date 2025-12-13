package de.joeakeem.m28BYJ48;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static spark.Spark.*;

public class SparkButtonExample {

    private static final Logger logger = LoggerFactory.getLogger(SparkButtonExample.class);
    private static int counter = 0;

    public static void main(String[] args) {
        port(8080); // Set the port (optional, defaults to 4567)

        get("/", (req, res) -> {
            String html = "<!DOCTYPE html>" +
                    "<html>" +
                    "<head>" +
                    "<title>Spark Button Example</title>" +
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
                    "<p id='counter'>Counter: " + counter + "</p>" +
                    "<form action='/increment' method='post'>" +
                    "<button type='submit'>Increment</button>" +
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
    }

    private static void incrementCounter() {
        counter++;
        logger.info("Counter incremented: {}", counter);
    }

}
