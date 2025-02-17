package de.joeakeem.m28BYJ48;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;

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
    int temp=0;
    boolean inBurnLoop = false;
    FileOutputStream tempFile;
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
        System.setProperty("spark.logging.quiet", "true");
        File f=new File("/home/gdesveau/timeVStemp.csv");
        try {
            tempFile=new FileOutputStream(f,true);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
        Context pi4j = Pi4J.newAutoContext();
        stepperMotor = new
                StepperMotor28BYJ48(pi4j);
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
        System.exit(0);
        inBurnLoop = false;
        System.out.println("Moving damper to open");
        stepperMotor.moveDamper(3000-damperPosition, 0);
        damperPosition = 3000;
    }

    void openDamper() {
        int steps = 300;
        int direction = 0;
        if (damperPosition <2700) {
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
        damperPosition=0;
    }

    void hotThread() {
        new Thread() {
            public void run() {

                    while (true) {
                        temp = temperature.getTemp();
                        System.out.println("Temp: " + temp);
                        System.out.println("Damper: " + damperPosition);
                        System.out.println("inBurnloop: "+inBurnLoop);
                      if (temp > 230 && inBurnLoop && damperPosition > 300) {
                            int steps = 300;
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
                 if (temp < 200 && inBurnLoop && damperPosition < 3000) {
                        int steps = 300;
                        int direction = 0;

                        stepperMotor.moveDamper(steps, direction);
                        damperPosition = damperPosition + steps;
                        System.out.println("moving damper to: " + damperPosition);
                    } else if (temp < 150 && inBurnLoop && damperPosition > 1500) {
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
                while (true) {
                    int temp = temperature.getTemp();
                    System.out.println("Temp: " + temp);
                    System.out.println("Damper: " + damperPosition);
                    Date date=new Date();
                    String timeTemp=date+","+temp+","+damperPosition+"\n";
                    try {
                        tempFile.write(timeTemp.getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    if (temp > 230 && !inBurnLoop) {
                        int steps = 2100;
                        int direction = 1;
                        if (steps < 0) {
                            steps = 0 - steps;
                            direction = 0;
                        }

                        stepperMotor.moveDamper(steps, direction);
                        inBurnLoop = true;
                        damperPosition = 900;
                        System.out.println("moving damper to: " + damperPosition);
                    } else if (temp > 250 && damperPosition > 300) {
                        int steps = 300;
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
