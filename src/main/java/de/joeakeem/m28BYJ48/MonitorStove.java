package de.joeakeem.m28BYJ48;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import de.joeakeem.m28BYJ48.StepperMotor28BYJ48.SteppingMethod;

/**
 * Hello world!
 */
public class MonitorStove {
    Temperature temperature;

    public static void main(String[] args) {

        new MonitorStove();
    }

    public MonitorStove() {
        int damperPosition = 1000;
        int[] pins = {14, 15, 18, 23};
        Context pi4j = Pi4J.newAutoContext();
        StepperMotor28BYJ48 stepperMotor = new
                StepperMotor28BYJ48(pi4j, pins, 10, SteppingMethod.FULL_STEP);
        //stepperMotor.performDemo(rotations);
        temperature = new Temperature();
        boolean inBurnLoop = false;
        double temp = temperature.getTemp();
        while (true) {
            if (temp > 190 && !inBurnLoop) {
                int steps = damperPosition - (900);
                int direction = 1;
                if (steps < 0) {
                    steps = 0 - steps;
                    direction = 0;
                }
                stepperMotor.moveDamper(steps, direction);
                inBurnLoop = true;
                damperPosition = 100;
            }
            if (temp > 190 && inBurnLoop) {
                int steps = 100;
                int direction = 1;

                stepperMotor.moveDamper(steps, direction);
                damperPosition = damperPosition - steps;
            }
            if (temp < 150 && inBurnLoop) {
                int steps = 100;
                int direction = 0;

                stepperMotor.moveDamper(steps, direction);
                damperPosition = damperPosition + steps;
            }
            if (temp < 100 && inBurnLoop&&damperPosition>500) {
                int steps = damperPosition ;
                int direction = 1;

                stepperMotor.moveDamper(steps, direction);
                inBurnLoop = false;
                damperPosition = 0;
            }
        }
    }
}
