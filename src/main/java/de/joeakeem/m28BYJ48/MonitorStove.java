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
import java.util.Date;

/**
 * Hello world!
 */
public class MonitorStove {
    Temperature temperature;
    int damperPosition = 3000;
    StepperMotor28BYJ48 stepperMotor = null;
    int temp = 0;
    boolean inBurnLoop = false;
    FileOutputStream tempFile;
    int highTemp = 230;
    Memo memo;
    boolean fanOn = false;

    public static void main(String[] args) {

        new MonitorStove();

    }

    public MonitorStove() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Closing damper");
            int steps = damperPosition;
            stepperMotor.moveDamper(steps, 1);
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
        stepperMotor = new
                StepperMotor28BYJ48(pi4j);
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
        //stepperMotor.performDemo(rotations);
        temperature = new Temperature();
        new WebInterface(this);

        temp = temperature.getTemp();
        System.out.println("Temp: " + temp);
        System.out.println("Moving damper to open");
        stepperMotor.moveDamper(damperPosition, 0);
        emergenyCloseThread();
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

    void resetBurn() {
        //System.exit(0);
        inBurnLoop = false;
        System.out.println("Moving damper to open");
        stepperMotor.moveDamper(3000 - damperPosition, 0);
        damperPosition = 3000;
    }

    void openDamper() {
        int steps = 300;
        int direction = 0;
        if (damperPosition < 2700) {
            stepperMotor.moveDamper(steps, direction);
            damperPosition = damperPosition + steps;
            System.out.println("moving damper to: " + damperPosition);
        }
    }

    void closeDamper() {
        int steps = damperPosition;
        System.out.println("closing damper");
        stepperMotor.moveDamper(steps, 1);
        System.out.println("damper closed");
        damperPosition = 0;
    }

    void hotThread() {
        new Thread() {
            public void run() {

                while (true) {
                    temp = temperature.getTemp();
                    System.out.println("Temp: " + temp);
                    System.out.println("Damper: " + damperPosition);
                    System.out.println("inBurnloop: " + inBurnLoop);
                    if (temp > (highTemp + 20) && inBurnLoop && damperPosition > 300) {
                        int steps = 300;
                        int direction = 1;

                        stepperMotor.moveDamper(steps, direction);
                        damperPosition = damperPosition - steps;
                        System.out.println("moving damper to: " + damperPosition);
                    }
                    int roomTemp=temperature.getRoomTemp();
                    if (roomTemp > 50 && !fanOn) {
                        memo.setOn();
                        fanOn = true;
                    } else if (roomTemp < 50 && roomTemp>0 && fanOn) {
                        memo.setOff();
                        fanOn = false;
                    }
                    try {
                        Thread.sleep(1000 * 60 * 5);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }

            }
        }.start();
    }

    void coldThread() {
        new Thread() {
            public void run() {
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
                        int direction = 0;

                        stepperMotor.moveDamper(steps, direction);
                        damperPosition = damperPosition + steps;
                        System.out.println("moving damper to: " + damperPosition);
                    } else if (temp < (highTemp - 25) && inBurnLoop && damperPosition < 300) {
                        int steps = 300;
                        int direction = 0;

                        stepperMotor.moveDamper(steps, direction);
                        damperPosition = damperPosition + steps;
                        System.out.println("moving damper to: " + damperPosition);
                    } else if (temp < (highTemp - 40) && inBurnLoop && damperPosition < 1200) {
                        int steps = 300;
                        int direction = 0;

                        stepperMotor.moveDamper(steps, direction);
                        damperPosition = damperPosition + steps;
                        System.out.println("moving damper to: " + damperPosition);
                    } else if (temp < 190 && inBurnLoop && damperPosition > 1000) {
                        int steps = damperPosition;
                        int direction = 1;

                        stepperMotor.moveDamper(steps, direction);
                        inBurnLoop = false;
                        damperPosition = 0;
                        System.out.println("Fire is out, moving damper to: " + damperPosition);
                    }
                    try {
                        Thread.sleep(1000 * 60 * 10);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }

            }
        }.start();
    }

    void emergenyCloseThread() {
        new Thread() {
            public void run() {
                int prevTemp = 300;
                while (true) {
                    int temp = temperature.getTemp();
                    System.out.println("Temp: " + temp);
                    System.out.println("Damper: " + damperPosition);
                    Date date = new Date();
                    String timeTemp = date + "," + temp + "," + damperPosition + "\n";
                    try {
                        tempFile.write(timeTemp.getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    if (temp > highTemp && (temp - prevTemp) > 6 && !inBurnLoop) {
                        int steps = 2400;
                        int direction = 1;
                        if (steps < 0) {
                            steps = 0 - steps;
                            direction = 0;
                        }

                        stepperMotor.moveDamper(steps, direction);
                        inBurnLoop = true;
                        damperPosition = 600;
                        System.out.println("temp is above 250 and rise is greater than 6. starting burn loop and moving damper to: " + damperPosition);
                    } else if (temp > (highTemp + 15) && !inBurnLoop) {
                        int steps = 2400;
                        int direction = 1;
                        if (steps < 0) {
                            steps = 0 - steps;
                            direction = 0;
                        }

                        stepperMotor.moveDamper(steps, direction);
                        inBurnLoop = true;
                        damperPosition = 600;
                        System.out.println("moving damper to: " + damperPosition);
                    } else if (temp > 280 && damperPosition > 300) {
                        int steps = 300;
                        int direction = 1;

                        stepperMotor.moveDamper(steps, direction);
                        damperPosition = damperPosition - steps;
                        System.out.println("Fire is too hot, moving damper to: " + damperPosition);
                    } else if (temp > 290 && damperPosition > 0) {
                        int steps = 300;
                        int direction = 1;

                        stepperMotor.moveDamper(steps, direction);
                        damperPosition = damperPosition - steps;
                        System.out.println("Fire is too hot, moving damper to: " + damperPosition);
                    }
                    prevTemp = temp;
                    try {
                        Thread.sleep(60000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }.start();
    }
}
