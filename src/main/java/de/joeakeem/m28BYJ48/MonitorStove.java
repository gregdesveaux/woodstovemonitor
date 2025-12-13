package de.joeakeem.m28BYJ48;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalInput;
import com.pi4j.io.gpio.digital.DigitalState;
import com.pi4j.io.gpio.digital.PullResistance;

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
    private static final int DAMPER_FULLY_OPEN = 3000;
    private static final int DIRECTION_CLOSE = 1;
    private static final int DIRECTION_OPEN = 0;
    private static final int START_HIGH_TEMP = 230;
    private static final Path DAMPER_POSITION_FILE = Path.of("damperPosition.txt");

    private final Temperature temperature;
    private final StepperMotor28BYJ48 stepperMotor;
    private final FileOutputStream tempFile;
    private final Memo memo;

    private int damperPosition = DAMPER_FULLY_OPEN;
    private int temp = 0;
    private int highTemp = START_HIGH_TEMP;
    private boolean inBurnLoop = false;
    private boolean fanOn = false;

    public static void main(String[] args) {

        new MonitorStove();

    }

    public MonitorStove() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Closing damper");
            stepperMotor.moveDamper(damperPosition, DIRECTION_CLOSE);
            System.out.println("Damper Closed");
        }));
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
                System.out.println("Button Pressed!");
                resetBurn();
            } else {
                System.out.println("Button Released!");
            }
        });
        damperPosition = loadDamperPosition();
        //stepperMotor.performDemo(rotations);
        temperature = new Temperature();
        new WebInterface(this);

        temp = temperature.getTemp();
        System.out.println("Temp: " + temp);
        System.out.println("Moving damper to open");
        stepperMotor.moveDamper(damperPosition, DIRECTION_OPEN);
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
            System.err.println("Failed to persist damper position: " + e.getMessage());
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
            System.err.println("Failed to read damper position from file, using default: " + e.getMessage());
            return DAMPER_FULLY_OPEN;
        }
    }

    void resetBurn() {
        inBurnLoop = false;
        System.out.println("Moving damper to open");
        stepperMotor.moveDamper(DAMPER_FULLY_OPEN - damperPosition, DIRECTION_OPEN);
        setDamperPosition(DAMPER_FULLY_OPEN);
    }

    void openDamper() {
        int steps = 300;
        if (damperPosition < 2700) {
            stepperMotor.moveDamper(steps, DIRECTION_OPEN);
            adjustDamperPosition(steps);
            System.out.println("moving damper to: " + damperPosition);
        }
    }

    void closeDamper() {
        int steps = damperPosition;
        System.out.println("closing damper");
        stepperMotor.moveDamper(steps, DIRECTION_CLOSE);
        System.out.println("damper closed");
        setDamperPosition(0);
    }

    void hotThread() {
        new Thread(() -> {
            while (true) {
                temp = temperature.getTemp();
                System.out.println("Temp: " + temp);
                System.out.println("Damper: " + damperPosition);
                System.out.println("inBurnloop: " + inBurnLoop);
                if (temp > (highTemp + 20) && inBurnLoop && damperPosition > 300) {
                    int steps = 300;

                    stepperMotor.moveDamper(steps, DIRECTION_CLOSE);
                    adjustDamperPosition(-steps);
                    System.out.println("moving damper to: " + damperPosition);
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
        }).start();
    }

    void coldThread() {
        new Thread(() -> {
            try {
                Thread.sleep(1000 * 60 * 20);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            while (true) {
                temp = temperature.getTemp();
                System.out.println("Temp: " + temp);
                System.out.println("Damper: " + damperPosition);
                if (temp < (highTemp - 20) && inBurnLoop && damperPosition < 600) {
                    int steps = 300;

                    stepperMotor.moveDamper(steps, DIRECTION_OPEN);
                    adjustDamperPosition(steps);
                    System.out.println("moving damper to: " + damperPosition);
                } else if (temp < (highTemp - 25) && inBurnLoop && damperPosition < 300) {
                    int steps = 300;

                    stepperMotor.moveDamper(steps, DIRECTION_OPEN);
                    adjustDamperPosition(steps);
                    System.out.println("moving damper to: " + damperPosition);
                } else if (temp < (highTemp - 40) && inBurnLoop && damperPosition < 1200) {
                    int steps = 300;

                    stepperMotor.moveDamper(steps, DIRECTION_OPEN);
                    adjustDamperPosition(steps);
                    System.out.println("moving damper to: " + damperPosition);
                } else if (temp < 190 && inBurnLoop && damperPosition > 1000) {
                    int steps = damperPosition;

                    stepperMotor.moveDamper(steps, DIRECTION_CLOSE);
                    inBurnLoop = false;
                    setDamperPosition(0);
                    System.out.println("Fire is out, moving damper to: " + damperPosition);
                }
                try {
                    Thread.sleep(1000 * 60 * 10);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }).start();
    }

    void emergencyCloseThread() {
        new Thread(() -> {
            int previousTemp = 300;
            while (true) {
                int currentTemp = temperature.getTemp();
                System.out.println("Temp: " + currentTemp);
                System.out.println("Damper: " + damperPosition);
                Date date = new Date();
                String timeTemp = date + "," + currentTemp + "," + damperPosition + "\n";
                try {
                    tempFile.write(timeTemp.getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    e.printStackTrace();
                }
                if (currentTemp > highTemp && (currentTemp - previousTemp) > 6 && !inBurnLoop) {
                    int steps = 2400;
                    if (steps < 0) {
                        steps = 0 - steps;
                    }

                    stepperMotor.moveDamper(steps, DIRECTION_CLOSE);
                    inBurnLoop = true;
                    setDamperPosition(600);
                    System.out.println("temp is above 250 and rise is greater than 6. starting burn loop and moving damper to: " + damperPosition);
                } else if (currentTemp > (highTemp + 15) && !inBurnLoop) {
                    int steps = 2400;
                    if (steps < 0) {
                        steps = 0 - steps;
                    }

                    stepperMotor.moveDamper(steps, DIRECTION_CLOSE);
                    inBurnLoop = true;
                    setDamperPosition(600);
                    System.out.println("moving damper to: " + damperPosition);
                } else if (currentTemp > 280 && damperPosition > 300) {
                    int steps = 300;
                    stepperMotor.moveDamper(steps, DIRECTION_CLOSE);
                    adjustDamperPosition(-steps);
                    System.out.println("Fire is too hot, moving damper to: " + damperPosition);
                } else if (currentTemp > 290 && damperPosition > 0) {
                    int steps = 300;
                    stepperMotor.moveDamper(steps, DIRECTION_CLOSE);
                    adjustDamperPosition(-steps);
                    System.out.println("Fire is too hot, moving damper to: " + damperPosition);
                }
                previousTemp = currentTemp;
                try {
                    Thread.sleep(60000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }).start();
    }
}
