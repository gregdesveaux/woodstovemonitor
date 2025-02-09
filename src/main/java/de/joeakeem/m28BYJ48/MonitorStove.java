package de.joeakeem.m28BYJ48;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import de.joeakeem.m28BYJ48.StepperMotor28BYJ48.SteppingMethod;

/**
 * Hello world!
 */
public class MonitorStove {
    Temperature temperature;
    int damperPosition = 1000;
    StepperMotor28BYJ48 stepperMotor = null;
    int temp=0;
    boolean inBurnLoop = false;
    public static void main(String[] args) {

        new MonitorStove();

    }

    public MonitorStove() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutdown Hook is running!");
            int steps = damperPosition;
            stepperMotor.moveDamper(steps, 1);
        }));
        System.setProperty("spark.logging.quiet", "true");
        int[] pins = {14, 15, 18, 23};
        Context pi4j = Pi4J.newAutoContext();
        stepperMotor = new
                StepperMotor28BYJ48(pi4j, pins, 10, SteppingMethod.FULL_STEP);
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
    int getTemp(){
        return temperature.getTemp();
    }
    int getDamperPosition(){
        return damperPosition;
    }
    void resetBurn(){
        boolean inBurnLoop = false;
        System.out.println("Moving damper to open");
        stepperMotor.moveDamper(1000-damperPosition, 0);
        damperPosition = 1000;
    }
    void hotThread() {
        new Thread() {
            public void run() {

                    while (true) {
                        temp = temperature.getTemp();
                        System.out.println("Temp: " + temp);
                        System.out.println("Damper: " + damperPosition);
                        if (temp > 190 && !inBurnLoop) {
                            int steps = 800;
                            int direction = 1;
                            if (steps < 0) {
                                steps = 0 - steps;
                                direction = 0;
                            }

                            stepperMotor.moveDamper(steps, direction);
                            inBurnLoop = true;
                            damperPosition = 200;
                            System.out.println("moving damper to: " + damperPosition);
                        } else if (temp > 210 && inBurnLoop && damperPosition > 100) {
                            int steps = 100;
                            int direction = 1;

                            stepperMotor.moveDamper(steps, direction);
                            damperPosition = damperPosition - steps;
                            System.out.println("moving damper to: " + damperPosition);
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
                 if (temp < 190 && inBurnLoop && damperPosition < 1000) {
                        int steps = 100;
                        int direction = 0;

                        stepperMotor.moveDamper(steps, direction);
                        damperPosition = damperPosition + steps;
                        System.out.println("moving damper to: " + damperPosition);
                    } else if (temp < 150 && inBurnLoop && damperPosition > 500) {
                        int steps = damperPosition;
                        int direction = 1;

                        stepperMotor.moveDamper(steps, direction);
                        inBurnLoop = false;
                        damperPosition = 0;
                        System.out.println("Fire is out, moving damper to: " + damperPosition);
                    }
                    try {
                        Thread.sleep(1000 * 60 * 20);
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
                while (true) {
                    int temp = temperature.getTemp();
                    System.out.println("Temp: " + temp);
                    System.out.println("Damper: " + damperPosition);
                    if (temp > 220 && damperPosition > 100) {
                        int steps = 100;
                        int direction = 1;

                        stepperMotor.moveDamper(steps, direction);
                        damperPosition = damperPosition - steps;
                        System.out.println("Fire is too hot, moving damper to: " + damperPosition);
                    }
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
