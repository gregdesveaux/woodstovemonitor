package de.joeakeem.m28BYJ48;

import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalOutput;
import com.pi4j.io.gpio.digital.DigitalState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.pi4j.io.gpio.digital.DigitalState.HIGH;
import static com.pi4j.io.gpio.digital.DigitalState.LOW;

public class StepperMotor28BYJ48 {

    private static final Logger LOG = LoggerFactory.getLogger(StepperMotor28BYJ48.class);
    private static final int STEP_DELAY_MILLIS = 10;

    private final Context pi4j;
    private final DigitalOutput stepPin;
    private final DigitalOutput dirPin;
    private final DigitalOutput enablePin;

    public StepperMotor28BYJ48(Context pi4j) {
        this.pi4j = pi4j;

        stepPin = pi4j.create(
                DigitalOutput.newConfigBuilder(pi4j)
                        .id("STEP_PIN")
                        .name("Stepper Step Pin")
                        .address(18)
                        .shutdown(DigitalState.LOW)
                        .initial(DigitalState.LOW)
                        .provider("pigpio-digital-output")
                        .build());

        dirPin = pi4j.create(
                DigitalOutput.newConfigBuilder(pi4j)
                        .id("DIR_PIN")
                        .name("Stepper Direction Pin")
                        .address(23)
                        .shutdown(DigitalState.LOW)
                        .initial(DigitalState.LOW)
                        .provider("pigpio-digital-output")
                        .build());

        enablePin = pi4j.create(
                DigitalOutput.newConfigBuilder(pi4j)
                        .id("ENABLE_PIN")
                        .name("Stepper Enable Pin")
                        .address(24)
                        .shutdown(HIGH)
                        .initial(HIGH)
                        .provider("pigpio-digital-output")
                        .build());
    }

    public void moveDamper(int steps, int direction) {
        LOG.info("moving damper {} rotations", steps);
        if (direction == 1) {
            dirPin.high();
        } else {
            dirPin.low();
        }

        enablePin.low();
        for (int i = 0; i < steps; i++) {
            stepPin.high();
            sleepMillis(STEP_DELAY_MILLIS);
            stepPin.low();
            sleepMillis(STEP_DELAY_MILLIS);
        }
        enablePin.high();
        shutdown();
        LOG.info("Done.");
    }

    private static void sleepMillis(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            ex.printStackTrace();
        }
    }

    public void shutdown() {
        stepPin.state(LOW);
        stepPin.shutdown(pi4j);
        dirPin.state(LOW);
        dirPin.shutdown(pi4j);
        enablePin.state(HIGH);
        enablePin.shutdown(pi4j);
    }
}