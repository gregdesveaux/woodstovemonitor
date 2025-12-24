package de.joeakeem.m28BYJ48;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalInput;
import com.pi4j.io.gpio.digital.DigitalState;
import com.pi4j.io.gpio.digital.PullResistance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Date;

/**
 * Coordinates the wood stove monitor hardware and web interface.
 */
public class MonitorStove {
    private static final Logger logger = LoggerFactory.getLogger(MonitorStove.class);
    private static final int DAMPER_FULLY_OPEN = 7000;
    private static final int DIRECTION_CLOSE = 1;
    private static final int DIRECTION_OPEN = 0;
    private static final int START_HIGH_TEMP = 200;
    private static final Path DAMPER_POSITION_FILE = Path.of(
            System.getProperty("user.home"),
            "damper_position.txt"
    );

    private final Temperature temperature;
    private final StepperMotor28BYJ48 stepperMotor;
    private final FileOutputStream tempFile;
    private final Memo memo;

    private int damperPosition = DAMPER_FULLY_OPEN;
    private int temp = 0;
    private int highTemp = START_HIGH_TEMP;
    private boolean inBurnLoop = false;
    private boolean fanOn = false;
    private String status = "Monitoring";

    public static void main(String[] args) {

        new MonitorStove();

    }

    public MonitorStove() {
        memo = new Memo();
        System.setProperty("spark.logging.quiet", "true");
        File f = new File("/home/gdesveau/timeVStemp.csv");
        try {
            tempFile = new FileOutputStream(f, true);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
        Context pi4j = Pi4J.newAutoContext();
        stepperMotor = new StepperMotor28BYJ48(pi4j);

        long debounce = 3000;
        DigitalInput button = pi4j.create(
                DigitalInput.newConfigBuilder(pi4j)
                        .id("button")
                        .name("Push Button")
                        .address(17) // GPIO 17
                        .pull(PullResistance.PULL_UP) // Enable internal pull-up resistor
                        .debounce(debounce) // Debounce to prevent false triggers
                        .provider("pigpio-digital-input")
                        .build()
        );
        button.addListener(event -> {
            if (event.state() == DigitalState.LOW) {
                logger.info("Button Pressed!");
                resetBurn();
            } else {
                logger.info("Button Released!");
            }
        });
        damperPosition = loadDamperPosition();
        //stepperMotor.performDemo(rotations);
        temperature = new Temperature();
        new WebInterface(this);

        temp = temperature.getTemp();
        status = "NOT iN BURN LOOP";
        logger.info("Temp: {}", temp);
        //logger.info("Moving damper to open");
        //stepperMotor.moveDamper(damperPosition, DIRECTION_OPEN);
        persistDamperPosition();
        emergencyCloseThread();
        hotThread();
        try {
            Thread.sleep(1000 * 60 * 1);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        coldThread();

    }

    int getTemp() {
        return temperature.getTemp();
    }

    int getDamperPosition() {
        return damperPosition;
    }

    int getHighTemp() {
        return highTemp;
    }

    String getStatus() {
        return status;
    }

    private void setStatus(String status) {
        this.status = status;
        logger.info("Status updated: {}", status);
    }

    void setHighTemp(int highTemp) {
        this.highTemp = highTemp;
        logger.info("Updated high temperature threshold to {}", highTemp);
    }

    private void adjustDamperPosition(int delta) {
        setDamperPosition(damperPosition + delta);
    }

    private void setDamperPosition(int position) {
        damperPosition = Math.max(0, Math.min(DAMPER_FULLY_OPEN, position));
        persistDamperPosition();
    }

    private void persistDamperPosition() {
        try {
            Files.writeString(
                    DAMPER_POSITION_FILE,
                    String.valueOf(damperPosition),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException e) {
            logger.error("Failed to persist damper position", e);
        }
    }

    private int loadDamperPosition() {
        if (!Files.exists(DAMPER_POSITION_FILE)) {
            return DAMPER_FULLY_OPEN;
        }

        try {
            String content = Files.readString(DAMPER_POSITION_FILE, StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) {
                return DAMPER_FULLY_OPEN;
            }
            int position = Integer.parseInt(content);
            return Math.max(0, Math.min(DAMPER_FULLY_OPEN, position));
        } catch (IOException | NumberFormatException e) {
            logger.error("Failed to read damper position from file, using default", e);
            return DAMPER_FULLY_OPEN;
        }
    }

    void resetBurn() {
        inBurnLoop = false;
        logger.info("Moving damper to open");
        stepperMotor.moveDamper(DAMPER_FULLY_OPEN - damperPosition, DIRECTION_OPEN);
        damperPosition = DAMPER_FULLY_OPEN;
        persistDamperPosition();
        setStatus("Burn reset - NOT IN BURN LOOP");
    }

    void openDamper() {
        int steps = 300;
        if (damperPosition < 6700) {
            stepperMotor.moveDamper(steps, DIRECTION_OPEN);
            damperPosition = damperPosition + steps;
            persistDamperPosition();
            logger.info("moving damper to: {}", damperPosition);
        }
    }

    void closeDamper() {
        int steps = damperPosition;
        logger.info("closing damper");
        stepperMotor.moveDamper(steps, DIRECTION_CLOSE);
        persistDamperPosition();
        logger.info("damper closed");
        setDamperPosition(0);
        setStatus("Damper closed manually");
    }

    void hotThread() {
       Thread hotThread=new Thread(() -> {
            while (true) {
                temp = temperature.getTemp();
                logger.info("Temp: {}", temp);
                logger.info("Damper: {}", damperPosition);
                logger.info("inBurnloop: {}", inBurnLoop);
                if (temp > (highTemp + 20) && inBurnLoop && damperPosition > 300) {
                    int steps = 300;

                    stepperMotor.moveDamper(steps, DIRECTION_CLOSE);
                    damperPosition -= steps;
                    persistDamperPosition();
                    logger.info("moving damper to: {}", damperPosition);
                    setStatus("Managing heat - damper closing");
                } else if (temp > (highTemp + 20) && inBurnLoop && damperPosition > 0) {
                    int steps = 100;

                    stepperMotor.moveDamper(steps, DIRECTION_CLOSE);
                    damperPosition -= steps;
                    persistDamperPosition();
                    logger.info("moving damper to: {}", damperPosition);
                    setStatus("Managing heat - damper closing");
                }
                int roomTemp = temperature.getRoomTemp();
                if (roomTemp > 50 && !fanOn) {
                    memo.setOn();
                    fanOn = true;
                } else if (roomTemp < 50 && roomTemp > 0 && fanOn) {
                    memo.setOff();
                    fanOn = false;
                }
                try {
                    Thread.sleep(1000 * 60 * 5);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        });
       hotThread.setName("holt thread");
       hotThread.start();
    }

    void coldThread() {
       Thread coldThread= new Thread(() -> {
            try {
                Thread.sleep(1000 * 60 * 20);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            while (true) {
                temp = temperature.getTemp();
                logger.info("Temp: {}", temp);
                logger.info("Damper: {}", damperPosition);
                if (temp < (highTemp ) && inBurnLoop && damperPosition < 600) {
                    int steps = 300;

                    stepperMotor.moveDamper(steps, DIRECTION_OPEN);
                    damperPosition += steps;
                    persistDamperPosition();
                    logger.info("temp < (highTemp) && inBurnLoop && damperPosition < 600 - moving damper to: {}", damperPosition);
                    setStatus("IN BURN LOOP - OPENING DAMPER");
                } else if (temp < (highTemp - 20) && inBurnLoop && damperPosition < 12*00) {
                    int steps = 300;

                    stepperMotor.moveDamper(steps, DIRECTION_OPEN);
                    damperPosition += steps;
                    persistDamperPosition();
                    logger.info("temp < (highTemp - 20) && inBurnLoop && damperPosition < 600 - moving damper to: {}", damperPosition);
                    setStatus("IN BURN LOOP - OPENING DAMPER");
                } else if (temp < (highTemp - 40) && inBurnLoop && damperPosition < 2400) {
                    int steps = 300;

                    stepperMotor.moveDamper(steps, DIRECTION_OPEN);
                    damperPosition += steps;
                    persistDamperPosition();
                    logger.info("temp < (highTemp - 40) && inBurnLoop && damperPosition < 1200 - moving damper to: {}", damperPosition);
                    setStatus("IN BURN LOOP - OPENING DAMPER");
                } else if (temp < 190 && inBurnLoop && damperPosition > 1000) {
                    int steps = damperPosition;

                    stepperMotor.moveDamper(steps, DIRECTION_CLOSE);
                    inBurnLoop = false;
                    damperPosition = 0;
                    persistDamperPosition();
                    logger.info("Fire is out, moving damper to: {}", damperPosition);
                    setStatus("Fire is out - NOT IN BURN LOOP");
                }
                try {
                    Thread.sleep(1000 * 60 * 10);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        });
       coldThread.setName("ColdThread");
       coldThread.start();
    }

    void emergencyCloseThread() {
      Thread e=  new Thread(() -> {
            int previousTemp = 300;
            while (true) {
                int currentTemp = temperature.getTemp();
                logger.info("Temp: {}", currentTemp);
                logger.info("Damper: {}", damperPosition);
                Date date = new Date();
                String timeTemp = date + "," + currentTemp + "," + damperPosition + "\n";
                try {
                    tempFile.write(timeTemp.getBytes(StandardCharsets.UTF_8));
                } catch (IOException ee) {
                    logger.error("Failed to write temperature to file", ee);
                }
                if (currentTemp > highTemp && (currentTemp - previousTemp) > 6 && !inBurnLoop) {
                    int steps = 6400;
                    if (damperPosition - steps < 600) {
                        steps = damperPosition - 600;
                    }
                    if (steps < 0) steps = 0;

                    stepperMotor.moveDamper(steps, DIRECTION_CLOSE);
                    inBurnLoop = true;
                    damperPosition = damperPosition-steps;
                    persistDamperPosition();
                    logger.info("temp is above 250 and rise is greater than 6. starting burn loop and moving damper to: {}", damperPosition);
                    setStatus("Burn loop started");
                } else if (currentTemp > (highTemp + 15) && !inBurnLoop) {
                    int steps = 6400;
                    if (damperPosition - steps < 600) {
                        steps = damperPosition - 600;
                    }
                    if (steps < 0) steps = 0;

                    stepperMotor.moveDamper(steps, DIRECTION_CLOSE);
                    inBurnLoop = true;
                    damperPosition -= steps;
                    persistDamperPosition();
                    logger.info("moving damper to: {}", damperPosition);
                    setStatus("Burn loop started");
                } else if (currentTemp > 280 && damperPosition > 300) {
                    int steps = 300;
                    stepperMotor.moveDamper(steps, DIRECTION_CLOSE);
                    damperPosition -= steps;
                    persistDamperPosition();
                    logger.info("Fire is too hot, moving damper to: {}", damperPosition);
                    setStatus("Fire too hot - closing damper 300");
                } else if (currentTemp > 290 && damperPosition > 0) {
                    int steps = 300;
                    stepperMotor.moveDamper(steps, DIRECTION_CLOSE);
                    damperPosition -= steps;
                    persistDamperPosition();
                    logger.info("Fire is too hot, moving damper to: {}", damperPosition);
                    setStatus("Fire way too hot - closing damper 300");
                }
                previousTemp = currentTemp;
                try {
                    Thread.sleep(60000);
                } catch (InterruptedException ee) {
                    throw new RuntimeException(ee);
                }
            }
        });
      e.setName("Emergency Close Thread");
      e.start();
    }
}
